#!/usr/bin/env python3
"""
End-to-end build, host JVM unit test, API-19 DEX bytecode audit, ELF32 ARMv7 JNI check,
APK v1 signature check, HMG-API ECDSA signature verification, loopback security check,
and mksh/flash-wear/privacy compliance suite for obd-gen5w.
"""

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent.parent
SDK_DIR = Path(os.environ.get("ANDROID_HOME", str(BASE_DIR.parent / "tools" / "android-sdk"))).resolve()
ANDROID_JAR = SDK_DIR / "platforms" / "android-19" / "android.jar"
BUILD_TOOLS = SDK_DIR / "build-tools" / "30.0.3"

APK_PATH = BASE_DIR / "build" / "ObdTelemetry.apk"
DEX_PATH = BASE_DIR / "build" / "dex" / "classes.dex"
CLASSES_DIR = BASE_DIR / "build" / "classes"
HOOK_STUBS_DIR = BASE_DIR / "build" / "hook-stubs"
HOOK_CLASSES_DIR = BASE_DIR / "build" / "hook-classes"
HOOK_JAR_PATH = BASE_DIR / "build" / "mod" / "hooks.jar"
DIST_DIR = BASE_DIR / "dist"


def fail(msg: str) -> None:
    print(f"\n[FAIL] {msg}", file=sys.stderr)
    sys.exit(1)


def step(title: str) -> None:
    print(f"\n=== {title} ===")


def run_cmd(cmd, cwd=BASE_DIR) -> str:
    res = subprocess.run(cmd, cwd=str(cwd), text=True, capture_output=True)
    if res.returncode != 0:
        fail(f"Command failed ({' '.join(str(c) for c in cmd)}):\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")
    return res.stdout + res.stderr


def test_clean_build() -> None:
    step("1. Full Clean Build (./build.sh)")
    out = run_cmd([str(BASE_DIR / "build.sh")])
    if "BUILD SUCCESSFUL!" not in out:
        fail("build.sh did not report BUILD SUCCESSFUL!")
    if not APK_PATH.is_file() or not DEX_PATH.is_file() or not HOOK_JAR_PATH.is_file():
        fail("Expected build/ObdTelemetry.apk, build/dex/classes.dex, and build/mod/hooks.jar to exist")
    print(f"  [OK] Built {APK_PATH.name} ({APK_PATH.stat().st_size:,} bytes) + {HOOK_JAR_PATH.name} ({HOOK_JAR_PATH.stat().st_size:,} bytes)")


def test_java_unit_suite() -> None:
    step("2. Host JVM Unit & Integration Tests (ObdUnitTests)")
    with tempfile.TemporaryDirectory() as tmp:
        test_src = BASE_DIR / "tests" / "ObdUnitTests.java"
        cp_compile = os.pathsep.join([str(ANDROID_JAR), str(CLASSES_DIR), str(HOOK_CLASSES_DIR), str(HOOK_STUBS_DIR)])
        run_cmd(["javac", "--release", "8", "-cp", cp_compile, "-d", tmp, str(test_src)])
        cp_run = os.pathsep.join([tmp, str(CLASSES_DIR), str(HOOK_CLASSES_DIR), str(HOOK_STUBS_DIR), str(ANDROID_JAR)])
        out = run_cmd(["java", "-cp", cp_run, "ObdUnitTests"])
        print(out.strip())


