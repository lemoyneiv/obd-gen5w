#!/system/bin/sh
# Open the OBD-II & E-GMP Telemetry app from NEMESIS Manager,
# installing its APK first if needed.
MOD=${ROOT:-}/data/hmgapi/mods/obd-gen5w
say() { echo "OBD-II: $1"; }
[ "$(getprop sys.boot_completed)" = 1 ] || { say "Android isn't up yet: try again in a minute"; exit 1; }
case "$(pm path obd.app 2>/dev/null)" in
    package:*) ;;
    *)
        A=$MOD/app/ObdTelemetry.apk
        [ -f "$A" ] || { say "the app isn't on this unit: install OBD-II again"; exit 1; }
        setprop sys.boot_completed 0
        r=$(pm install -r "$A" 2>&1)
        setprop sys.boot_completed 1
        case "$r" in
            *Success*) say "installed the app" ;;
            *) say "the app didn't install: $r"; exit 1 ;;
        esac ;;
esac
am start -n obd.app/.MainActivity >/dev/null 2>&1 || { say "couldn't open the app"; exit 1; }
