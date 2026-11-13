package obd.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java API-19 client for HMG-API's {@code @hmgapid} abstract socket daemon (LFD1 framing).
 *
 * Wire protocol (all 32-bit unsigned integers are LITTLE-ENDIAN):
 * - Request:  {@code "LFD1"} + {@code u32_le argc} + {@code argc * (u32_le len + utf8_bytes)}
 * - Response: {@code 'O' (0x4F) + u32_le len + bytes} (stdout/stderr chunks), terminated by
 *             {@code 'X' (0x58) + u32_le 4 + u32_le exit_code}.
 *
 * Caller mod identity is authenticated by {@code @hmgapid} via {@code SO_PEERCRED} APK UID:
 * - Root mod services: {@code service-start <name>} / {@code service-restart <name>} / {@code service-log <name>}
 * - Live vehicle signal reads: {@code vehicle gear.park speed odometer hv.ready ...}
 * - Optional E-GMP Battery Preconditioning actuation: {@code vehicle-do conditioning start|stop|status [warm]}
 */
public final class HmgapidClient {

    public static final String MOD_ID = "obd-gen5w";
    private static final String SOCKET_NAME = "hmgapid";
    private static final byte[] MAGIC_LFD1 = { 'L', 'F', 'D', '1' };
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_OUTPUT_BYTES = 262144;

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
        public final Map<String, String> signals;

        public ConditioningStatus(boolean supported, boolean active, boolean parked,
                                  boolean hvReady, String stateLabel, String detail) {
            this(supported, active, parked, hvReady, stateLabel, detail, Collections.<String, String>emptyMap());
        }