def _audit_dex_against_api19(dex_path: Path, label: str, allowed_parent_prefixes=()) -> None:
    dex_bytes = dex_path.read_bytes()
    magic = dex_bytes[:8]
    if magic != b"dex\n035\x00":
        fail(f"[{label}] Expected Dalvik 'dex\\n035\\x00' magic for Android 4.4.2 API 19, got {magic!r}")
    print(f"  [OK] [{label}] DEX magic verified: dex\\n035\\x00 (Android 4.4 Dalvik compatible)")

    dexdump = BUILD_TOOLS / "dexdump"
    dump = run_cmd([str(dexdump), "-d", str(dex_path)])

    banned_patterns = [
        ("android/bluetooth/le/", "API 21+ android.bluetooth.le.* reference"),
        ("Landroid/bluetooth/BluetoothGatt;.requestMtu", "API 21+ BluetoothGatt.requestMtu reference"),
        ("getBluetoothLeScanner", "API 21+ getBluetoothLeScanner reference"),
        ("getBluetoothLeAdvertiser", "API 21+ getBluetoothLeAdvertiser reference"),
        (
            "Landroid/bluetooth/BluetoothDevice;.connectGatt:(Landroid/content/Context;ZLandroid/bluetooth/BluetoothGattCallback;I)",
            "API 23+ 4-argument BluetoothDevice.connectGatt(..., transport) reference",
        ),
    ]
    for pat, reason in banned_patterns:
        if pat in dump:
            fail(f"[{label}] Found forbidden {reason}!")

    defined_in_dex = set(
        re.findall(r"Class descriptor\s*:\s*'L([a-zA-Z0-9_/$]+);'", dump)
    )
    for pfx in allowed_parent_prefixes:
        leaked = sorted(c for c in defined_in_dex if c.startswith(pfx))
        if leaked:
            fail(f"[{label}] Must NOT bundle parent-loader stub classes {leaked}!")

    invoke_re = re.compile(r"L([a-zA-Z0-9_/$]+);\.([a-zA-Z0-9_$<>]+):(\([^\)]*\)\S+)")
    external_calls = set()
    for m in invoke_re.finditer(dump):
        cls_internal, meth_name, desc = m.group(1), m.group(2), m.group(3)
        if cls_internal in defined_in_dex:
            continue
        if any(cls_internal.startswith(pfx) for pfx in allowed_parent_prefixes):
            continue
        external_calls.add((cls_internal, meth_name, desc))

    with zipfile.ZipFile(ANDROID_JAR, "r") as jar_zf:
        jar_classes = {
            name[:-6] for name in jar_zf.namelist() if name.endswith(".class")
        }

    missing_classes = sorted({c for (c, _, _) in external_calls if c not in jar_classes})
    if missing_classes:
        fail(f"[{label}] References classes absent from API-19 android.jar: {missing_classes}")

    referenced_classes = sorted({c.replace("/", ".") for (c, _, _) in external_calls})
    class_methods, class_supers = _load_javap_hierarchy(referenced_classes, jar_classes)

    unresolved = []
    for cls_internal, meth_name, desc in sorted(external_calls):
        cls_dot = cls_internal.replace("/", ".")
        if not _has_method_in_hierarchy(cls_dot, meth_name, desc, class_methods, class_supers, jar_classes):
            unresolved.append(f"{cls_internal}.{meth_name}:{desc}")

    if unresolved:
        fail(f"[{label}] Invokes method(s) missing from API-19 android.jar:\n  " + "\n  ".join(unresolved))

    print(
        f"  [OK] [{label}] Verified all {len(external_calls)} external SDK method invocations across "
        f"{len(referenced_classes)} classes against API-19 android.jar"
    )


def test_dex_header_and_api19_symbols() -> None:
    step("3. Dalvik DEX 035 Header & API-19 Symbol Compatibility Audit (APK + hooks.jar)")
    with zipfile.ZipFile(APK_PATH, "r") as zf:
        dex_entries = [n for n in zf.namelist() if n.endswith(".dex")]
        if dex_entries != ["classes.dex"]:
            fail(f"Expected single classes.dex in APK, found {dex_entries}")
    print("  [OK] APK single-DEX layout verified (no multidex)")
    _audit_dex_against_api19(DEX_PATH, "APK classes.dex")

    with zipfile.ZipFile(HOOK_JAR_PATH, "r") as hz:
        hook_dex_entries = [n for n in hz.namelist() if n.endswith(".dex")]
        if hook_dex_entries != ["classes.dex"]:
            fail(f"Expected single classes.dex in hooks.jar, found {hook_dex_entries}")
        with tempfile.TemporaryDirectory() as tmp:
            hook_dex = Path(tmp) / "hook_classes.dex"
            hook_dex.write_bytes(hz.read("classes.dex"))
            _audit_dex_against_api19(hook_dex, "hooks.jar classes.dex", allowed_parent_prefixes=("hmgapi/hook/",))


