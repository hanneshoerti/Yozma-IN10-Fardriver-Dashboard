# Yozma IN10 · Fardriver Dashboard (Android)

Ein schlankes Android-Dashboard für E-Motorräder mit **Fardriver-NS-Controller** (z. B. NS12) und
Bluetooth-Dongle. Die App verbindet sich per Bluetooth Low Energy mit dem Controller und zeigt die
wichtigsten Fahrdaten live an. Entwickelt und getestet an einer **Yozma IN10** (48 V, 2,6 kW,
Controller `SM48V70A_81_CZH;30`).

> **Inoffizielles Projekt.** Es besteht keine Verbindung zu Yozma oder Nanjing Fardriver. Alle Marken-
> und Produktnamen gehören ihren jeweiligen Inhabern. Nutzung auf eigene Gefahr.

## Funktionen

- **Geschwindigkeit** groß in der Mitte, darunter ein Leistungsbalken (0 bis 2,6 kW)
- **Akku** in %, **Spannung**, **Strom** und **Leistung**
- **Motor- und Controller-Temperatur**
- **Automatisches Verbinden** mit dem zuletzt genutzten Controller beim App-Start
- **Kalibrierung** der Geschwindigkeit über Polpaare und Radumfang
- Ausblendbarer **Debug-Bereich** mit Rohdaten, falls etwas nicht stimmt
- Rein lesend: Die App ändert keine Einstellungen am Controller

## Installation

1. Unter [**Releases**](../../releases) die neueste Datei `Yozma-Dashboard-….apk` aufs Handy laden.
2. Antippen und installieren. Android fragt einmalig, ob der Browser bzw. Dateimanager Apps
   installieren darf ("Unbekannte Apps installieren"). Das musst du erlauben.
3. Voraussetzung: Android 8.0 oder neuer mit Bluetooth LE.

**Update von einer älteren Version:** Die APK ist mit einem Entwicklungsschlüssel signiert, der sich
von Build zu Build ändern kann. Meldet Android "App nicht installiert", die alte Version zuerst
deinstallieren und dann die neue installieren. Die Kalibrierung musst du danach neu eintragen.

## Erste Verbindung

1. **Bluetooth am Handy einschalten** und die Zündung am Motorrad einschalten.
2. App öffnen und die Bluetooth-Berechtigung erlauben.
3. **Scan starten** und den Controller antippen. Er erscheint unter seinem Modellnamen, bei der
   Yozma IN10 als `SM48V70A_81_CZH;30`.
4. Nach ein bis zwei Sekunden erscheint das Dashboard mit Live-Werten.

Ab dann verbindet sich die App beim Start automatisch mit diesem Controller. Ist das Motorrad nicht
in Reichweite, kehrt sie nach etwa 8 Sekunden zur Geräteliste zurück. Das Verbindungs-Abzeichen oben
links trennt die Verbindung und öffnet ebenfalls die Geräteliste.

> Die Original-Fardriver-App und dieses Dashboard können nicht gleichzeitig verbunden sein. Schließe
> die eine App, bevor du die andere verbindest.

## Geschwindigkeit kalibrieren

Der Controller liefert nur die Motordrehzahl. Daraus berechnet die App die Geschwindigkeit:

```
km/h = Rohwert × 4 / Polpaare × Radumfang (m) × 60 / 1000
```

Voreingestellt sind **20 Polpaare** und **1,25 m Radumfang**. Damit stimmt die Anzeige bei der
Yozma IN10 mit den gemessenen 64 km/h Höchstgeschwindigkeit überein.

Für ein anderes Fahrzeug oder andere Reifen: Zahnrad oben rechts antippen und den Radumfang anpassen.
Anzeige und Radumfang verhalten sich proportional. Zeigt die App 10 % zu viel an, den Radumfang um
10 % verringern. Die Einstellungen bleiben nach einem Neustart erhalten.

## Fehlersuche

