# MinerGuru for Android 17 (Material You): development

The [README](../README.md) describes the app for users.

**MinerGuru** is the native Android version of the macOS app MinerGuru: monitoring and controlling open-source Bitcoin miners
(**Bitaxe** Gamma/Supra/Ultra with AxeOS and **NerdQAxe++/+** with NerdOS) on the local network. The coin prices page of the
Mac app is left out on purpose; prices only appear in the solo odds cards of the dashboard.

**For developers:** [architecture of the Android app](ARCHITECTURE.md). What all three apps have in common is in the Mac repository in [FUNDAMENTALS.md](https://github.com/GuruMeditationSoftware/minerguru/blob/main/docs/FUNDAMENTALS.md).

## How the app is organized

| Tab | Contents |
|---|---|
| **Overview** | Fleet status, total hashrate/power/efficiency, shares, best diff, average pool latency, solo mining odds per coin (block/hour/day/week/month/year) with the block reward in coins |
| **Miners** | Cards with live hashrate, sparkline, sensors, pool, latency; menu: details, browser, restart, remove; FAB to add a miner |
| **Pools** | *Connections*: active pool, wallet/worker, coin, dual-pool split, best diff, latency of all miners. *Configuration*: pool library (read from miners, create, duplicate) and assignment to the pool slots of each miner (AxeOS 2.15+: up to 8 slots with primary/fallback/reserve; NerdOS: failover or dual pool) |
| **Settings** | Language, private mode, poll interval, temperature warning threshold, default subnet, auto scan, background monitoring |

Power, maintenance and reports are further areas, as in the Mac app.

**Private mode** (eye symbol at the top right): IP, wallet and MAC addresses, the Wi-Fi name and the subnet are obscured for screenshots; worker names stay readable, input fields are blurred.

**Miner details** (per miner, 4 tabs): *Overview* (9 KPIs, pool slots including dual pool, hardware) · *History* (hashrate, ASIC/VR temperature
with warning threshold, pool latency, power, difficulty per share) · *Live log* (WebSocket `/api/ws`, filter, search, copy/share) ·
*Control* (Eco/Standard/Boost profiles from the firmware steps of the chip, fan, pools including slots, library, templates,
dual pool, latency test, hostname, display name, restart). After a pool change the miner restarts so the change takes effect.

**Add miner**: automatic `/24` subnet scan, or a manual IP/hostname with a connection test.

## Technology
- Kotlin and Jetpack Compose, Material 3 with dynamic color (Material You), edge-to-edge
- Target Android 17 (API 37), minSdk 31; `ACCESS_LOCAL_NETWORK` is requested at runtime
- OkHttp for REST (`GET /api/system/info`, `PATCH /api/system`, `POST /api/system/restart`) and WebSocket
- Polls at the set interval while the app is visible; optional background monitoring, see [ARCHITECTURE.md](ARCHITECTURE.md#background-monitoring). Miner list and settings are stored locally (no cloud backup)
- No price queries: the solo odds only need pool data
- Hardware detection through the ESP-Miner board table or the NerdOS `deviceModel`; unit tests cover all 24 variants from `MockMiners/variants` in the Mac repository (copy in `app/src/test/resources/variants`)

## Build
```bash
./gradlew assembleDebug testDebugUnitTest   # debug APK (…minerguru.dev) and unit tests
./gradlew assembleRelease                   # app/build/outputs/apk/release/app-release.apk
```
