package obd.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SAE J1979 Mode 03 (Stored), Mode 07 (Pending), and Mode 0A (Permanent) Diagnostic Trouble Code
 * (DTC) decoder with Hyundai/Kia E-GMP ECU labels and common EV fault descriptions.
 */
public final class ObdDtcCodec {

    public static final String STATUS_STORED    = "STORED (Mode 03)";
    public static final String STATUS_PENDING   = "PENDING (Mode 07)";
    public static final String STATUS_PERMANENT = "PERMANENT (Mode 0A)";

    private static final char[] DTC_PREFIX = { 'P', 'C', 'B', 'U' };
    private static final Map<String, String> KNOWN_DESCRIPTIONS;

    static {
        Map<String, String> m = new HashMap<String, String>();
        // Hyundai / Kia E-GMP EV common codes (ICCU, BMS, EPCU/VCU, Thermal, Inverter)
        m.put("P1A90", "ICCU / OBC DC Output Voltage Sensor or Charging Circuit Fault");
        m.put("P1A91", "ICCU / OBC AC Input or PFC Stage Performance Fault");
        m.put("P1AA6", "HV Battery Pack Isolation / BMS Internal Sense Fault");
        m.put("P1AA7", "HV Battery Cell Voltage Deviation / Balancing Threshold Exceeded");
        m.put("P1AB0", "HV Battery Coolant Pump / Thermal Loop Performance");
        m.put("P1B70", "HV Battery Low Voltage or Pre-Charge Contactor Fault");
        m.put("P1B74", "HV Battery Cell Voltage Sensor Line Open / Range");
        m.put("P1B77", "HV Pre-Charge Relay / Main Contactor Circuit Fault");
        m.put("P1B96", "LDC (12V DC-DC Converter) Output Regulation Fault");
        m.put("P0A0A", "High Voltage System Interlock Circuit (HVIL)");
        m.put("P0A0D", "High Voltage System Interlock Circuit Low");
        m.put("P0A7F", "Hybrid / EV Battery Pack Deterioration");
        m.put("P0A80", "HV Battery Module / Pack Performance Threshold");
        m.put("P0A94", "DC/DC Converter (LDC) Performance");
        m.put("P0AA6", "Hybrid / EV Battery Voltage System Isolation Fault");
        m.put("P0AC4", "Hybrid / EV Powertrain Control Module Requested MIL");
        m.put("P0B24", "Hybrid / EV Battery 'A' Voltage Unstable");
        m.put("P0562", "Auxiliary 12V System Voltage Low");
        m.put("P0563", "Auxiliary 12V System Voltage High");
        m.put("U0100", "Lost CAN Communication with ECM / VCU");
        m.put("U0101", "Lost CAN Communication with TCM / E-Shift");
        m.put("U0111", "Lost CAN Communication with Battery Energy Control Module (BMS)");
        m.put("U0121", "Lost CAN Communication with ABS / ESC Control Module");
        m.put("U0140", "Lost CAN Communication with Body Control Module (IGPM/BCM)");
        m.put("U0293", "Lost CAN Communication with EV Powertrain /MCU");
        m.put("C1611", "CAN Timeout EMS / VCU (ESC)");
        KNOWN_DESCRIPTIONS = Collections.unmodifiableMap(m);
    }

    private ObdDtcCodec() {}

    public static final class DtcEntry {
        public final String code;
        public final String status;
        public final int ecuCanId;
        public final String ecuName;
        public final String description;

        public DtcEntry(String code, String status, int ecuCanId, String ecuName, String description) {
            this.code = code;
            this.status = status;
            this.ecuCanId = ecuCanId;
            this.ecuName = ecuName;
            this.description = description;
        }
    }

    /**
     * Resolves an 11-bit UDS response CAN ID into a human-readable E-GMP ECU module label.
     */
    public static String ecuNameForCanId(int canId) {
        switch (canId) {
            case 0x7EC: return "BMS (7EC)";
            case 0x7ED: return "ICCU OBC/LDC (7ED)";
            case 0x7E8: return "VCU / EPCU (7E8)";
            case 0x7E9: return "E-Shift / SCU (7E9)";
            case 0x7EA: return "MCU Rear/Main (7EA)";
            case 0x7EB: return "MCU Front/Sub (7EB)";
            case 0x7EF: return "VCMS / Charge (7EF)";
            case 0x7D9: return "ABS / ESC (7D9)";
            case 0x7A8: return "TPMS / IAU (7A8)";
            case 0x7CE: return "Cluster (7CE)";
            case 0x77E: return "Airbag / ACU (77E)";
            default:
                if (canId > 0) {
                    return String.format(Locale.US, "ECU %03X", canId);
                }
                return "OBD ECU";
        }
    }

