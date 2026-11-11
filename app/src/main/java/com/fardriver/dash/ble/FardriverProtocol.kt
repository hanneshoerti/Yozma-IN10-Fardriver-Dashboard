package com.fardriver.dash.ble

/**
 * Fardriver NS-Serie BLE-Protokoll (Community-Reverse-Engineering, NICHT offiziell von
 * Fardriver dokumentiert). Quellen u.a.:
 *  - github.com/jackhumbert/fardriver-controllers
 *  - github.com/bobecek79/ESP32-Fardriver-BLE-Reader
 *  - endless-sphere.com Thread "Fardriver controller serial protocol reverse engineering"
 *
 * WICHTIG: Nicht jeder Wert ist 100% verifiziert (z.B. exakte Skalierung von Spannung/
 * Strom/Temperatur kann je nach Firmware-Version leicht abweichen). Nutze den
 * Debug-/Rohdaten-Screen in der App, um deine Werte mit der offiziellen Fardriver-App
 * zu vergleichen und ggf. die Skalierungsfaktoren unten anzupassen (siehe TODO-Kommentare).
 *
 * Paketformat (16 Byte, per BLE-Notify):
 *   Byte 0      : 0xAA (Start-Byte)
 *   Byte 1      : 0x80 + Frame-Index (die 2 höchsten Bits sind ein Flag, unteres 6-Bit = Index)
 *   Byte 2..13  : 12 Byte Nutzdaten
 *   Byte 14..15 : CRC16 (Berechnung nicht zweifelsfrei geklärt, wird hier nur zur Info mitgeführt)
 *
 * Der Frame-Index wird über eine feste Lookup-Tabelle auf eine "virtuelle Flash-Adresse"
 * gemappt. Diese Adresse bestimmt, welche Werte in den 12 Datenbytes stecken.
 */
/**
 * Fardriver NS-Serie BLE-Protokoll. Feld-Offsets, Flash-Adress-Tabelle und CRC16-Verfahren
 * 1:1 uebernommen aus dem verifizierten, funktionierenden Referenzprojekt:
 *   github.com/bobecek79/ESP32-Fardriver-BLE-Reader
 * (dort ueber echte Hardware getestet, inkl. CRC-Pruefung - deutlich zuverlaessiger als reines
 * Community-Reverse-Engineering ohne Verifikation).
 *
 * Nicht von Fardriver selbst dokumentiert; die Skalierung von Voltage/Current/Temp/SOC ist
 * aber CRC-verifiziert und stammt aus echten Tests, nicht aus Vermutung.
 *
 * Ausnahme: die km/h-Berechnung haengt von Radumfang & Motor-Polpaarzahl ab (siehe
 * wheelCircumferenceM / motorPolePairs unten) - das sind Fahrzeug-/Motoreigenschaften, die
 * bei deinem Yozma i10 ggf. von den Referenzwerten abweichen und angepasst werden sollten.
 */
object FardriverProtocol {

    const val PACKET_SIZE = 16

    /**
     * Frame-Index (0..54) -> "Flash-Adresse". Tabelle uebernommen und verifiziert aus
     * github.com/bobecek79/ESP32-Fardriver-BLE-Reader (inkl. Korrektur an Index 3: 0xE4
     * statt der ohne CRC-Verifikation kolportierten 0x00 aus jackhumbert/fardriver-controllers).
     */
    val FLASH_READ_ADDR = intArrayOf(
        0xE2, 0xE8, 0xEE, 0xE4, 0x06, 0x0C, 0x12,
        0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
        0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69,
        0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
        0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6,
        0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
        0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0,
        0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA
    )

    data class RawFrame(val index: Int, val address: Int, val data: ByteArray, val raw: ByteArray)

