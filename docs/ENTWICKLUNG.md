# MinerGuru für Android 17 (Material You): Entwicklung

Die öffentliche Beschreibung auf Englisch steht in der [README](../README.md).

**MinerGuru** ist die native Android-Version der macOS-App MinerGuru: Überwachung und Steuerung von Open-Source-Bitcoin-Minern
(**Bitaxe** Gamma/Supra/Ultra mit AxeOS und **NerdQAxe++/+** mit NerdOS) im lokalen Netzwerk. Die Coin-Kurse-Seite der
Mac-App gibt es hier bewusst nicht; Kurse erscheinen nur in den Solo-Chancen-Karten des Dashboards.

**Für Entwickler:** [Architektur der Android-App](ARCHITEKTUR.md). Was alle drei Apps gemeinsam haben, steht im Mac-Repo in [GRUNDLAGEN.md](https://github.com/GuruMeditationSoftware/minerguru/blob/main/docs/GRUNDLAGEN.md).

## Aufbau der App

| Tab | Inhalt |
|---|---|
| **Übersicht** | Flottenstatus, Gesamt-Hashrate/Leistung/Effizienz, Shares, Best Diff, Ø Pool-Latenz, Solo-Mining-Chancen je Coin (Block/Stunde/Tag/Woche/Monat/Jahr) mit Block-Reward in Coins |
| **Miner** | Karten mit Live-Hashrate, Sparkline, Sensoren, Pool, Latenz; Menü: Details, Browser, Neustart, Entfernen; FAB zum Hinzufügen |
| **Pools** | *Verbindungen*: aktiver Pool, Wallet/Worker, Coin, Dual-Pool-Split, Best Diff, Latenz aller Miner. *Konfiguration*: Pool-Bibliothek (aus Minern auslesen, anlegen, duplizieren) und Zuordnung zu den Pool-Slots jedes Miners (AxeOS 2.15+: bis zu 8 Slots mit Primär/Fallback/Reserve; NerdOS: Failover oder Dual-Pool) |
| **Einstellungen** | Sprache, Privatmodus, Abfrage-Intervall, Temperatur-Warnschwelle, Standard-Subnetz, Auto-Scan |

**Privatmodus** (Augen-Symbol oben rechts): IP-, Wallet- und MAC-Adressen, WLAN-Name und Subnetz werden für Screenshots verschleiert; Worker-Namen bleiben lesbar, Eingabefelder werden unscharf.

**Miner-Details** (je Miner, 4 Reiter): *Übersicht* (9 KPIs, Pool-Slots inkl. Dual-Pool, Hardware) · *Verlauf* (Hashrate, ASIC/VR-Temperatur
mit Warnschwelle, Pool-Latenz, Leistung, Difficulty pro Share) · *Live-Log* (WebSocket `/api/ws`, Filter, Suche, Kopieren/Teilen) ·
*Steuerung* (Eco/Standard/Boost-Profile aus den Firmware-Stufen des jeweiligen Chips, Lüfter, Pools inkl. Slots, Bibliothek, Vorlagen,
Dual-Pool, Latenztest, Hostname, Anzeigename, Reboot). Nach einer Pool-Änderung startet der Miner neu, damit sie aktiv wird.

**Miner hinzufügen**: automatischer `/24`-Subnetz-Scan oder manuelle IP/Hostname mit Verbindungstest.

## Technik
- Kotlin + Jetpack Compose, Material 3 mit Dynamic Color (Material You), Edge-to-Edge
- Target Android 17 (API 37), minSdk 31; `ACCESS_LOCAL_NETWORK` wird zur Laufzeit angefragt
- OkHttp für REST (`GET /api/system/info`, `PATCH /api/system`, `POST /api/system/restart`) und WebSocket
- Abfrage nur, solange die App sichtbar ist; Miner-Liste und Einstellungen lokal gespeichert (kein Cloud-Backup)
- Keine Kursabfragen: die Solo-Chance braucht nur Pool-Daten
- Hardware-Erkennung über die ESP-Miner-Boardtabelle bzw. das NerdOS-`deviceModel`; Unit-Tests decken alle 24 Varianten aus `MockMiners/variants` des Mac-Repos ab (Kopie in `app/src/test/resources/variants`)

## Build
```bash
./gradlew assembleDebug testDebugUnitTest   # Debug-APK (…minerguru.dev) + Unit-Tests
./gradlew assembleRelease                   # app/build/outputs/apk/release/app-release.apk
```
