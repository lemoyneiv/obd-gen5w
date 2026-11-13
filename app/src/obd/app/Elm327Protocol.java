package obd.app;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java BLE ELM327 / STN / Veepeak OBDCheck BLE framing, GATT characteristic resolver,
 * and 11-bit ISO 15765-4 (ISO-TP) single/multi-frame reassembler for Android 4.4.2 (API 19).
 *
 * Designed specifically for 20-byte ATT notification chunks where responses such as Hyundai/Kia
 * E-GMP BMS {@code 220101} (62 bytes across 9 CAN frames) arrive split across multiple BLE packets
 * before the terminating {@code '>'} prompt character.
 */
public final class Elm327Protocol {

    public static final Charset ASCII = Charset.forName("US-ASCII");

    // Primary Veepeak OBDCheck BLE / Vgate GATT profile (Service 0xFFF0)
    public static final String UUID_FFF0_SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFF1_NOTIFY  = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFF2_WRITE   = "0000fff2-0000-1000-8000-00805f9b34fb";

    // Dual-mode BLE+SPP OBD profile (Service 0x18F0: 0x2AF0 Notify + 0x2AF1 Write)
    public static final String UUID_18F0_SERVICE = "000018f0-0000-1000-8000-00805f9b34fb";
    public static final String UUID_2AF0_NOTIFY  = "00002af0-0000-1000-8000-00805f9b34fb";
    public static final String UUID_2AF1_WRITE   = "00002af1-0000-1000-8000-00805f9b34fb";

    // Secondary Microchip / ISSC Transparent UART BLE profile (used on alternate BLE OBD revisions)
    public static final String UUID_ISSC_SERVICE  = "49535343-fe7d-4ae5-8fa9-9fafd205e455";
    public static final String UUID_ISSC_NOTIFY   = "49535343-1e4d-4bd9-ba61-23c647249616";
    public static final String UUID_ISSC_WRITE    = "49535343-8841-43f4-a8d4-ecbe34729bb3";
    public static final String UUID_ISSC_AIRPATCH = "49535343-aca3-481c-91ec-d85e28a60318";

    // Tertiary Nordic UART Service (NUS) fallback
    public static final String UUID_NUS_SERVICE  = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String UUID_NUS_WRITE    = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String UUID_NUS_NOTIFY   = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    // Alternate FFE0 / FFE5 BLE ELM adapters (single-char FFE1, dual-char FFE1+FFE2, or FFE4+FFE9)
    public static final String UUID_FFE0_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFE1_RW_NOT  = "0000ffe1-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFE2_WRITE   = "0000ffe2-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFE4_NOTIFY  = "0000ffe4-0000-1000-8000-00805f9b34fb";
    public static final String UUID_FFE9_WRITE   = "0000ffe9-0000-1000-8000-00805f9b34fb";

    // Dual-mode Vlink / JieLi BLE UART profiles
    public static final String UUID_VLINK_SERVICE = "e7810a71-73ae-499d-8c15-faa9aef0c3f2";
    public static final String UUID_VLINK_RW_NOT  = "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f";
    public static final String UUID_AE01_WRITE    = "0000ae01-0000-1000-8000-00805f9b34fb";
    public static final String UUID_AE02_NOTIFY   = "0000ae02-0000-1000-8000-00805f9b34fb";

    // BVM02 command notify UUID used to ensure we NEVER treat a BVM02 sensor as an OBD adapter
    private static final String UUID_BVM02_CMD_NOTIFY = "0000fff4-0000-1000-8000-00805f9b34fb";

    // BluetoothGattCharacteristic property bitmasks (API 19)
    public static final int PROP_READ             = 0x02;
    public static final int PROP_WRITE_NO_RESP    = 0x04;
    public static final int PROP_WRITE            = 0x08;
    public static final int PROP_NOTIFY           = 0x10;
    public static final int PROP_INDICATE         = 0x20;