        public ConditioningStatus(boolean supported, boolean active, boolean parked,
                                  boolean hvReady, String stateLabel, String detail,
                                  Map<String, String> signals) {
            this.supported = supported;
            this.active = active;
            this.parked = parked;
            this.hvReady = hvReady;
            this.stateLabel = stateLabel;
            this.detail = detail;
            this.signals = (signals == null) ? Collections.<String, String>emptyMap() : signals;
        }
    }

    /**
     * Encodes an {@code LFD1} request frame using 32-bit little-endian lengths.
     */
    public static byte[] encodeLfd1Request(String... args) {
        int count = (args == null) ? 0 : args.length;
        ByteArrayOutputStream req = new ByteArrayOutputStream(64);
        req.write(MAGIC_LFD1, 0, MAGIC_LFD1.length);
        writeU32Le(req, count);
        for (int i = 0; i < count; i++) {
            byte[] b = (args[i] == null ? "" : args[i]).getBytes(UTF8);
            writeU32Le(req, b.length);
            req.write(b, 0, b.length);
        }
        return req.toByteArray();
    }

    /**
     * Decodes an {@code LFD1} response stream ({@code 'O'} stdout chunks + {@code 'X'} exit frame).
     */
    public static Reply decodeLfd1Reply(InputStream in) throws IOException {
        ByteArrayOutputStream textOut = new ByteArrayOutputStream(256);
        byte[] header = new byte[5];
        while (readFully(in, header, 5)) {
            int type = header[0] & 0xFF;
            long len = readU32Le(header, 1);
            if (type == 'O') {
                if (len < 0 || len > (4L * 1024L * 1024L)) {
                    throw new IOException("Invalid LFD1 output length: " + len);
                }
                if (len > 0 && len <= MAX_OUTPUT_BYTES) {
                    byte[] chunk = new byte[(int) len];
                    if (!readFully(in, chunk, (int) len)) {
                        throw new IOException("Truncated LFD1 output chunk");
                    }
                    if (textOut.size() < MAX_OUTPUT_BYTES) {
                        int room = MAX_OUTPUT_BYTES - textOut.size();
                        textOut.write(chunk, 0, Math.min(room, chunk.length));
                    }
                } else if (len > 0) {
                    skipFully(in, len);
                }
            } else if (type == 'X') {
                if (len != 4L) {
                    throw new IOException("Invalid LFD1 exit payload length: " + len);
                }
                byte[] codeBuf = new byte[4];
                if (!readFully(in, codeBuf, 4)) {
                    throw new IOException("Truncated LFD1 exit code");
                }
                int exitCode = (int) readU32Le(codeBuf, 0);
                return new Reply(exitCode, new String(textOut.toByteArray(), UTF8).trim());
            } else {
                throw new IOException("Unexpected LFD1 frame tag: 0x" + Integer.toHexString(type));
            }
        }
        throw new IOException("EOF before LFD1 exit frame");
    }

    public static Reply call(int timeoutMs, String... args) throws IOException {
        if (args == null || args.length == 0) {
            return new Reply(-1, "");
        }
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(Math.max(1000, timeoutMs));
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            out.write(encodeLfd1Request(args));
            out.flush();

            return decodeLfd1Reply(in);
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    public static Reply startService(String serviceName, int timeoutMs) throws IOException {
        Reply r = call(timeoutMs, "service-start", serviceName);
        if (r.ok()) {
            return r;
        }
        return call(timeoutMs, "service-restart", serviceName);
    }

    /**
     * Triggers the read-only {@code btprobe} manual service and polls {@code service-log btprobe}
     * across {@code @hmgapid}'s 1-second supervisor tick until the snapshot end marker arrives.
     */
    public static Reply runBtProbe(int maxWaitMs) throws IOException {
        Reply ping = call(2000, "ping");
        if (!ping.ok()) {
            return new Reply(ping.code, "hmgapid ping failed: " + ping.text);
        }

        final String beginMarker = "=== BEGIN BTPROBE ROOT READ-ONLY SNAPSHOT";
        final String endMarker = "=== END BTPROBE ROOT READ-ONLY SNAPSHOT ===";

        int priorEnds = 0;
        int priorLen = 0;
        try {
            Reply preLog = call(2000, "service-log", "btprobe");
            if (preLog.ok()) {
                priorEnds = countOccurrences(preLog.text, endMarker);
                priorLen = preLog.text.length();
            }
        } catch (Throwable ignored) {}

        Reply start = startService("btprobe", 3500);
        if (!start.ok()) {
            return new Reply(start.code, "service-start btprobe failed: " + start.text);
        }

        long deadline = System.currentTimeMillis() + Math.max(1800, maxWaitMs);
        Reply lastLog = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                lastLog = call(2000, "service-log", "btprobe");
                if (lastLog.ok() && !lastLog.text.isEmpty()) {
                    int nowEnds = countOccurrences(lastLog.text, endMarker);
                    if (nowEnds > priorEnds || (priorEnds > 0 && lastLog.text.length() != priorLen
                            && lastLog.text.endsWith(endMarker))) {
                        return new Reply(0, extractLatestRunSlice(lastLog.text, beginMarker));
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (lastLog == null) {
            lastLog = call(2000, "service-log", "btprobe");
        }
        if (lastLog.ok() && !lastLog.text.isEmpty()) {
            return new Reply(0, extractLatestRunSlice(lastLog.text, beginMarker));
        }
        return new Reply(lastLog.code == 0 ? -1 : lastLog.code,
                lastLog.text.isEmpty() ? "btprobe log empty after wait" : lastLog.text);
    }

    public static Map<String, String> readVehicleSignals() {
        try {
            Reply r = call(2500,
                    "vehicle",
                    "gear.park",
                    "speed",
                    "odometer",
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
                            "UNAVAILABLE", "HMG-API vehicle conditioning bridge not active on this unit.", sigs);
                }
                boolean active = isTruthy(sigs.get("hv.conditioning_active")) || isTruthy(sigs.get("hv.conditioning"));
                boolean park = !"false".equalsIgnoreCase(sigs.get("gear.park"));
                boolean ready = isTruthy(sigs.get("hv.ready"));
                String st = sigs.containsKey("conditioning.state") ? sigs.get("conditioning.state") : (active ? "ACTIVE" : "IDLE");
                return new ConditioningStatus(false, active, park, ready, st, r.text, sigs);
            }
            boolean active = r.text.toLowerCase().contains("active")
                    || isTruthy(sigs.get("hv.conditioning_active"))
                    || isTruthy(sigs.get("hv.conditioning"));
            boolean park = !"false".equalsIgnoreCase(sigs.get("gear.park"));
            boolean ready = isTruthy(sigs.get("hv.ready"));
            String st = sigs.containsKey("conditioning.state") ? sigs.get("conditioning.state") : (active ? "HEATING" : "READY");
            return new ConditioningStatus(true, active, park, ready, st, r.text, sigs);
        } catch (Throwable t) {
            return new ConditioningStatus(false, false, true, false,
                    "OFFLINE", "HMG-API @hmgapid socket not reachable (" + t.getClass().getSimpleName() + ")", sigs);
        }
    }

    public static Reply controlConditioning(String action, boolean warmOverride) throws IOException {
        if ("start".equals(action) && warmOverride) {
            return call(4000, "vehicle-do", "conditioning", "start", "warm");
        }
        return call(4000, "vehicle-do", "conditioning", action);
    }

    public static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    public static String extractLatestRunSlice(String fullLog, String beginMarker) {
        if (fullLog == null || beginMarker == null || beginMarker.isEmpty()) return fullLog;
        int lastBegin = fullLog.lastIndexOf(beginMarker);
        return (lastBegin >= 0) ? fullLog.substring(lastBegin) : fullLog;
    }

    /**
     * Filters raw {@code btprobe} output so generic Android system/ashmem {@code BTMAP:} entries
     * do not crowd out Bluetooth HAL libraries ({@code bplus.default.so}), open FDs ({@code BTFD:}),
     * properties, {@code HMGLOG:}, and the snapshot end marker.
     */
    public static List<String> filterBtProbeLines(String rawText, int maxLines) {
        List<String> out = new ArrayList<String>();
        if (rawText == null || rawText.isEmpty() || maxLines <= 0) {
            return out;
        }
        String[] lines = rawText.split("[\\r\\n]+");
        String endLine = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("=== END BTPROBE")) {
                endLine = line;
            }
            if (line.startsWith("BTMAP:")) {
                String low = line.substring(6).toLowerCase(Locale.US);
                if (low.contains("/dev/ashmem/") || low.contains("/dev/__properties__")) {
                    continue;
                }
                boolean relevant = low.contains("bt")
                        || low.contains("blue")
                        || low.contains("bplus")
                        || low.contains("bcm")
                        || low.contains("brcm")
                        || low.contains("serial")
                        || low.contains("tty")
                        || low.contains("uart")
                        || low.contains("rfkill")
                        || low.contains("obd")
                        || low.contains("bvm")
                        || low.contains("hmg")
                        || low.contains("xposed")
                        || low.contains("app-lib");
                if (!relevant) {
                    continue;
                }
            }
            if (out.size() < maxLines) {
                out.add(line);
            }
        }
        if (endLine != null && !out.isEmpty() && !endLine.equals(out.get(out.size() - 1))) {
            if (out.size() >= maxLines) {
                out.set(out.size() - 1, endLine);
            } else {
                out.add(endLine);
            }
        }
        return out;
    }

    private static void writeU32Le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static long readU32Le(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | (((long) (b[off + 1] & 0xFF)) << 8)
                | (((long) (b[off + 2] & 0xFF)) << 16)
                | (((long) (b[off + 3] & 0xFF)) << 24);
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int offset = 0;
        while (offset < len) {
            int n = in.read(b, offset, len - offset);
            if (n < 0) return false;
            offset += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long len) throws IOException {
        byte[] discard = new byte[2048];
        long remaining = len;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else {
                int toRead = (int) Math.min((long) discard.length, remaining);
                int read = in.read(discard, 0, toRead);
                if (read < 0) break;
                remaining -= read;
            }
        }
    }

    private static boolean isTruthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "on".equals(s) || "active".equals(s);
    }
}
