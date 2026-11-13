# OBD-II & E-GMP EV Telemetry for Gen5W (`obd-gen5w`)

Native widescreen Bluetooth Low Energy (BLE) **OBD-II & Hyundai/Kia E-GMP EV telemetry dashboard**, **localhost Wi-Fi ELM327 TCP bridge (`127.0.0.1:35000`)**, and **optional concurrent LNEX BVM02 12V battery monitor** engineered for **Hyundai / Kia / Genesis Standard-Class Gen5W** infotainment head units (`Android 4.4.2 KitKat`, API 19, ARMv7).

Built directly on the general-purpose `@gen5w_ble` BLE Central & GATT runtime in `com.android.bluetooth`, `obd-gen5w` pairs directly with BLE OBD-II scanners — tested and optimized around the **Veepeak OBDCheck BLE** (`Service 0xFFF0`: `0xFFF1` Notify / `0xFFF2` Write) as well as ISSC/Microchip and Nordic UART BLE ELM327 adapters.

---

## Key Capabilities

1. **Hyundai Ioniq 5 / Kia EV6 / Genesis GV60 (E-GMP) Live Cockpit (`EV Live`)**
   - Real-time HV Pack Power (`kW`, color-coded draw vs regen/charge), Pack Bus Voltage (`~550–800 V`), Pack Current (`A`), and Available Charge/Discharge limits (`kW`).
   - Dual State-of-Charge readout (**Display SoC %** vs **BMS Raw SoC %**) and Remaining Energy (`kWh`).
   - Min/Max Battery Module Temperatures, Inlet & Coolant Loop Temperatures, Inverter Capacitor Voltage, HV Isolation Resistance (`kΩ`), Drive Motor 1 & 2 RPM, Gear, and Speed.
   - Dual-trace 180-sample rolling chart (`TelemetryGraphView`) for HV Power (`kW`) and Pack Voltage (`V`).
   - **Optional HMG-API Battery Preconditioning Control**: reads live PTC heater & thermal loop status and exposes `Start Heat`, `Warm Pack`, and `Stop` actuation via `@hmgapid` (`vehicle-do conditioning`) when HMG-API vehicle control is active on the unit (automatically hidden when unavailable).

2. **192 / 180 / 144-Cell HV Balance Map & Lifetime BMS Health (`Cells & Thermal`)**
   - Live Min/Max cell voltage, cell index (`#001`–`#192`), millivolt delta (`dV mV`), and BMS **State of Health (`SOH %`)**.
   - On-demand full cell sweep (`220102`..`22010C`) rendered in an allocation-free 2-bank bar/heatmap (`CellHeatmapView`) supporting **77.4 / 84 kWh (192c)**, **72.6 kWh (180c)**, **58 / 63 kWh (144c)**, and **Generic SAE J1979 Mode 01** profiles.
   - Individual module temperatures (`M01`–`M16`) and lifetime cumulative energy/capacity counters (`Charged kWh / Ah`, `Discharged kWh / Ah`, BMS operating hours, cluster odometer).

3. **ICCU LDC 12V Health, 4-Corner TPMS & Optional Concurrent BVM02 (`12V / ICCU & BVM`)**
   - Live **ICCU Low-Voltage DC-DC Converter (`7E5`/`7ED`)** output voltage (`V`), output current (`A`), LDC temperature, HV input voltage, and **Aux 12V Battery Sensor** voltage, current, and SoC `%`.
   - **4-Corner TPMS (`7A0`/`7A8`)** tire pressure (`PSI` or `bar`) and tire temperature (`°C` or `°F`).
   - **Concurrent LNEX BVM02 12V Battery Monitor (`MULTICONN 1`)**: optional toggle allowing users who also run up to 4 LNEX BVM02 direct-terminal 12V BLE sensors to monitor them simultaneously alongside the Veepeak OBDCheck BLE scanner.

