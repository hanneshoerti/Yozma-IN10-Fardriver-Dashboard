# Inoffizielles Dashboard fuer Fardriver-Controller

> **Hinweis:** Dies ist ein privates, **inoffizielles** Projekt und steht in keiner Verbindung
> zu Yozma oder Fardriver. Alle Marken- und Produktnamen gehoeren ihren jeweiligen Inhabern.
> Das Protokoll (CRC16, Adresstabelle, Feld-Offsets) basiert auf der Vorarbeit von
> [bobecek79/ESP32-Fardriver-BLE-Reader](https://github.com/bobecek79/ESP32-Fardriver-BLE-Reader).
> Nutzung auf eigene Gefahr - die App liest nur Werte, schreibt aber Keep-Alive-Befehle an den Dongle.

# Fardriver Dash (Android, Kotlin/Compose)

Eigenständiges Android-Dashboard für Fardriver NS-Serie Controller (z.B. NS12) mit
BLE-Bluetooth-Modul, im Stil deines Screenshots (großer Speed-Wert, Power-Balken,
Battery/Voltage/Current/Power-Kacheln, Temperaturen).

## Wichtiger Hinweis

Das Fardriver-BLE-Protokoll ist **nicht offiziell dokumentiert** – alles hier basiert
auf öffentlichem Community-Reverse-Engineering (u.a. github.com/jackhumbert/fardriver-controllers,
github.com/bobecek79/ESP32-Fardriver-BLE-Reader, endless-sphere.com-Forum).

**Sicher/verifiziert:**
- Paket-Framing (16 Byte, Start `0xAA`, Index-Byte, 12 Datenbytes, CRC)
- Index → Adress-Mapping (Lookup-Tabelle)
- RPM-Feld bei Adresse `0xE2`
- Richtung/Gang/Bremse-Bits bei Adresse `0xEE`

**Noch nicht verifiziert (mit `// TODO kalibrieren` markiert in `FardriverProtocol.kt`):**
- Genaue Skalierung von Spannung, Strom, Temperatur, Batterie-%
- RPM → km/h Umrechnungsfaktor (abhängig von deiner Motor-/Rad-Kombination)

Die App hat deshalb einen **Debug-Bereich** unten im Dashboard, der das zuletzt
empfangene Rohpaket als Hex anzeigt (`idx=... addr=0x... data=...`). So gehst du vor:

1. App bauen & auf dem Handy neben der offiziellen Fardriver-App laufen lassen
2. Werte vergleichen (z.B. offizielle App zeigt 72.4 V, Debug-Zeile zeigt `addr=0xE8 data=...`)
3. In `FardriverProtocol.kt` die Bytes/Skalierung in der jeweiligen `when`-Branch anpassen
4. Neu bauen, prüfen, wiederholen

## Projekt öffnen & APK bauen

1. [Android Studio](https://developer.android.com/studio) installieren (kostenlos)
2. "Open" → diesen Ordner (`FardriverDash`) auswählen
3. Warten, bis Gradle synchronisiert (lädt beim ersten Mal Abhängigkeiten aus dem Internet)
4. Menü: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. Fertige APK liegt in `app/build/outputs/apk/debug/app-debug.apk`
6. Aufs Handy kopieren, installieren (ggf. "Unbekannte Quellen" erlauben)

Alternativ per Kommandozeile (falls Android SDK schon installiert ist):

```bash
./gradlew assembleDebug
```

## Nutzung

1. App öffnen → Bluetooth-Berechtigungen erlauben
2. "Scan starten" → dein Gerät sollte als `SM48V70A_81_CZH;30` o.ä. auftauchen
   (Name = ModelType deines Controllers)
3. Gerät antippen → verbindet automatisch und wechselt zum Dashboard
4. Falls "Keine Notify-Characteristic gefunden": über Logcat (Filter `FardriverBle`)
   nachsehen, welche Service-/Characteristic-UUIDs dein Dongle tatsächlich anbietet,
   und diese in `FardriverBleManager.kt` (`FardriverGatt`-Objekt) eintragen. Am
   einfachsten findest du sie vorher mit der App **nRF Connect** heraus.

## Struktur

```
app/src/main/java/com/fardriver/dash/
  MainActivity.kt          Permissions, Navigation Scan ↔ Dashboard
  ble/FardriverBleManager.kt   BLE Scan/Connect/Notify
  ble/FardriverProtocol.kt     Paket-Parsing & Fahrzeugzustand
  ui/ScanScreen.kt          Geräteliste
  ui/DashboardScreen.kt     Dashboard-UI (Speed, Kacheln, Temperaturen)
```

## Nicht enthalten (bewusst)

- Schreiben von Parametern an den Controller (Gang, Rückwärtsgang etc.) – das
  Protokoll dafür ist noch unsicherer, und Fehlschreibungen könnten den Controller
  in einen unsicheren Zustand bringen. Diese App ist reines Read-Only-Monitoring.

## Update: Freischalt-Handshake gefunden (per BLE-HCI-Snoop-Mitschnitt)

Per `adb bugreport` + BLE-HCI-Snoop-Log der **offiziellen** Fardriver-App wurde festgestellt:
Der Dongle liefert erst nach einem bestimmten Schreib-Befehl echte 16-Byte-Telemetrie-Frames
(vorher nur kryptische Status-Pakete). Dieser Befehl ist in `FardriverGatt.UNLOCK_CMD` /
`POLL_CMD` in `FardriverBleManager.kt` hinterlegt und wird automatisch nach Verbindungsaufbau
gesendet (3x Unlock, danach alle 750ms ein Keep-Alive). Alle mitgeschnittenen echten
Telemetrie-Frames wurden per CRC16 verifiziert (655/655 OK) - das Protokoll aus
bobecek79/ESP32-Fardriver-BLE-Reader passt exakt, sobald der Dongle "wach" ist.

Hinweis: Der Unlock-Code ist vermutlich vom Standard-Passwort "000000" des Dongles abgeleitet
und duerfte fuer andere Fardriver/YuanQuFOC-Dongles mit demselben Standardpasswort ebenfalls
funktionieren - eine Garantie dafuer gibt es aber nicht.

## Update: Automatisches Verbinden

Die App merkt sich jetzt die Bluetooth-Adresse des zuletzt genutzten Dongles (lokal auf dem
Handy, per SharedPreferences) und versucht beim Start automatisch, sich direkt wieder damit
zu verbinden - ohne erneuten manuellen Scan. Klappt das nicht (z.B. Fahrzeug ausser Reichweite),
fällt die App nach ca. 8 Sekunden automatisch zurück auf den Scan-Screen. Waehlst du dort ein
(ggf. anderes) Geraet aus, wird das neue Geraet für's naechste Mal gemerkt.