    // Standard initialization sequence for Hyundai/Kia E-GMP 11-bit 500 kbps ISO 15765-4 CAN
    public static final String[] EGMP_INIT_COMMANDS = {
        "ATE0",     // Echo off
        "ATL0",     // Linefeeds off
        "ATS0",     // Spaces off (compact hex frames)
        "ATH1",     // Headers on (preserve 7EC / 7ED / 7A8 / 7D9 / 7CE CAN IDs)
        "ATSP6",    // ISO 15765-4 CAN (11-bit ID, 500 kbaud)
        "ATCAF1",   // CAN auto-formatting on
        "ATCFC1",   // Automatic ISO-TP flow control frames on
        "ATAT1",    // Adaptive timing auto1
        "ATST32"    // Timeout ~200 ms (50 * 4 ms)
    };

    private static final String[] KNOWN_OBD_NAME_TOKENS = {
        "obdcheck",
        "veepeak",
        "obdii",
        "obd-ii",
        "obd2",
        "obdble",
        "ios-vlink",
        "v-link",
        "vgate",
        "icar",
        "elm327",
        "carista",
        "konnwei",
        "nexas",
        "viecar"
    };

    private Elm327Protocol() {}

    /**
     * Resolved BLE GATT notify/write characteristic pair for an ELM327 BLE adapter.
     */
    public static final class GattBinding {
        public final String profileName;
        public final String notifyUuid;
        public final String writeUuid;
        public final boolean writeWithoutResponse;
        public final int writeProps;

        public GattBinding(String profileName, String notifyUuid, String writeUuid, boolean writeWithoutResponse) {
            this(profileName, notifyUuid, writeUuid, writeWithoutResponse,
                    writeWithoutResponse ? PROP_WRITE_NO_RESP : PROP_WRITE);
        }

        public GattBinding(String profileName, String notifyUuid, String writeUuid,
                           boolean writeWithoutResponse, int writeProps) {
            this.profileName = profileName;
            this.notifyUuid = notifyUuid;
            this.writeUuid = writeUuid;
            this.writeWithoutResponse = writeWithoutResponse;
            this.writeProps = writeProps;
        }

        public boolean supportsAlternateWriteMode() {
            return (writeProps & (PROP_WRITE | PROP_WRITE_NO_RESP)) == (PROP_WRITE | PROP_WRITE_NO_RESP);
        }

        public GattBinding withWriteWithoutResponse(boolean wnr) {
            return new GattBinding(profileName, notifyUuid, writeUuid, wnr, writeProps);
        }
    }

    /**
     * Reassembled ISO 15765-4 UDS / OBD-II response payload for a single ECU CAN ID.
     */
    public static final class IsoTpMessage {
        public final int canId;      // e.g. 0x7EC (BMS), 0x7ED (ICCU), or -1 if headerless
        public final byte[] payload; // Reassembled UDS/OBD payload starting with positive response SID (e.g. 0x62)

        public IsoTpMessage(int canId, byte[] payload) {
            this.canId = canId;
            this.payload = payload;
        }
    }

    /**
     * Stateful chunk accumulator that reassembles arbitrary 20-byte BLE GATT notifications
     * into complete {@code '>'} prompt-delimited ELM327 responses.
     */
    public static final class PromptBuffer {
        private static final int MAX_BUFFER_CHARS = 16384;
        private final StringBuilder sb = new StringBuilder(512);

        public synchronized List<String> feed(byte[] chunk) {
            List<String> completed = new ArrayList<String>();
            if (chunk == null || chunk.length == 0) {
                return completed;
            }
            for (int i = 0; i < chunk.length; i++) {
                int b = chunk[i] & 0xFF;
                if (b == 0) {
                    continue;
                }
                char c = (char) b;
                if (c == '>') {
                    completed.add(sb.toString().trim());
                    sb.setLength(0);
                } else {
                    if (sb.length() >= MAX_BUFFER_CHARS) {
                        sb.delete(0, sb.length() - 2048);
                    }
                    sb.append(c);
                }
            }
            return completed;
        }

        public synchronized void reset() {
            sb.setLength(0);
        }

        public synchronized int pendingLength() {
            return sb.length();
        }
    }

