package obd.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight configuration store backed by SharedPreferences.
 * Strictly written ONLY on explicit user preference/pairing actions (never during live telemetry)
 * to preserve Gen5W internal eMMC health.
 */
public final class DevicePrefs {

    private static final String PREFS_NAME = "obd_gen5w_prefs";

    private static final String KEY_OBD_MAC       = "obd_mac";
    private static final String KEY_OBD_NAME      = "obd_name";
    private static final String KEY_OBD_ADDR_TYPE = "obd_addr_type";

    private static final String KEY_PROFILE       = "vehicle_profile";
    private static final String KEY_TEMP_FAHREN   = "unit_temp_f";
    private static final String KEY_DIST_MILES    = "unit_dist_mi";
    private static final String KEY_PRESS_BAR     = "unit_press_bar";

    private static final String KEY_BRIDGE_PORT   = "bridge_tcp_port";

    private static final String KEY_BVM_ENABLED   = "bvm02_enabled";
    private static final String KEY_BVM_MAC_PFX   = "bvm02_mac_";
    private static final String KEY_BVM_NAME_PFX  = "bvm02_name_";
    private static final String KEY_BVM_TYPE_PFX  = "bvm02_type_";

    public static final int BVM_SLOTS = 4;
    public static final int DEFAULT_BRIDGE_PORT = 35000;

    private final SharedPreferences sp;

    public DevicePrefs(Context context) {
        this.sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getObdMac() {
        return sp.getString(KEY_OBD_MAC, "");
    }

    public String getObdName() {
        return sp.getString(KEY_OBD_NAME, "");
    }

    public int getObdAddrType() {
        return sp.getInt(KEY_OBD_ADDR_TYPE, -1);
    }

    public void setObdAdapter(String mac, String name, int addrType) {
        sp.edit()
                .putString(KEY_OBD_MAC, mac == null ? "" : mac.trim())
                .putString(KEY_OBD_NAME, name == null ? "" : name.trim())
                .putInt(KEY_OBD_ADDR_TYPE, addrType)
                .apply();
    }

    public void clearObdAdapter() {
        sp.edit()
                .remove(KEY_OBD_MAC)
                .remove(KEY_OBD_NAME)
                .remove(KEY_OBD_ADDR_TYPE)
                .apply();
    }

    public int getVehicleProfile() {
        return sp.getInt(KEY_PROFILE, EgmpTelemetry.PROFILE_EGMP_192);
    }

    public void setVehicleProfile(int profile) {
        sp.edit().putInt(KEY_PROFILE, profile).apply();
    }

    public boolean isFahrenheit() {
        return sp.getBoolean(KEY_TEMP_FAHREN, false);
    }

    public void setFahrenheit(boolean f) {
        sp.edit().putBoolean(KEY_TEMP_FAHREN, f).apply();
    }

    public boolean isMiles() {
        return sp.getBoolean(KEY_DIST_MILES, false);
    }

    public void setMiles(boolean mi) {
        sp.edit().putBoolean(KEY_DIST_MILES, mi).apply();
    }

    public boolean isBar() {
        return sp.getBoolean(KEY_PRESS_BAR, false);
    }

    public void setBar(boolean bar) {
        sp.edit().putBoolean(KEY_PRESS_BAR, bar).apply();
    }

    public int getBridgePort() {
        int p = sp.getInt(KEY_BRIDGE_PORT, DEFAULT_BRIDGE_PORT);
        return (p < 1024 || p > 65535) ? DEFAULT_BRIDGE_PORT : p;
    }

    public void setBridgePort(int port) {
        if (port >= 1024 && port <= 65535) {
            sp.edit().putInt(KEY_BRIDGE_PORT, port).apply();
        }
    }

    public boolean isBvm02Enabled() {
        return sp.getBoolean(KEY_BVM_ENABLED, false);
    }

    public void setBvm02Enabled(boolean enabled) {
        sp.edit().putBoolean(KEY_BVM_ENABLED, enabled).apply();
    }

    public String getBvmMac(int slot) {
        if (slot < 0 || slot >= BVM_SLOTS) return "";
        return sp.getString(KEY_BVM_MAC_PFX + slot, "");
    }

    public String getBvmName(int slot) {
        if (slot < 0 || slot >= BVM_SLOTS) return "";
        return sp.getString(KEY_BVM_NAME_PFX + slot, "12V Sensor " + (slot + 1));
    }

    public int getBvmAddrType(int slot) {
        if (slot < 0 || slot >= BVM_SLOTS) return -1;
        return sp.getInt(KEY_BVM_TYPE_PFX + slot, -1);
    }

    public void setBvmSensor(int slot, String mac, String name, int addrType) {
        if (slot < 0 || slot >= BVM_SLOTS) return;
        sp.edit()
                .putString(KEY_BVM_MAC_PFX + slot, mac == null ? "" : mac.trim())
                .putString(KEY_BVM_NAME_PFX + slot, name == null ? ("12V Sensor " + (slot + 1)) : name.trim())
                .putInt(KEY_BVM_TYPE_PFX + slot, addrType)
                .apply();
    }

    public void clearBvmSensor(int slot) {
        if (slot < 0 || slot >= BVM_SLOTS) return;
        sp.edit()
                .remove(KEY_BVM_MAC_PFX + slot)
                .remove(KEY_BVM_NAME_PFX + slot)
                .remove(KEY_BVM_TYPE_PFX + slot)
                .apply();
    }
}