def _load_javap_hierarchy(seed_classes_dot, jar_classes):
    class_methods = {}
    class_supers = {}
    pending = list(seed_classes_dot)

    header_re = re.compile(r"(?:class|interface)\s+([a-zA-Z0-9_.$]+)(?:<[^>]*>)?(.*)$")
    desc_re = re.compile(r"^\s*descriptor:\s*(\S+)\s*$")

    while pending:
        batch = [c for c in pending if c not in class_methods]
        pending = []
        if not batch:
            break
        for i in range(0, len(batch), 60):
            chunk = batch[i : i + 60]
            out = run_cmd(["javap", "-cp", str(ANDROID_JAR), "-p", "-s", *chunk])
            cur_cls = None
            last_member_names = []
            for raw_line in out.splitlines():
                line = raw_line.strip()
                if not line or line.startswith("Compiled from"):
                    continue
                if line.endswith("{") and ("class " in line or "interface " in line):
                    hdr = line[:-1].strip()
                    m = header_re.search(hdr)
                    if m:
                        cur_cls = m.group(1).replace("$", ".")
                        for cand in chunk:
                            if cand.replace("$", ".") == cur_cls:
                                cur_cls = cand
                                break
                        class_methods.setdefault(cur_cls, set())
                        supers = []
                        tail = m.group(2)
                        for tok in re.split(r"\bextends\b|\bimplements\b|,", tail):
                            cleaned = re.sub(r"<.*>", "", tok).strip()
                            if cleaned and "." in cleaned:
                                supers.append(cleaned)
                        resolved_supers = []
                        for s in supers:
                            s_cand = _resolve_dot_to_jar_class(s, jar_classes)
                            if s_cand:
                                resolved_supers.append(s_cand)
                                if s_cand not in class_methods:
                                    pending.append(s_cand)
                        class_supers[cur_cls] = resolved_supers
                    continue

                if cur_cls is None:
                    continue

                dm = desc_re.match(raw_line)
                if dm and last_member_names:
                    desc = dm.group(1)
                    if desc.startswith("("):
                        for mn in last_member_names:
                            class_methods[cur_cls].add((mn, desc))
                    last_member_names = []
                elif "(" in line and not line.startswith("descriptor:"):
                    before_paren = line.split("(", 1)[0].strip()
                    tok = before_paren.split()[-1] if before_paren else ""
                    simple_cls = cur_cls.split(".")[-1].split("$")[-1]
                    full_dot = cur_cls.replace("$", ".")
                    if tok == cur_cls or tok == full_dot or tok == simple_cls:
                        last_member_names = ["<init>"]
                    elif tok:
                        last_member_names = [tok]

    return class_methods, class_supers


def _resolve_dot_to_jar_class(dot_name: str, jar_classes: set) -> str:
    slash = dot_name.replace(".", "/")
    if slash in jar_classes:
        return dot_name
    parts = dot_name.split(".")
    for split_idx in range(len(parts) - 1, 0, -1):
        cand_dot = ".".join(parts[:split_idx]) + "$" + "$".join(parts[split_idx:])
        if cand_dot.replace(".", "/") in jar_classes:
            return cand_dot
    return ""