| Problem | Lösung |
| --- | --- |
| Scan findet keine Geräte | Bluetooth am Handy ist aus, oder (bei älteren Android-Versionen) der Standortdienst. Beides einschalten. |
| Controller erscheint nicht | Zündung an? Original-App geschlossen? Abstand zum Motorrad verringern. |
| Verbunden, aber nur "--" | Unten im Dashboard "Debug-Infos anzeigen" öffnen. Kommen keine Rohdaten an, App schließen und neu verbinden. |
| Geschwindigkeit falsch | Siehe [Geschwindigkeit kalibrieren](#geschwindigkeit-kalibrieren). |

Mit **Debug-Log kopieren** landen Rohdaten, Bluetooth-Ereignisse und die gefundenen Services in der
Zwischenablage. Das ist hilfreich, wenn du ein Problem als Issue meldest.

## So funktioniert es

Der Bluetooth-Dongle des Controllers bietet den Service `0xFFE0` mit der Characteristic `0xFFEC`
(Lesen, Schreiben, Benachrichtigen). Die Kommunikation läuft in drei Schritten:

1. **Benachrichtigungen aktivieren** auf `0xFFEC`.
2. **Freischalten:** Ohne diesen Schritt sendet der Dongle nur Statuspakete ohne Fahrdaten. Die App
   schreibt deshalb dreimal `AA 13 EC 07 5F 5F 6E 91` und danach alle 750 ms den Keep-Alive-Befehl
   `AA 13 EC 07 09 6F 28 D7`. Beide Befehle stammen aus einem Bluetooth-Mitschnitt der offiziellen
   Fardriver-App mit dem Standardpasswort des Dongles.
3. **Telemetrie lesen:** Der Controller sendet reihum 16-Byte-Pakete:

```
AA | 0x80 + Index | 12 Byte Daten | CRC16 (Low, High)
```

Die CRC ist eine Modbus-artige CRC16 (Polynom `0xA001`, Startwert `0x7F3C`) über die ersten 14 Byte.
Der Index (untere 6 Bit) wird über eine feste Tabelle auf eine Speicheradresse abgebildet. Ausgewertet
werden diese Adressen (Werte Little-Endian):

| Adresse | Bytes | Wert |
| --- | --- | --- |
| `0xE2` | 6–7 | Drehzahl-Rohwert (vorzeichenbehaftet) |
| `0xE8` | 0–1 | Spannung ÷ 10 (V) |
| `0xE8` | 4–5 | Strom ÷ 4 (A), negativ bei Rekuperation |
| `0xD6` | 10–11 | Controller-Temperatur (°C) |
| `0xF4` | 0–1 | Motor-Temperatur (°C) |
| `0xF4` | 3 | Akkustand (%) |

## Selbst bauen

Mit [Android Studio](https://developer.android.com/studio) den Ordner öffnen und
**Build → Build APK(s)** wählen. Meldet Android Studio einen JDK-Fehler, im Fehlerfenster
"Apply compatible Gradle JDK configuration and sync" anklicken. Das Projekt braucht JDK 17
und Gradle 8.7.

Bei jedem Tag `v*` (z. B. `v0.1.0`) baut GitHub Actions die APK automatisch und hängt sie an ein
Release. Den Workflow findest du unter `.github/workflows/build-apk.yml`.

```
app/src/main/java/com/fardriver/dash/
├── MainActivity.kt              Berechtigungen, Navigation, Auto-Connect
├── ble/
│   ├── FardriverBleManager.kt   Scan, Verbindung, Freischalten, Keep-Alive
│   ├── FardriverProtocol.kt     CRC-Prüfung und Dekodierung der Pakete
│   ├── LastDeviceStore.kt       Merkt sich den letzten Controller
│   └── SettingsStore.kt         Speichert die Kalibrierung
└── ui/
    ├── DashboardScreen.kt       Dashboard
    ├── ScanScreen.kt            Geräteliste
    └── SettingsScreen.kt        Kalibrierung
```

## Danksagung

Das Telemetrie-Protokoll (CRC16, Adresstabelle, Feldpositionen) basiert auf der Vorarbeit von
[bobecek79/ESP32-Fardriver-BLE-Reader](https://github.com/bobecek79/ESP32-Fardriver-BLE-Reader)
sowie den Analysen in
[jackhumbert/fardriver-controllers](https://github.com/jackhumbert/fardriver-controllers) und im
Forum [Endless Sphere](https://endless-sphere.com/sphere/threads/fardriver-controller-serial-protocol-reverse-engineering.121825/).