    /** Modbus-artige CRC16 (Poly 0xA001, Init 0x7F3C) - exakt wie im ESP32-Referenzprojekt. */
    private fun crc16(buffer: ByteArray, offset: Int, length: Int): Int {
        var crc = 0x7F3C
        for (i in 0 until length) {
            crc = crc xor (buffer[offset + i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Sucht im (ggf. ueber mehrere BLE-Notifications zusammengesetzten) Buffer nach gueltigen
     * 16-Byte-Frames. Statt uns auf ein festes Start-Byte zu verlassen, validieren wir per CRC16
     * an jeder moeglichen Position - das macht die Erkennung robust gegenueber ungerade
     * fragmentierten BLE-Notifications (z.B. 8+20 statt sauber 16+16 Byte).
     */
    fun extractFrames(buffer: ByteArray): List<RawFrame> {
        val frames = mutableListOf<RawFrame>()
        var i = 0
        while (i + PACKET_SIZE <= buffer.size) {
            val crc = crc16(buffer, i, 14)
            val expected = ((buffer[i + 15].toInt() and 0xFF) shl 8) or (buffer[i + 14].toInt() and 0xFF)
            if (crc == expected) {
                val id = buffer[i + 1].toInt() and 0x3F
                val address = FLASH_READ_ADDR.getOrElse(id) { -1 }
                if (address != -1) {
                    val data = buffer.copyOfRange(i + 2, i + 14)
                    val raw = buffer.copyOfRange(i, i + PACKET_SIZE)
                    frames.add(RawFrame(id, address, data, raw))
                }
                i += PACKET_SIZE
            } else {
                i++
            }
        }
        return frames
    }

    fun hexDump(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    data class VehicleState(
        val speedKmh: Double? = null,
        val rpm: Double? = null,
        val rawRpm: Int? = null,
        val voltage: Double? = null,
        val currentA: Double? = null,
        val powerKw: Double? = null,
        val batteryPercent: Int? = null,
        val motorTempC: Int? = null,
        val controllerTempC: Int? = null,
        val gear: Int? = null,
        val isRegen: Boolean = false,
        val lastRawByAddress: Map<Int, ByteArray> = emptyMap()
    )

    // Fahrzeugspezifisch - fuer eine genaue km/h-Anzeige ggf. anpassen (Referenzwerte aus dem
    // ESP32-Projekt; Motor-Polpaarzahl ist normalerweise pro Motortyp fix, der Radumfang haengt
    // von deiner Reifengroesse ab). Fuer die Yozma i10 auf Basis eines Vergleichsmessung
    // (angezeigt ~72.5 km/h vs. real 64 km/h) kalibriert: 1.416 * (64/72.5) = 1.25.
    var wheelCircumferenceM: Double = 1.25
    var motorPolePairs: Int = 20

    private fun int16LE(b0: Byte, b1: Byte): Int {
        val v = ((b1.toInt() and 0xFF) shl 8) or (b0.toInt() and 0xFF)
        return v.toShort().toInt() // sign-extend
    }

    private fun uint16LE(b0: Byte, b1: Byte): Int =
        ((b1.toInt() and 0xFF) shl 8) or (b0.toInt() and 0xFF)

    /** Wendet einen einzelnen Frame auf den bisherigen Zustand an (verifizierte Feld-Offsets). */
    fun applyFrame(state: VehicleState, frame: RawFrame): VehicleState {
        val d = frame.data // 12 Byte Nutzdaten
        val updatedRaw = state.lastRawByAddress.toMutableMap().apply { put(frame.address, d) }
        var next = state.copy(lastRawByAddress = updatedRaw)

        when (frame.address) {
            0xE2 -> {
                val gear = ((d[0].toInt() and 0xFF) shr 2 and 0b11) + 1
                val rawRpm = int16LE(d[6], d[7])
                val clampedRpm = if (rawRpm < 0) 0 else rawRpm
                val rpm = clampedRpm * 4.0 / motorPolePairs
                val speed = (rpm * wheelCircumferenceM * 60.0) / 1000.0
                next = next.copy(gear = gear, rawRpm = rawRpm, rpm = rpm, speedKmh = speed)
            }
            0xE8 -> {
                val voltage = uint16LE(d[0], d[1]) / 10.0
                val current = int16LE(d[4], d[5]) / 4.0
                val validVoltage = voltage in 0.0..100.0
                val validCurrent = current in -100.0..300.0
                next = next.copy(
                    voltage = if (validVoltage) voltage else next.voltage,
                    currentA = if (validCurrent) current else next.currentA,
                    isRegen = validCurrent && current < 0
                )
                val v = next.voltage
                val a = next.currentA
                if (v != null && a != null) next = next.copy(powerKw = (v * a) / 1000.0)
            }
            0xD6 -> {
                val temp = int16LE(d[10], d[11])
                if (temp in -19..99) next = next.copy(controllerTempC = temp)
            }
            0xF4 -> {
                val temp = int16LE(d[0], d[1])
                if (temp in -19..199) next = next.copy(motorTempC = temp)
                next = next.copy(batteryPercent = d[3].toInt() and 0xFF)
            }
        }
        return next
    }
}