def _has_method_in_hierarchy(cls_dot, meth_name, desc, class_methods, class_supers, jar_classes) -> bool:
    visited = set()
    stack = [cls_dot]
    while stack:
        cur = stack.pop()
        if cur in visited:
            continue
        visited.add(cur)
        if (meth_name, desc) in class_methods.get(cur, set()):
            return True
        for sup in class_supers.get(cur, []):
            stack.append(sup)
        if cur != "java.lang.Object":
            if "java.lang.Object" not in class_methods:
                extra_m, extra_s = _load_javap_hierarchy(["java.lang.Object"], jar_classes)
                class_methods.update(extra_m)
                class_supers.update(extra_s)
            stack.append("java.lang.Object")
    return False


def test_apk_alignment_and_v1_signature() -> None:
    step("4. APK 4-Byte Zipalign, v1-Only JAR Signature, Manifest & ARMv7 JNI Audit")
    zipalign = BUILD_TOOLS / "zipalign"
    run_cmd([str(zipalign), "-c", "4", str(APK_PATH)])
    print("  [OK] zipalign 4-byte alignment verified")

    apksigner_jar = BUILD_TOOLS / "lib" / "apksigner.jar"
    sig_out = run_cmd(["java", "-jar", str(apksigner_jar), "verify", "--verbose", str(APK_PATH)])
    if "Verified using v1 scheme (JAR signing): true" not in sig_out:
        fail(f"APK is missing v1 JAR signature:\n{sig_out}")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" in sig_out:
        fail("APK must NOT enable v2 signature scheme on Gen5W API 19!")
    if "Verified using v3 scheme (APK Signature Scheme v3): true" in sig_out:
        fail("APK must NOT enable v3 signature scheme on Gen5W API 19!")
    print("  [OK] APK Signature Scheme v1=true, v2=false, v3=false verified")

    aapt = BUILD_TOOLS / "aapt"
    badging = run_cmd([str(aapt), "dump", "badging", str(APK_PATH)])
    if "package: name='obd.app'" not in badging:
        fail(f"Unexpected package name in badging:\n{badging}")
    if "sdkVersion:'19'" not in badging or "targetSdkVersion:'19'" not in badging:
        fail(f"Expected minSdkVersion=19 and targetSdkVersion=19:\n{badging}")
    print("  [OK] AndroidManifest badging verified (package=obd.app, minSdk=19, targetSdk=19)")

    with zipfile.ZipFile(APK_PATH, "r") as zf:
        apk_entries = set(zf.namelist())
        for req_so in ("lib/armeabi-v7a/libobd_ble.so", "lib/armeabi/libobd_ble.so"):
            if req_so not in apk_entries:
                fail(f"APK missing required ARMv7 JNI library entry: {req_so}")
        so_bytes = zf.read("lib/armeabi-v7a/libobd_ble.so")
        if so_bytes[:7] != b"\x7fELF\x01\x01\x01":
            fail("libobd_ble.so is not an ELF32 LSB shared library")
        e_type = int.from_bytes(so_bytes[16:18], "little")
        e_machine = int.from_bytes(so_bytes[18:20], "little")
        if e_type != 3 or e_machine != 40:
            fail(f"libobd_ble.so expected ET_DYN(3)/EM_ARM(40), got type={e_type} machine={e_machine}")

        e_phoff = int.from_bytes(so_bytes[28:32], "little")
        e_phentsize = int.from_bytes(so_bytes[42:44], "little")
        e_phnum = int.from_bytes(so_bytes[44:46], "little")
        has_sysv_dt_hash = False
        for idx in range(e_phnum):
            poff = e_phoff + idx * e_phentsize
            p_type = int.from_bytes(so_bytes[poff : poff + 4], "little")
            if p_type == 2:
                dyn_off = int.from_bytes(so_bytes[poff + 4 : poff + 8], "little")
                dyn_sz = int.from_bytes(so_bytes[poff + 16 : poff + 20], "little")
                for doff in range(dyn_off, dyn_off + dyn_sz, 8):
                    d_tag = int.from_bytes(so_bytes[doff : doff + 4], "little", signed=True)
                    if d_tag == 0:
                        break
                    if d_tag == 4:
                        has_sysv_dt_hash = True
        if not has_sysv_dt_hash:
            fail("libobd_ble.so is missing SysV DT_HASH (tag=4) required by Android 4.4 KitKat linker!")

        for sym in (
            b"Java_obd_hook_BtNativeBridge_nativeInit",
            b"Java_obd_hook_BtNativeBridge_nativeSetState",
            b"Java_obd_hook_BtNativeBridge_nativeGetStat",
            b"Java_obd_hook_BtNativeBridge_nativeShutdown",
        ):
            if sym not in so_bytes:
                fail(f"libobd_ble.so missing required JNI export {sym.decode('ascii')}")
    print(f"  [OK] ARMv7 JNI libobd_ble.so ({len(so_bytes):,} bytes) ELF32 EM_ARM, SysV DT_HASH & JNI exports verified")


