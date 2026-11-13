package obd.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure Java API-19 client for HMG-API's {@code @hmgapid} abstract socket daemon (LFD1 framing).
 *
 * Supports:
 * - Root mod services ({@code svc-start obd-gen5w screenshot|btprobe|reboot})
 * - Live vehicle signal reads ({@code vehicle gear.park hv.ready hv.conditioning ...})
 * - Optional E-GMP Battery Preconditioning actuation ({@code vehicle-do conditioning start|stop|status [warm]}),
 *   failing gracefully when HMG-API vehicle control is unavailable on the unit.
 */
public final class HmgapidClient {

    public static final String MOD_ID = "obd-gen5w";
    private static final String SOCKET_NAME = "hmgapid";
    private static final byte[] MAGIC_LFD1 = { 'L', 'F', 'D', '1' };
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HmgapidClient() {}

    public static final class Reply {
        public final int code;
        public final String text;

        public Reply(int code, String text) {
            this.code = code;
            this.text = text == null ? "" : text;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public static final class ConditioningStatus {
        public final boolean supported;
        public final boolean active;
        public final boolean parked;
        public final boolean hvReady;
        public final String stateLabel;
        public final String detail;

        public ConditioningStatus(boolean supported, boolean active, boolean parked,
                                  boolean hvReady, String stateLabel, String detail) {
            this.supported = supported;
            this.active = active;
            this.parked = parked;
            this.hvReady = hvReady;
            this.stateLabel = stateLabel;
            this.detail = detail;
        }
    }

    public static Reply call(int timeoutMs, String... args) throws IOException {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(Math.max(1000, timeoutMs));
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            out.write(MAGIC_LFD1);
            out.writeShort(args.length);
            for (int i = 0; i < args.length; i++) {
                byte[] b = (args[i] == null ? "" : args[i]).getBytes(UTF8);
                out.writeInt(b.length);
                out.write(b);
            }
            out.flush();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                int tag = in.readUnsignedByte();
                if (tag == 1) {
                    int n = in.readInt();
                    if (n < 0 || n > (4 * 1024 * 1024)) {
                        throw new IOException("Invalid LFD1 frame length: " + n);
                    }
                    byte[] chunk = new byte[n];
                    in.readFully(chunk);
                    body.write(chunk);
                } else if (tag == 2) {
                    int exit = in.readInt();
                    return new Reply(exit, new String(body.toByteArray(), UTF8).trim());
                } else {
                    throw new IOException("Unexpected LFD1 tag: " + tag);
                }
            }
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    public static Reply startService(String serviceName, int timeoutMs) throws IOException {
        return call(timeoutMs, "svc-start", MOD_ID, serviceName);
    }

    public static Map<String, String> readVehicleSignals() {
        try {
            Reply r = call(2500,
                    "vehicle",
                    "gear.park",
                    "hv.ready",
                    "hv.conditioning",
                    "hv.conditioning_active",
                    "hv.battery_temp_min",
                    "conditioning.state");
            if (!r.ok() || r.text.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> out = new LinkedHashMap<String, String>();
            String[] lines = r.text.split("[\\r\\n]+");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    out.put(line.substring(0, sp).trim(), line.substring(sp + 1).trim());
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    public static ConditioningStatus queryConditioningStatus() {
        Map<String, String> sigs = readVehicleSignals();
        try {
            Reply r = call(2500, "vehicle-do", "conditioning", "status");
            if (!r.ok()) {
                if (sigs.isEmpty()) {
                    return new ConditioningStatus(false, false, true, false,
                            "UNAVAILABLE", "HMG-API vehicle conditioning bridge not active on this unit.");
                }
                boolean active = isTruthy(sigs.get("hv.conditioning_active")) || isTruthy(sigs.get("hv.conditioning"));
                boolean park = !"false".equalsIgnoreCase(sigs.get("gear.park"));
                boolean ready = isTruthy(sigs.get("hv.ready"));
                String st = sigs.containsKey("conditioning.state") ? sigs.get("conditioning.state") : (active ? "ACTIVE" : "IDLE");
                return new ConditioningStatus(false, active, park, ready, st, r.text);
            }
            boolean active = r.text.toLowerCase().contains("active")
                    || isTruthy(sigs.get("hv.conditioning_active"))
                    || isTruthy(sigs.get("hv.conditioning"));
            boolean park = !"false".equalsIgnoreCase(sigs.get("gear.park"));
            boolean ready = isTruthy(sigs.get("hv.ready"));
            String st = sigs.containsKey("conditioning.state") ? sigs.get("conditioning.state") : (active ? "HEATING" : "READY");
            return new ConditioningStatus(true, active, park, ready, st, r.text);
        } catch (Throwable t) {
            return new ConditioningStatus(false, false, true, false,
                    "OFFLINE", "HMG-API @hmgapid socket not reachable (" + t.getClass().getSimpleName() + ")");
        }
    }

    public static Reply controlConditioning(String action, boolean warmOverride) throws IOException {
        if ("start".equals(action) && warmOverride) {
            return call(4000, "vehicle-do", "conditioning", "start", "warm");
        }
        return call(4000, "vehicle-do", "conditioning", action);
    }

    private static boolean isTruthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "on".equals(s) || "active".equals(s);
    }
}