4. **Multi-ECU DTC Reader & Bonus Localhost Wi-Fi ELM327 Bridge (`DTC & Bridge`)**
   - Scans **Stored (`Mode 03`)**, **Pending (`Mode 07`)**, and **Permanent (`Mode 0A`)** trouble codes across responding E-GMP ECUs (`BMS`, `ICCU`, `VCU/EPCU`, `MCU`, `ABS/ESC`, `TPMS`, `Cluster`) with parked-only `Mode 04` clear confirmation.
   - **Opt-In Background Wi-Fi ELM327 Bridge (`WifiElmBridgeService`)**: binds strictly to loopback **`127.0.0.1:35000`** (`35000` / `35001` / `23000`) so 3rd-party Android OBD apps installed on the head unit (such as *Car Scanner ELM OBD2* or *Torque Pro*) can connect via `Wi-Fi -> 127.0.0.1:35000` while wireless projection or cabin Wi-Fi stays uninterrupted. Automatically pauses internal dashboard polling while a TCP client is connected and resumes when the client disconnects.

5. **Zero Internal eMMC Wear & Privacy-First Telemetry**
   - Zero continuous writes to `/data` — all diagnostic logs, E-GMP HV CSVs, BVM02 12V CSVs, and screenshots live in bounded RAM ring buffers and export atomically only to removable USB (`<usb>/OBDLogs/`).
   - Zero VIN collection (`0902` and `22F190` are blocked at the protocol layer) and strictly read-only UDS `0x22` / SAE J1979 `0x01`–`0x0A` queries.

---

## Installation Packages (`dist/`)

Run `./build.sh` to build and sign all four installation archives in `dist/`:

| Package | Target Environment |
|---|---|
| `obd-gen5w-v0.1.4.hmgapi.zip` | Signed HMG-API `.mod` package (`mod.json` + `mod.sig`) |
| `obd-gen5w-v0.1.4-hmgapi-usb.zip` | USB root auto-installer (when HMG-API is already installed) |
| `obd-gen5w-v0.1.4-nemesis-package.zip` | NEMESIS Mod Manager package |
| `obd-gen5w-v0.1.4-nemesis-usb.zip` | Complete USB bundle including HMG-API runtime + `obd-gen5w` |

> **Coexistence Note:** If `bvm02-gen5w` is also installed on the same head unit, both mods detect an active `@gen5w_ble` socket in `com.android.bluetooth` and share the single BLE bridge cleanly without double-hooking `bplus.default.so`.

---

## Quick Start with Veepeak OBDCheck BLE

1. Plug the **Veepeak OBDCheck BLE** into your Ioniq 5 / E-GMP OBD-II port and put the vehicle in **READY / IG ON**.
2. Launch **OBD-II** from the Gen5W launcher and open the **`BLE & Logs`** tab.
3. Tap **`Scan BLE`**, verify **`Target: OBD-II`**, and tap **`OBDCheck BLE`** (or **`VEEPEAK`**) in the discovered list.
4. Switch to **`EV Live`**, **`Cells & Thermal`**, or **`12V / ICCU & BVM`** — live telemetry begins automatically within 1–2 seconds.
5. *(Optional Bridge)* To use *Car Scanner ELM OBD2* or *Torque Pro* on the head unit, open **`DTC & Bridge`**, tap **`Start Background Bridge (:35000)`**, and configure your 3rd-party app for **Wi-Fi (`127.0.0.1`, port `35000`)**.
6. *(12V Standby Tip)* The Veepeak OBDCheck BLE draws ~10–15 mA quiescent current from the always-live OBD-II Pin 16 (`+12V`). `obd-gen5w` automatically enters 5-second low-power standby when CAN goes to sleep (`IG OFF`), and unplugging the dongle during multi-week airport parking is recommended.

---

## Building & Running Verification Tests

```bash
./build.sh
python3 tests/run_tests.py
```

See [DISCLAIMER.md](DISCLAIMER.md) for safety terms and [GEN5W_BLE_LIBRARY.md](GEN5W_BLE_LIBRARY.md) for the `@gen5w_ble` IPC protocol specification. Licensed under [AGPL-3.0](LICENSE).
