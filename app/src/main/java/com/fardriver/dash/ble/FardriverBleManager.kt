package com.fardriver.dash.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Standard "BLE UART"-Service, den die meisten Fardriver-Dongles (und viele andere
 * China-BLE-Module, z.B. HM-10/JDY-Klone) verwenden. Falls dein Dongle andere UUIDs
 * nutzt, hier bzw. beim ersten Verbindungsversuch (siehe discoverServices-Callback,
 * der ALLE Services/Characteristics loggt) anpassen.
 */
object FardriverGatt {
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    // Manche Dongles nutzen stattdessen 0xFFEC fuer Notify - wird als Fallback probiert.
    val NOTIFY_CHAR_UUID_ALT: UUID = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Freischalt-/Keepalive-Befehle, per BLE-HCI-Snoop-Mitschnitt aus der ORIGINALEN
     * Fardriver-App gewonnen (kein Rate-/Zufallswert, sondern ein fest verdrahteter
     * Byte-String - vermutlich abgeleitet aus dem Standard-Passwort "000000" des Dongles).
     * Ohne diesen Schreibvorgang liefert der Dongle nur kryptische Status-Pakete statt
     * der echten 16-Byte-Telemetrie-Frames.
     */
    val UNLOCK_CMD = byteArrayOf(0xAA.toByte(), 0x13, 0xEC.toByte(), 0x07, 0x5F, 0x5F, 0x6E, 0x91.toByte())
    val POLL_CMD = byteArrayOf(0xAA.toByte(), 0x13, 0xEC.toByte(), 0x07, 0x09, 0x6F, 0x28, 0xD7.toByte())
}