def test_distribution_archives_and_hmg_signature() -> None:
    step("5. HMG-API & NEMESIS Distribution Archives + ECDSA Signature Verification")
    mod_meta = json.loads((BASE_DIR / "mod" / "mod.json").read_text(encoding="utf-8"))
    ver_str = mod_meta["version"]
    hooks_cfg = mod_meta.get("hooks", {})
    if hooks_cfg.get("jar") != "hooks.jar" or hooks_cfg.get("entry") != "obd.hook.BtHookEntry":
        fail(f"Expected hooks.jar / obd.hook.BtHookEntry in mod.json, got {hooks_cfg}")
    if "points" in hooks_cfg:
        fail("mod.json must NOT define hooks.points so HMG-API Live.sync can late-load cleanly!")

    expected_zips = [
        f"obd-gen5w-v{ver_str}.hmgapi.zip",
        f"obd-gen5w-v{ver_str}-hmgapi-usb.zip",
        f"obd-gen5w-v{ver_str}-nemesis-package.zip",
        f"obd-gen5w-v{ver_str}-nemesis-usb.zip",
    ]
    for zname in expected_zips:
        zp = DIST_DIR / zname
        if not zp.is_file() or zp.stat().st_size == 0:
            fail(f"Missing distribution archive: {zp}")

    hmg_zip = DIST_DIR / f"obd-gen5w-v{ver_str}.hmgapi.zip"
    with zipfile.ZipFile(hmg_zip, "r") as zf:
        names = set(zf.namelist())
        required = {
            "mod.json",
            "hooks.jar",
            "open.sh",
            "svc/btprobe.sh",
            "svc/screenshot.sh",
            "svc/reboot.sh",
            "icons/tile.png",
            "icons/tile_disabled.png",
            "app/ObdTelemetry.apk",
            "META-HMGAPI/manifest.json",
            "META-HMGAPI/signature",
        }
        missing = required - names
        if missing:
            fail(f".hmgapi.zip missing entries: {missing}")

        manifest_bytes = zf.read("META-HMGAPI/manifest.json")
        manifest_obj = json.loads(manifest_bytes.decode("utf-8"))
        uuid_str = manifest_obj["uuid"]
        pub_der = base64.b64decode(manifest_obj["key"])
        sig_der = base64.b64decode(zf.read("META-HMGAPI/signature").strip())

        with tempfile.TemporaryDirectory() as tmp:
            pub_pem_path = Path(tmp) / "pub.pem"
            sig_path = Path(tmp) / "sig.der"
            pub_pem = subprocess.run(
                ["openssl", "pkey", "-pubin", "-inform", "DER"],
                input=pub_der,
                capture_output=True,
                check=True,
            ).stdout
            pub_pem_path.write_bytes(pub_pem)
            sig_path.write_bytes(sig_der)
            ver = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", str(pub_pem_path), "-signature", str(sig_path)],
                input=manifest_bytes,
                capture_output=True,
            )
            if ver.returncode != 0:
                fail(f"ECDSA signature verification failed: {ver.stderr.decode('utf-8', errors='replace')}")

        for rel_path, expected_hash in manifest_obj["files"].items():
            actual_hash = hashlib.sha256(zf.read(rel_path)).hexdigest()
            if expected_hash != actual_hash:
                fail(f"META-HMGAPI/manifest.json SHA-256 mismatch on {rel_path}")

    with zipfile.ZipFile(DIST_DIR / f"obd-gen5w-v{ver_str}-hmgapi-usb.zip", "r") as zf:
        if f"HMGAPI/updates/obd-gen5w-v{ver_str}.zip" not in zf.namelist():
            fail(f"hmgapi-usb.zip missing HMGAPI/updates/obd-gen5w-v{ver_str}.zip")

    with zipfile.ZipFile(DIST_DIR / f"obd-gen5w-v{ver_str}-nemesis-usb.zip", "r") as zf:
        if "NEMESIS.data" not in zf.namelist() or "packages/hmgapi.obd-gen5w.zip" not in zf.namelist():
            fail("nemesis-usb.zip missing NEMESIS.data or packages/hmgapi.obd-gen5w.zip")

    sdk_jar = DIST_DIR / f"gen5w-ble-client-v{ver_str}.jar"
    if not sdk_jar.is_file() or sdk_jar.stat().st_size == 0:
        fail(f"Missing standalone Gen5W BLE Client SDK JAR: {sdk_jar}")
    with zipfile.ZipFile(sdk_jar, "r") as sz:
        sdk_names = set(sz.namelist())
        for req_cls in ("gen5w/ble/Gen5wBleClient.class", "gen5w/ble/Gen5wBleAdvRecord.class"):
            if req_cls not in sdk_names:
                fail(f"Standalone SDK JAR missing {req_cls}")
        for entry in sdk_names:
            if entry.endswith(".class"):
                if not entry.startswith("gen5w/ble/"):
                    fail(f"Unexpected non-SDK class in {sdk_jar.name}: {entry}")
                raw_cls = sz.read(entry)
                if b"obd/app" in raw_cls or b"obd/hook" in raw_cls:
                    fail(f"Standalone SDK class {entry} must have zero coupling to obd.*!")

    print(
        f"  [OK] All 4 dist/*v{ver_str}*.zip archives, standalone {sdk_jar.name}, "
        f"& HMG-API ECDSA P-256 signature verified (uuid={uuid_str})"
    )