    public static String normUuid(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.length() == 36 && s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return s.substring(4, 8);
        }
        return s;
    }

    public static String expandFullUuid(String normOrFull) {
        String n = normUuid(normOrFull);
        if (n.length() == 4) {
            return "0000" + n + "-0000-1000-8000-00805f9b34fb";
        }
        return n;
    }

    /**
     * Heuristic advertisement matcher for Veepeak OBDCheck BLE and compatible BLE OBD-II scanners.
     * Explicitly excludes LNEX BVM02 battery sensors.
     */
    public static boolean isLikelyObdAdapter(String localName, List<String> serviceUuids) {
        if (Bvm02Protocol.isLikelyBvm02Device(localName, null)) {
            return false;
        }
        if (localName != null) {
            String lower = localName.trim().toLowerCase(Locale.US);
            for (int i = 0; i < KNOWN_OBD_NAME_TOKENS.length; i++) {
                if (lower.contains(KNOWN_OBD_NAME_TOKENS[i])) {
                    return true;
                }
            }
        }
        if (serviceUuids != null) {
            for (int i = 0; i < serviceUuids.size(); i++) {
                String u = serviceUuids.get(i);
                if (u == null) continue;
                String norm = normUuid(u);
                if (UUID_ISSC_SERVICE.equals(norm)
                        || UUID_NUS_SERVICE.equals(norm)
                        || UUID_VLINK_SERVICE.equals(norm)
                        || "18f0".equals(norm)
                        || "ffe0".equals(norm)
                        || "ffe5".equals(norm)
                        || "ae00".equals(norm)
                        || "ae30".equals(norm)
                        || "e010".equals(norm)) {
                    return true;
                }
                if ("fff0".equals(norm) && !Bvm02Protocol.isLikelyBvm02Device(localName, null)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolves the highest-priority notify/write characteristic binding from {@code GATT_READY} properties,
     * with strict hardware safety checks so an LNEX BVM02 sensor is NEVER mistaken for an OBD adapter.
     */
    public static GattBinding resolveAdapterBinding(Map<String, Integer> charPropsByUuid) {
        List<GattBinding> all = resolveAdapterBindings(charPropsByUuid);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Resolves an ordered list of candidate ELM327 BLE GATT bindings so dual-service or reversed-polarity
     * adapters can be verified live via {@code ATI}/{@code ATZ} handshake probing.
     */
    public static List<GattBinding> resolveAdapterBindings(Map<String, Integer> charPropsByUuid) {
        if (charPropsByUuid == null || charPropsByUuid.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Integer> normProps = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : charPropsByUuid.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                normProps.put(normUuid(e.getKey()), e.getValue());
            }
        }

        // Hardware safety check: LNEX BVM02 exposes 0xFFF4 (NOTIFY) and has 0xFFF1 as READ-only (0x02).
        // Never allow a BVM02 12V battery sensor to resolve as an OBD-II adapter.
        Integer pFff1 = normProps.get("fff1");
        Integer pFff4 = normProps.get("fff4");
        boolean hasBvmCmdNotify = pFff4 != null && (pFff4.intValue() & (PROP_NOTIFY | PROP_INDICATE)) != 0;
        if (hasBvmCmdNotify) {
            boolean fff1Active = pFff1 != null
                    && (pFff1.intValue() & (PROP_NOTIFY | PROP_INDICATE | PROP_WRITE | PROP_WRITE_NO_RESP)) != 0;
            if (!fff1Active) {
                return Collections.emptyList();
            }
        }

        List<GattBinding> out = new ArrayList<GattBinding>();
        Set<String> seenPairs = new HashSet<String>();

        // 1. Standard Veepeak OBDCheck BLE / Vgate FFF0: FFF1 Notify + FFF2 Write
        addCandidateIfValid(out, seenPairs, normProps, "FFF0 (Veepeak BLE)", "fff1", "fff2");

        // 2. Dual-Mode SPP+BLE 18F0: 2AF0 Notify + 2AF1 Write (and reversed 2AF1/2AF0)
        addCandidateIfValid(out, seenPairs, normProps, "18F0 (Dual-Mode BLE)", "2af0", "2af1");
        addCandidateIfValid(out, seenPairs, normProps, "18F0 Reversed", "2af1", "2af0");

        // 3. Reversed FFF0: FFF2 Notify + FFF1 Write (strictly when BVM02 FFF4 is absent!)
        if (!hasBvmCmdNotify) {
            addCandidateIfValid(out, seenPairs, normProps, "FFF0 Reversed (FFF2/FFF1)", "fff2", "fff1");
        }

        // 4. Microchip / ISSC Transparent UART (and reversed / AirPatch variants)
        addCandidateIfValid(out, seenPairs, normProps, "ISSC UART", UUID_ISSC_NOTIFY, UUID_ISSC_WRITE);
        addCandidateIfValid(out, seenPairs, normProps, "ISSC Reversed", UUID_ISSC_WRITE, UUID_ISSC_NOTIFY);
        addCandidateIfValid(out, seenPairs, normProps, "ISSC AirPatch", UUID_ISSC_AIRPATCH, UUID_ISSC_AIRPATCH);

        // 5. Nordic UART Service (NUS)
        addCandidateIfValid(out, seenPairs, normProps, "Nordic NUS", UUID_NUS_NOTIFY, UUID_NUS_WRITE);

        // 6. Dual-Service FFE0 + FFE5 (FFE4 Notify + FFE9 Write) & Dual-Char FFE1/FFE2
        addCandidateIfValid(out, seenPairs, normProps, "FFE0/FFE5 UART", "ffe4", "ffe9");
        addCandidateIfValid(out, seenPairs, normProps, "FFE0 Dual-Char", "ffe1", "ffe2");
        addCandidateIfValid(out, seenPairs, normProps, "FFE0 Reversed", "ffe2", "ffe1");

        // 7. Single-characteristic UART profiles (FFE1, Vlink BEF8..., FFF1/FFF2)
        addCandidateIfValid(out, seenPairs, normProps, "FFE0 Single-Char", "ffe1", "ffe1");
        addCandidateIfValid(out, seenPairs, normProps, "Vlink BLE UART", UUID_VLINK_RW_NOT, UUID_VLINK_RW_NOT);
        if (!hasBvmCmdNotify) {
            addCandidateIfValid(out, seenPairs, normProps, "FFF0 Single-Char (FFF1)", "fff1", "fff1");
            addCandidateIfValid(out, seenPairs, normProps, "FFF0 Single-Char (FFF2)", "fff2", "fff2");
        }
        addCandidateIfValid(out, seenPairs, normProps, "18F0 Single-Char (2AF0)", "2af0", "2af0");

        // 8. JieLi AE00 BLE SPP-over-GATT (AE02 Notify + AE01 Write)
        addCandidateIfValid(out, seenPairs, normProps, "AE00 BLE UART", "ae02", "ae01");
        addCandidateIfValid(out, seenPairs, normProps, "AE00 Reversed", "ae01", "ae02");

        // 9. Generic Custom Vendor UART Fallback (strictly excluding standard Bluetooth SIG 0x2Axx/0x2Bxx chars)
        if (!hasBvmCmdNotify) {
            List<String> customNotify = new ArrayList<String>();
            List<String> customWrite = new ArrayList<String>();
            for (Map.Entry<String, Integer> e : normProps.entrySet()) {
                String u = e.getKey();
                int p = e.getValue().intValue();
                if (isStandardBluetoothSigCharacteristic(u)) {
                    continue;
                }
                if ((p & (PROP_NOTIFY | PROP_INDICATE)) != 0) {
                    customNotify.add(u);
                }
                if ((p & (PROP_WRITE | PROP_WRITE_NO_RESP)) != 0) {
                    customWrite.add(u);
                }
            }
            for (int i = 0; i < customNotify.size(); i++) {
                String nu = customNotify.get(i);
                for (int j = 0; j < customWrite.size(); j++) {
                    String wu = customWrite.get(j);
                    if (!nu.equals(wu)) {
                        addCandidateIfValid(out, seenPairs, normProps,
                                "Custom UART (" + shortTag(nu) + "/" + shortTag(wu) + ")", nu, wu);
                    }
                }
            }
            for (int i = 0; i < customNotify.size(); i++) {
                String rw = customNotify.get(i);
                if (customWrite.contains(rw)) {
                    addCandidateIfValid(out, seenPairs, normProps,
                            "Custom Single-Char (" + shortTag(rw) + ")", rw, rw);
                }
            }
        }

        return Collections.unmodifiableList(out);
    }

    private static void addCandidateIfValid(List<GattBinding> out,
                                            Set<String> seenPairs,
                                            Map<String, Integer> normProps,
                                            String profileName,
                                            String notifyNorm,
                                            String writeNorm) {
        Integer pnObj = normProps.get(notifyNorm);
        Integer pwObj = normProps.get(writeNorm);
        if (pnObj == null || pwObj == null) return;
        int pn = pnObj.intValue();
        int pw = pwObj.intValue();
        if ((pn & (PROP_NOTIFY | PROP_INDICATE)) == 0) return;
        if ((pw & (PROP_WRITE | PROP_WRITE_NO_RESP)) == 0) return;
        String key = notifyNorm + "->" + writeNorm;
        if (!seenPairs.add(key)) return;
        boolean wnr = (pw & PROP_WRITE) == 0 && (pw & PROP_WRITE_NO_RESP) != 0;
        out.add(new GattBinding(profileName, expandFullUuid(notifyNorm), expandFullUuid(writeNorm), wnr, pw));
    }

    /**
     * Returns {@code true} for standard Bluetooth SIG characteristic UUIDs ({@code 0x2A00..0x2BFF},
     * such as {@code 2a00} Device Name or {@code 2a05} Service Changed) so generic custom-UART
     * fallback never binds to GAP/GATT/DeviceInfo/Battery system attributes.
     */
    public static boolean isStandardBluetoothSigCharacteristic(String normUuid) {
        if (normUuid == null || normUuid.length() != 4) {
            return false;
        }
        try {
            int val = Integer.parseInt(normUuid, 16);
            return val >= 0x2A00 && val <= 0x2BFF;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }

    private static String shortTag(String normUuid) {
        if (normUuid == null) return "?";
        return normUuid.length() > 8 ? normUuid.substring(0, 8) : normUuid;
    }

    /**
     * Encodes an ASCII AT or hex OBD/UDS command terminated with CR ({@code '\r'}).
     * Splits into <=20-byte chunks if needed for default API-19 ATT MTU.
     */
    public static List<byte[]> encodeCommandChunks(String command, int maxChunkBytes) {
        List<byte[]> chunks = new ArrayList<byte[]>();
        if (command == null) {
            return chunks;
        }
        String trimmed = command.trim();
        byte[] raw = (trimmed + "\r").getBytes(ASCII);
        int limit = Math.max(1, maxChunkBytes);
        for (int off = 0; off < raw.length; off += limit) {
            int len = Math.min(limit, raw.length - off);
            byte[] part = new byte[len];
            System.arraycopy(raw, off, part, 0, len);
            chunks.add(part);
        }
        return chunks;
    }

    /**
     * Safety guardrail ensuring internal telemetry commands never transmit UDS write/session/routine
     * services or privacy-sensitive VIN queries ({@code 0902} / {@code 22F190}).
     */
    public static boolean isSafeInternalCommand(String cmd) {
        if (cmd == null) return false;
        String norm = stripSpacesUpper(cmd);
        if (norm.isEmpty()) return false;
        if (norm.startsWith("AT")) {
            return true;
        }
        // Explicitly block Mode 09 PID 02 (VIN) and UDS 22 F190 (VIN)
        if (norm.startsWith("0902") || norm.startsWith("22F190") || norm.startsWith("1A90")) {
            return false;
        }
        // Only allow SAE J1979 read modes (01, 02, 03, 07, 09 non-VIN, 0A), parked DTC clear (04),
        // and UDS ReadDataByIdentifier (22).
        if (norm.length() < 2) return false;
        String sid = norm.substring(0, 2);
        return "01".equals(sid)
                || "02".equals(sid)
                || "03".equals(sid)
                || "04".equals(sid)
                || "07".equals(sid)
                || "0A".equals(sid)
                || "22".equals(sid);
    }

    public static boolean isErrorOrNoData(String rawResponse) {
        if (rawResponse == null) return true;
        String up = rawResponse.toUpperCase(Locale.US);
        return up.contains("NO DATA")
                || up.contains("UNABLE TO CONNECT")
                || up.contains("CAN ERROR")
                || up.contains("BUS INIT: ...ERROR")
                || up.contains("STOPPED")
                || up.trim().equals("?");
    }

    /**
     * Parses an {@code ATRV} adapter input voltage string (e.g. {@code "14.4V"} -> {@code 14.4f}).
     * Returns {@code Float.NaN} if no valid voltage is present.
     */
    public static float parseAtRvVolts(String rawResponse) {
        if (rawResponse == null) return Float.NaN;
        String[] lines = rawResponse.split("[\\r\\n]+");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim().toUpperCase(Locale.US);
            if (line.isEmpty() || line.startsWith("AT")) continue;
            StringBuilder num = new StringBuilder();
            boolean seenDot = false;
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c >= '0' && c <= '9') {
                    num.append(c);
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    num.append(c);
                } else if (c == 'V' && num.length() > 0) {
                    break;
                }
            }
            if (num.length() > 0) {
                try {
                    float v = Float.parseFloat(num.toString());
                    if (v >= 5.0f && v <= 32.0f) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return Float.NaN;
    }

    private static final class MultiFrameBuilder {
        final int canId;
        final int expectedLen;
        final byte[] buf;
        int offset;

        MultiFrameBuilder(int canId, int expectedLen) {
            this.canId = canId;
            this.expectedLen = expectedLen;
            this.buf = new byte[expectedLen];
            this.offset = 0;
        }

        void append(byte[] src, int srcOff, int len) {
            int copy = Math.min(len, expectedLen - offset);
            if (copy > 0) {
                System.arraycopy(src, srcOff, buf, offset, copy);
                offset += copy;
            }
        }

        boolean isComplete() {
            return offset >= expectedLen;
        }
    }

    /**
     * Reassembles 11-bit ISO-TP single-frame and multi-frame CAN responses from an ELM327 prompt block.
     * Supports {@code ATH1} + {@code ATS0} (compact hex), {@code ATH1} + {@code ATS1} (spaced hex),
     * and headerless {@code ATH0} colon-indexed or raw ISO-TP frames.
     * Automatically skips UDS {@code 7F <sid> 78} (ResponsePending) negative responses.
     */
    public static List<IsoTpMessage> parseIsoTpMessages(String rawResponse, boolean headersExpected) {
        List<IsoTpMessage> out = new ArrayList<IsoTpMessage>();
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return out;
        }

        Map<Integer, MultiFrameBuilder> builders = new LinkedHashMap<Integer, MultiFrameBuilder>();
        int headerlessExpectedLen = -1;

        String[] rawLines = rawResponse.split("[\\r\\n]+");
        for (int idx = 0; idx < rawLines.length; idx++) {
            String line = rawLines[idx].trim();
            if (line.isEmpty()) continue;

            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("SEARCHING")
                    || upper.startsWith("AT")
                    || upper.equals("OK")
                    || upper.contains("NO DATA")
                    || upper.contains("STOPPED")
                    || upper.contains("ERROR")
                    || upper.equals("?")) {
                continue;
            }

            // Headerless ELM327 ATCAF1 multi-frame length header line (e.g. "03E" = 62 bytes)
            String compact = stripSpacesUpper(upper);
            if (!headersExpected && compact.length() == 3 && isAllHex(compact) && line.indexOf(':') < 0) {
                headerlessExpectedLen = Integer.parseInt(compact, 16);
                builders.put(Integer.valueOf(-1), new MultiFrameBuilder(-1, headerlessExpectedLen));
                continue;
            }

            // Headerless colon-prefixed line (e.g. "0: 62 01 01 FF F7 E7" or "1: FF 00 ...")
            int colon = compact.indexOf(':');
            if (colon > 0 && colon <= 2) {
                String hexPart = compact.substring(colon + 1);
                if (!isAllHex(hexPart) || (hexPart.length() & 1) != 0) continue;
                byte[] chunk = hexToBytes(hexPart);
                MultiFrameBuilder b = builders.get(Integer.valueOf(-1));
                if (b != null) {
                    b.append(chunk, 0, chunk.length);
                    if (b.isComplete()) {
                        out.add(new IsoTpMessage(-1, b.buf));
                        builders.remove(Integer.valueOf(-1));
                    }
                }
                continue;
            }

            if (!isAllHex(compact)) {
                continue;
            }

            int canId = -1;
            String frameHex = compact;
            if (headersExpected && compact.length() >= 5 && (compact.length() % 2 == 1)) {
                canId = Integer.parseInt(compact.substring(0, 3), 16);
                frameHex = compact.substring(3);
            } else if (!headersExpected && (compact.length() % 2 == 0)) {
                canId = -1;
                frameHex = compact;
            } else if (compact.length() >= 5 && (compact.length() % 2 == 1)) {
                canId = Integer.parseInt(compact.substring(0, 3), 16);
                frameHex = compact.substring(3);
            } else {
                continue;
            }

            byte[] frame = hexToBytes(frameHex);
            if (frame == null || frame.length == 0) continue;

            // If headerless ATCAF1 single-frame without PCI byte (e.g. "620104..." or "410C1A2B")
            if (canId == -1 && !headersExpected) {
                int firstByte = frame[0] & 0xFF;
                if (firstByte >= 0x41 && firstByte <= 0x7F && frame.length <= 8) {
                    if (isResponsePending(frame, 0, frame.length)) {
                        continue;
                    }
                    out.add(new IsoTpMessage(-1, frame));
                    continue;
                }
            }

            int pciType = (frame[0] & 0xF0) >>> 4;
            if (pciType == 0) {
                // Single Frame: [0x0L, d0..dL-1, padding...]
                int sfLen = frame[0] & 0x0F;
                if (sfLen <= 0 || sfLen > frame.length - 1) {
                    continue;
                }
                if (isResponsePending(frame, 1, sfLen)) {
                    // UDS 7F <sid> 78 (ResponsePending) — wait for real positive response frame
                    continue;
                }
                byte[] payload = new byte[sfLen];
                System.arraycopy(frame, 1, payload, 0, sfLen);
                out.add(new IsoTpMessage(canId, payload));
            } else if (pciType == 1) {
                // First Frame: [0x1H, 0xLL, d0..d5]
                if (frame.length < 3) continue;
                int totalLen = ((frame[0] & 0x0F) << 8) | (frame[1] & 0xFF);
                if (totalLen <= 0 || totalLen > 4095) continue;
                MultiFrameBuilder mfb = new MultiFrameBuilder(canId, totalLen);
                mfb.append(frame, 2, frame.length - 2);
                if (mfb.isComplete()) {
                    out.add(new IsoTpMessage(canId, mfb.buf));
                } else {
                    builders.put(Integer.valueOf(canId), mfb);
                }
            } else if (pciType == 2) {
                // Consecutive Frame: [0x2N, d0..d6]
                MultiFrameBuilder mfb = builders.get(Integer.valueOf(canId));
                if (mfb == null || frame.length < 2) continue;
                mfb.append(frame, 1, frame.length - 1);
                if (mfb.isComplete()) {
                    if (!isResponsePending(mfb.buf, 0, mfb.buf.length)) {
                        out.add(new IsoTpMessage(canId, mfb.buf));
                    }
                    builders.remove(Integer.valueOf(canId));
                }
            }
        }
        return out;
    }

    private static boolean isResponsePending(byte[] buf, int off, int len) {
        return len >= 3
                && (buf[off] & 0xFF) == 0x7F
                && (buf[off + 2] & 0xFF) == 0x78;
    }

    public static String stripSpacesUpper(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    public static boolean isAllHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return null;
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        char[] hexChars = "0123456789ABCDEF".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }
}