data class BleDeviceInfo(val name: String?, val address: String, val rssi: Int)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object DiscoveringServices : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@SuppressLint("MissingPermission")
class FardriverBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private val incomingBuffer = mutableListOf<Byte>()
    private val descriptorWriteQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var descriptorWriteInFlight = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var keepAliveJob: Job? = null

    val scanResults = MutableStateFlow<Map<String, BleDeviceInfo>>(emptyMap())
    val isScanning = MutableStateFlow(false)
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val vehicleState = MutableStateFlow(FardriverProtocol.VehicleState())
    val lastRawFrame = MutableStateFlow<String>("")
    val lastRawChunk = MutableStateFlow<String>("")
    val rawChunkHistory = MutableStateFlow<List<String>>(emptyList())
    val discoveredServices = MutableStateFlow<List<String>>(emptyList())
    val gattEvents = MutableStateFlow<List<String>>(emptyList())

    private fun logEvent(msg: String) {
        Log.d("FardriverBle", msg)
        gattEvents.update { (it + msg).takeLast(15) }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanResults.value = emptyMap()
        isScanning.value = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val info = BleDeviceInfo(device.name, device.address, result.rssi)
            scanResults.update { it + (device.address to info) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.value = false
            connectionState.value = ConnectionState.Error("Scan fehlgeschlagen ($errorCode)")
        }
    }

    fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        descriptorWriteQueue.clear()
        descriptorWriteInFlight = false
        connectionState.value = ConnectionState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState.value = ConnectionState.DiscoveringServices
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionState.value = ConnectionState.Error("Service-Discovery fehlgeschlagen")
                return
            }

            // Debug: alle gefundenen Services/Characteristics loggen UND in der UI anzeigen -
            // hilfreich, um die richtigen UUIDs fuer deinen konkreten Dongle herauszufinden.
            val summary = mutableListOf<String>()
            for (service in g.services) {
                Log.d("FardriverBle", "Service ${service.uuid}")
                summary.add("Service ${service.uuid}")
                for (c in service.characteristics) {
                    Log.d("FardriverBle", "  Char ${c.uuid} props=${c.properties}")
                    summary.add("  Char ${shortUuid(c.uuid)} props=${propsToString(c.properties)}")
                }
            }
            discoveredServices.value = summary

            // Statt uns auf eine einzige bekannte UUID zu verlassen: auf ALLE Characteristics
            // abonnieren, die Notify oder Indicate unterstuetzen. So sehen wir garantiert
            // Daten, egal welche UUID dein konkreter Dongle tatsaechlich benutzt.
            val notifiable = g.services.flatMap { it.characteristics }
                .filter {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                }

            if (notifiable.isEmpty()) {
                connectionState.value = ConnectionState.Error("Keine Notify/Indicate-Characteristic gefunden")
                return
            }

            for (char in notifiable) {
                val okNotify = g.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(FardriverGatt.CCCD_UUID)
                if (cccd == null) {
                    logEvent("${shortUuid(char.uuid)}: kein CCCD-Descriptor gefunden (setNotification ok=$okNotify)")
                    continue
                }
                val isIndicate = char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                val enableValue = if (isIndicate)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                logEvent("${shortUuid(char.uuid)}: setNotification ok=$okNotify, queued for CCCD-write")
                descriptorWriteQueue.addLast(cccd to enableValue)
            }
            processNextDescriptorWrite(g)

            // Groesseres MTU anfragen - manche Dongles senden erst danach zuverlaessig.
            g.requestMtu(185)

            connectionState.value = ConnectionState.Connected
            startUnlockAndKeepAlive()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }

        // Fallback fuer aeltere Android-Versionen (API < 33), wo obige Methode nicht existiert.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleIncoming(characteristic.value ?: return)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"
            logEvent("CCCD-Write ${shortUuid(descriptor.characteristic.uuid)}: $statusStr")
            descriptorWriteInFlight = false
            processNextDescriptorWrite(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logEvent("MTU changed to $mtu (status=$status)")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"
            logEvent("Write ${shortUuid(characteristic.uuid)}: $statusStr")
        }
    }

    /**
     * Manuell einen Byte-String an eine bestimmte Characteristic schreiben - nuetzlich, um
     * Handshake-/Passwort-Sequenzen auszuprobieren, die manche BLE-Dongles vor dem Senden
     * von Telemetriedaten erwarten. hexOrText: entweder Hex ("AA 55 01") oder Klartext.
     */
    fun writeToCharacteristic(charUuid: UUID, bytes: ByteArray) {
        val g = gatt ?: return
        val target = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == charUuid } ?: run {
            logEvent("writeToCharacteristic: Characteristic $charUuid nicht gefunden")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(target, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            target.value = bytes
            @Suppress("DEPRECATION")
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(target)
        }
        logEvent("writeToCharacteristic ${shortUuid(charUuid)}: ${FardriverProtocol.hexDump(bytes)}")
    }

    /**
     * BLE erlaubt immer nur EINEN ausstehenden GATT-Schreibvorgang gleichzeitig. Schreibt man
     * mehrere CCCD-Descriptoren direkt hintereinander, schlagen alle bis auf den ersten mit
     * Status 201 (ERROR_GATT_WRITE_REQUEST_BUSY) fehl. Deshalb: strikt nacheinander abarbeiten,
     * erst der naechste Write, wenn der onDescriptorWrite-Callback fuer den vorherigen kam.
     */
    private fun processNextDescriptorWrite(g: BluetoothGatt) {
        if (descriptorWriteInFlight) return
        val next = descriptorWriteQueue.removeFirstOrNull() ?: return
        val (descriptor, value) = next
        descriptorWriteInFlight = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    /**
     * Sendet den Freischalt-Befehl 3x und startet danach eine dauerhafte Keep-Alive-Schleife
     * (~alle 750ms), exakt wie im Mitschnitt der offiziellen App beobachtet. Ohne das bleibt
     * der Dongle im "Status-Only"-Modus haengen bzw. faellt nach kurzer Zeit dahin zurueck.
     */
    private fun startUnlockAndKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            repeat(3) {
                writeToCharacteristic(FardriverGatt.NOTIFY_CHAR_UUID_ALT, FardriverGatt.UNLOCK_CMD)
                delay(700)
            }
            while (true) {
                writeToCharacteristic(FardriverGatt.NOTIFY_CHAR_UUID_ALT, FardriverGatt.POLL_CMD)
                delay(750)
            }
        }
    }

    private fun handleIncoming(chunk: ByteArray) {
        val chunkLine = "len=${chunk.size} bytes=${FardriverProtocol.hexDump(chunk)}"
        lastRawChunk.value = chunkLine
        rawChunkHistory.update { (it + chunkLine).takeLast(60) }
        synchronized(incomingBuffer) {
            incomingBuffer.addAll(chunk.toList())
            val frames = FardriverProtocol.extractFrames(incomingBuffer.toByteArray())
            if (frames.isNotEmpty()) {
                var state = vehicleState.value
                for (frame in frames) {
                    state = FardriverProtocol.applyFrame(state, frame)
                }
                vehicleState.value = state
                lastRawFrame.value = frames.last().let {
                    "idx=${it.index} addr=0x${it.address.toString(16)} data=${FardriverProtocol.hexDump(it.data)}"
                }
                // Verarbeitete Bytes aus dem Buffer entfernen, Rest (unvollstaendiges Frame) behalten.
                val consumed = frames.size * FardriverProtocol.PACKET_SIZE
                repeat(consumed.coerceAtMost(incomingBuffer.size)) { incomingBuffer.removeAt(0) }
            }
            if (incomingBuffer.size > 512) incomingBuffer.clear() // Sicherheitsnetz
        }
    }

    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-1000-8000-00805f9b34fb")) {
            "0x" + s.substring(4, 8).uppercase()
        } else s
    }

    private fun propsToString(props: Int): String {
        val list = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NR")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return list.joinToString(",")
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true
}
