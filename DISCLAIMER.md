# Safety, Warranty & Liability Disclaimer

**READ CAREFULLY BEFORE INSTALLING OR USING `obd-gen5w` IN A VEHICLE.**

1. **Driver Attention & Road Safety**
   - Never interact with pairing menus, diagnostic trouble code (DTC) screens, cell maps, or log exports while the vehicle is in motion.
   - Configure BLE sensors, pack profiles, and localhost bridge settings only while stationary in **Park (`P`)** in a safe location.

2. **OBD-II Bus & 12V Auxiliary Battery Precautions**
   - `obd-gen5w` issues strictly read-only SAE J1979 (`0x01`, `0x03`, `0x07`, `0x0A`) and UDS ReadDataByIdentifier (`0x22`) queries during telemetry polling, plus explicit user-confirmed `Mode 04` DTC clear while stationary in Park.
   - However, when the **Localhost Wi-Fi ELM327 Bridge (`127.0.0.1:35000`)** is enabled and a 3rd-party OBD application connects, raw ELM327 commands from that 3rd-party application are relayed directly to your OBD-II adapter. Only connect trusted diagnostic software configured for your exact vehicle model.
   - Most BLE OBD-II dongles (including the Veepeak OBDCheck BLE) are powered from OBD-II Pin 16 (unswitched `+12V`) and draw ~10–25 mA continuously. Remove the adapter when parking the vehicle unused for extended multi-week periods.

3. **Unofficial Community Software**
   - This project is an independent open-source software package licensed under the GNU Affero General Public License v3.0 (`AGPL-3.0`) and is **not affiliated with, endorsed by, or supported by Hyundai Motor Group, Kia, Genesis, Veepeak, or LNEX**.
   - Provided **"AS IS"**, without warranty of any kind. You assume full responsibility for installation and operation on your infotainment head unit and vehicle.
