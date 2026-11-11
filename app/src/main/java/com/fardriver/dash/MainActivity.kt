package com.fardriver.dash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fardriver.dash.ble.BleDeviceInfo
import com.fardriver.dash.ble.ConnectionState
import com.fardriver.dash.ble.FardriverBleManager
import com.fardriver.dash.ble.FardriverProtocol
import com.fardriver.dash.ble.LastDeviceStore
import com.fardriver.dash.ble.SettingsStore
import com.fardriver.dash.ui.DashboardScreen
import com.fardriver.dash.ui.ScanScreen
import com.fardriver.dash.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: FardriverBleManager
    private lateinit var lastDeviceStore: LastDeviceStore
    private lateinit var settingsStore: SettingsStore

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = FardriverBleManager(applicationContext)
        lastDeviceStore = LastDeviceStore(applicationContext)
        settingsStore = SettingsStore(applicationContext)

        // Gespeicherte Kalibrierwerte laden (falls vorhanden), sonst bleiben die Defaults
        // aus FardriverProtocol (aktuell auf die Yozma i10 kalibriert) aktiv.
        FardriverProtocol.motorPolePairs = settingsStore.getMotorPolePairs(FardriverProtocol.motorPolePairs)
        FardriverProtocol.wheelCircumferenceM = settingsStore.getWheelCircumferenceM(FardriverProtocol.wheelCircumferenceM)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* Ergebnis wird ueber granted-State im Compose-Tree geprueft */ }

        setContent {
            var showScan by remember { mutableStateOf(true) }
            var showSettings by remember { mutableStateOf(false) }
            var autoConnectFailed by remember { mutableStateOf(false) }
            val savedAddress = remember { lastDeviceStore.getSavedAddress() }
            val savedName = remember { lastDeviceStore.getSavedName() }

            val scanResults by bleManager.scanResults.collectAsStateWithLifecycle()
            val isScanning by bleManager.isScanning.collectAsStateWithLifecycle()
            val connectionState by bleManager.connectionState.collectAsStateWithLifecycle()
            val vehicleState by bleManager.vehicleState.collectAsStateWithLifecycle()
            val rawDebug by bleManager.lastRawFrame.collectAsStateWithLifecycle()
            val rawChunk by bleManager.lastRawChunk.collectAsStateWithLifecycle()
            val rawChunkHistory by bleManager.rawChunkHistory.collectAsStateWithLifecycle()
            val discoveredServices by bleManager.discoveredServices.collectAsStateWithLifecycle()
            val gattEvents by bleManager.gattEvents.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                permissionLauncher.launch(requiredPermissions)
                // Kurz warten, bis die Berechtigungs-Dialoge durch sind, dann ggf. automatisch
                // mit dem zuletzt genutzten Geraet verbinden - ohne erneuten manuellen Scan.
                kotlinx.coroutines.delay(400)
                if (savedAddress != null) {
                    showScan = false
                    bleManager.connect(savedAddress)
                    // Sicherheitsnetz: BLE-Verbindungsversuche zu einem nicht erreichbaren
                    // Geraet koennen haengen bleiben, ohne einen Fehler zu melden. Nach ein
                    // paar Sekunden ohne Erfolg zurueck zum manuellen Scan-Screen.
                    kotlinx.coroutines.delay(8000)
                    if (bleManager.connectionState.value !is ConnectionState.Connected) {
                        bleManager.disconnect()
                        autoConnectFailed = true
                        showScan = true
                    }
                }
            }

            LaunchedEffect(connectionState) {
                if (connectionState is ConnectionState.Connected) {
                    showScan = false
                    autoConnectFailed = false
                }
                if (connectionState is ConnectionState.Error && savedAddress != null && showScan.not()) {
                    // Automatischer Verbindungsversuch mit gespeichertem Geraet fehlgeschlagen
                    // (z.B. Fahrzeug/Dongle gerade nicht in Reichweite) - zurueck zum Scan-Screen.
                    autoConnectFailed = true
                    showScan = true
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showSettings) {
                        SettingsScreen(
                            initialMotorPolePairs = FardriverProtocol.motorPolePairs,
                            initialWheelCircumferenceM = FardriverProtocol.wheelCircumferenceM,
                            onBack = { showSettings = false },
                            onSave = { polePairs, circumference ->
                                FardriverProtocol.motorPolePairs = polePairs
                                FardriverProtocol.wheelCircumferenceM = circumference
                                settingsStore.save(polePairs, circumference)
                                showSettings = false
                            }
                        )
                    } else if (showScan) {
                        ScanScreen(
                            isScanning = isScanning,
                            devices = scanResults.values.toList(),
                            autoConnectHint = if (autoConnectFailed && savedName != null) {
                                "Automatische Verbindung mit \"$savedName\" fehlgeschlagen - bitte manuell waehlen."
                            } else null,
                            onStartScan = { bleManager.startScan() },
                            onStopScan = { bleManager.stopScan() },
                            onDeviceSelected = { device: BleDeviceInfo ->
                                lastDeviceStore.save(device.address, device.name)
                                bleManager.connect(device.address)
                            }
                        )
                    } else {
                        DashboardScreen(
                            connectionState = connectionState,
                            state = vehicleState,
                            rawDebugLine = rawDebug,
                            rawChunkLine = rawChunk,
                            rawChunkHistory = rawChunkHistory,
                            discoveredServices = discoveredServices,
                            gattEvents = gattEvents,
                            onScanClick = {
                                bleManager.disconnect()
                                showScan = true
                            },
                            onSettingsClick = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
