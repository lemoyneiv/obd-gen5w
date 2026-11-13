# Agent & Maintainer Engineering Guide (`obd-gen5w`)

Critical platform invariants and architectural guardrails for maintaining `obd-gen5w` on Hyundai/Kia/Genesis **Standard-Class Gen5W** (`Android 4.4.2 KitKat`, API 19, ARMv7 Dalvik).

## 1. Non-Negotiable Platform Constraints

1. **API 19 Dalvik Bytecode & Signing**
   - `minSdkVersion="19"` and `targetSdkVersion="19"`.
   - `ObdTelemetry.apk` (`classes.dex`) and `hooks.jar` (`classes.dex`) MUST emit single-DEX `dex\n035\x00` magic (`--min-api 19`) with zero post-API-19 framework references.
   - APK signing MUST use **v1 JAR signing only** (`--v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false`) after 4-byte `zipalign`.
   - Native JNI (`libobd_ble.so`) MUST compile for 32-bit `armv7a-linux-androideabi` (`softfp`, Thumb-2) with `-Wl,--hash-style=sysv` (`DT_HASH` required by KitKat `/system/bin/linker`).

2. **Bluetooth Process Isolation & Mod Coexistence**
   - Never add `<uses-library android:name="com.broadcom.bt"/>` to `app/AndroidManifest.xml`.
   - `BtHookEntry` runs exclusively inside `com.android.bluetooth`. Before binding `@gen5w_ble`, `BtBleBridgeServer.isPeerBridgeAlive("gen5w_ble")` checks whether another installed mod (such as `bvm02-gen5w`) is already serving `@gen5w_ble` and yields cleanly so `bplus.default.so` is never hooked twice.

3. **Zero Internal eMMC Wear**
   - Never call `getFilesDir()`, `getCacheDir()`, `openFileOutput()`, or `SQLiteDatabase` in `app/` or `hook/`.
   - Persist user pairings/preferences strictly on explicit user edits via `DevicePrefs`, and write logs, CSVs, and screenshots exclusively to removable USB (`<usb>/OBDLogs/`) via atomic `.part` + `sync()` + `renameTo()`.

4. **Vehicle CAN Safety, Loopback Security & Privacy**
   - Never query, parse, log, or export raw VINs (`0902` / `22F190` blocked in `Elm327Protocol.isSafeInternalCommand`).
   - Never write to LNEX BVM02 characteristic `0xFFF1` (`PROTECT_CHAR` enforced). Furthermore, `Elm327Protocol.resolveAdapterBinding` inspects `GATT_READY` properties and rejects any peripheral exposing `0xFFF4` without `0xFFF1` `NOTIFY`, preventing accidental ELM327 writes to a BVM02 sensor.
   - `WifiElmBridgeService` MUST bind strictly to `127.0.0.1` (never `0.0.0.0`).
   - Use neutral `"wireless projection"` phrasing in app code/UI (never `"Android Auto"`).
   - Shell scripts under `mod/` MUST pass `HMG-API/tools/check-scripts.sh` (`#!/system/bin/sh`, `mksh` + Android 4.4 `toolbox` only).