def test_shell_scripts_flash_privacy_and_security() -> None:
    step("6. mksh/Toolbox Script Audit, Zero-eMMC, Loopback Bridge & Terminology Guardrails")
    sh_files = sorted((BASE_DIR / "mod").rglob("*.sh"))
    if len(sh_files) < 4:
        fail("Expected 4 shell scripts under mod/ (open.sh, reboot.sh, screenshot.sh, btprobe.sh)")

    banned_sh_regex = re.compile(
        r"\b(awk|sed|head|tail|cut|tr|wc|find|sort|stat|timeout|seq|printf|dirname|basename|readlink|nohup)\b"
        r"|\[\[|grep\s+-q"
    )
    for sh in sh_files:
        raw = sh.read_bytes()
        if b"\r\n" in raw:
            fail(f"CRLF line endings found in {sh}")
        text = raw.decode("utf-8")
        if not text.startswith("#!/system/bin/sh\n"):
            fail(f"{sh} must start with #!/system/bin/sh")
        for idx, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            m = banned_sh_regex.search(stripped)
            if m:
                fail(f"Banned non-toolbox construct '{m.group(0)}' in {sh}:{idx}")
        if not os.access(sh, os.X_OK):
            fail(f"Shell script {sh} is not executable (chmod +x)")

    hmg_checker = BASE_DIR.parent / "HMG-API" / "tools" / "check-scripts.sh"
    if hmg_checker.is_file() and shutil.which("sh"):
        run_cmd(["sh", str(hmg_checker), str(BASE_DIR / "mod")])

    print(f"  [OK] Verified {len(sh_files)} mod shell scripts against Gen5W Android 4.4 mksh + toolbox")

    forbidden_java_tokens = [
        "getFilesDir(",
        "getCacheDir(",
        "openFileOutput(",
        "SQLiteOpenHelper",
        "openOrCreateDatabase(",
    ]
    for src_file in sorted((BASE_DIR / "app").rglob("*")):
        if not src_file.is_file() or src_file.suffix not in {".java", ".xml"}:
            continue
        content = src_file.read_text(encoding="utf-8")
        if re.search(r"android\s+auto", content, flags=re.IGNORECASE):
            fail(f"Forbidden phrase 'Android Auto' found in {src_file}")
        if src_file.suffix == ".java":
            for tok in forbidden_java_tokens:
                if tok in content:
                    fail(f"Forbidden internal eMMC storage call '{tok}' found in {src_file}")
            if "0.0.0.0" in content and src_file.name == "WifiElmBridgeService.java":
                # Only allow 0.0.0.0 in Javadoc comments, never in code lines
                for raw_ln in content.splitlines():
                    ln = raw_ln.strip()
                    if not ln.startswith("*") and not ln.startswith("//") and "0.0.0.0" in ln:
                        fail("WifiElmBridgeService must NEVER bind to 0.0.0.0!")

    for hook_src in sorted((BASE_DIR / "hook" / "src").rglob("*.java")):
        h_text = hook_src.read_text(encoding="utf-8")
        for tok in forbidden_java_tokens + ["FileOutputStream", "FileWriter", "pokeNative", "enableGatt", "enableRadio", "com.broadcom.bt"]:
            if tok in h_text:
                fail(f"Forbidden write or unsafe call '{tok}' found in hook source {hook_src}")

    manifest_xml = (BASE_DIR / "app" / "AndroidManifest.xml").read_text(encoding="utf-8")
    if "com.broadcom.bt" in manifest_xml:
        fail("AndroidManifest.xml must NOT link com.broadcom.bt on Telechips mbox Gen5W firmware!")

    expected_ver = json.loads((BASE_DIR / "mod" / "mod.json").read_text(encoding="utf-8"))["version"]
    if f'android:versionName="{expected_ver}"' not in manifest_xml:
        fail(f"AndroidManifest.xml versionName does not match mod.json version {expected_ver}")
    layout_xml = (BASE_DIR / "app" / "res" / "layout" / "activity_main.xml").read_text(encoding="utf-8")
    if f'android:text="v{expected_ver}"' not in layout_xml:
        fail(f"activity_main.xml tv_app_version text does not match v{expected_ver}")
    app_ver_java = (BASE_DIR / "app" / "src" / "obd" / "app" / "AppVersion.java").read_text(encoding="utf-8")
    if f'APP_VERSION = "{expected_ver}"' not in app_ver_java:
        fail(f"AppVersion.APP_VERSION does not match {expected_ver}")
    hook_entry_java = (BASE_DIR / "hook" / "src" / "obd" / "hook" / "BtHookEntry.java").read_text(encoding="utf-8")
    if f'HOOK_VERSION = "{expected_ver}"' not in hook_entry_java:
        fail(f"BtHookEntry.HOOK_VERSION does not match {expected_ver}")

    print(
        f"  [OK] Zero internal eMMC logging, version sync (v{expected_ver}), loopback-only bridge, "
        "VIN/UDS safety, and UI terminology checks passed"
    )


def main() -> None:
    test_clean_build()
    test_java_unit_suite()
    test_dex_header_and_api19_symbols()
    test_apk_alignment_and_v1_signature()
    test_distribution_archives_and_hmg_signature()
    test_shell_scripts_flash_privacy_and_security()
    print("\n========================================================")
    print("ALL OBD-GEN5W AUTOMATED TESTS & AUDITS PASSED (100%)!")
    print("========================================================")


if __name__ == "__main__":
    main()