    /**
     * Converts a 16-bit SAE J2012 trouble code word into standard 5-character format (e.g. {@code P1A90}).
     */
    public static String formatDtcWord(int hiByte, int loByte) {
        int b1 = hiByte & 0xFF;
        int b2 = loByte & 0xFF;
        if (b1 == 0 && b2 == 0) {
            return null;
        }
        char prefix = DTC_PREFIX[(b1 >>> 6) & 0x03];
        int d1 = (b1 >>> 4) & 0x03;
        int d2 = b1 & 0x0F;
        int d3 = (b2 >>> 4) & 0x0F;
        int d4 = b2 & 0x0F;
        return String.format(Locale.US, "%c%d%X%X%X", prefix, d1, d2, d3, d4);
    }

    public static String describeCode(String code) {
        if (code == null) return "Unknown fault code";
        String upper = code.trim().toUpperCase(Locale.US);
        String exact = KNOWN_DESCRIPTIONS.get(upper);
        if (exact != null) {
            return exact;
        }
        if (upper.startsWith("P0")) return "SAE Standard Powertrain / EV Fault";
        if (upper.startsWith("P1")) return "Hyundai/Kia Manufacturer-Specific Powertrain/EV Fault";
        if (upper.startsWith("P2") || upper.startsWith("P3")) return "Powertrain Sub-System Fault";
        if (upper.startsWith("C")) return "Chassis / ABS / ESC / Regen Braking Fault";
        if (upper.startsWith("B")) return "Body / HVAC / Cabin Electronics Fault";
        if (upper.startsWith("U")) return "Vehicle CAN Network Communication Fault";
        return "Diagnostic Trouble Code";
    }

    /**
     * Parses ISO-TP responses for Mode 03 ({@code 0x43}), Mode 07 ({@code 0x47}), or Mode 0A ({@code 0x4A}).
     */
    public static List<DtcEntry> parseDtcResponses(List<Elm327Protocol.IsoTpMessage> messages) {
        List<DtcEntry> results = new ArrayList<DtcEntry>();
        if (messages == null || messages.isEmpty()) {
            return results;
        }
        Map<String, DtcEntry> dedup = new LinkedHashMap<String, DtcEntry>();
        for (int i = 0; i < messages.size(); i++) {
            Elm327Protocol.IsoTpMessage msg = messages.get(i);
            if (msg == null || msg.payload == null || msg.payload.length < 2) continue;
            int respSid = msg.payload[0] & 0xFF;
            String status;
            if (respSid == 0x43) {
                status = STATUS_STORED;
            } else if (respSid == 0x47) {
                status = STATUS_PENDING;
            } else if (respSid == 0x4A) {
                status = STATUS_PERMANENT;
            } else {
                continue;
            }

            // ISO 15765-4 CAN format for 0x43/0x47/0x4A:
            // byte[0] = 0x43/0x47/0x4A, byte[1] = dtcCount, byte[2..3] = DTC1, byte[4..5] = DTC2, ...
            int startIdx = 2;
            if ((msg.payload.length % 2) == 1 && msg.payload.length >= 3) {
                // Odd payload length fallback where byte[1..2] are DTC1 directly (non-CAN J1850)
                startIdx = 1;
            }
            for (int off = startIdx; off + 1 < msg.payload.length; off += 2) {
                String code = formatDtcWord(msg.payload[off], msg.payload[off + 1]);
                if (code == null) continue;
                String key = msg.canId + "|" + status + "|" + code;
                if (!dedup.containsKey(key)) {
                    dedup.put(key, new DtcEntry(
                            code,
                            status,
                            msg.canId,
                            ecuNameForCanId(msg.canId),
                            describeCode(code)));
                }
            }
        }
        results.addAll(dedup.values());
        return results;
    }

    public static String formatReport(List<DtcEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "No Stored (Mode 03), Pending (Mode 07), or Permanent (Mode 0A) DTCs reported by responding ECUs.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(entries.size()).append(" trouble code entry(s):\n\n");
        for (int i = 0; i < entries.size(); i++) {
            DtcEntry e = entries.get(i);
            sb.append(String.format(Locale.US, "[%s]  %-19s  %s\n  -> %s\n",
                    e.code, e.ecuName, e.status, e.description));
            if (i < entries.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
