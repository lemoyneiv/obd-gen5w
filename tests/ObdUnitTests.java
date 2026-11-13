import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gen5w.ble.Gen5wBleClient;
import obd.app.BleCoordinator;
import obd.app.Bvm02Protocol;
import obd.app.EgmpTelemetry;
import obd.app.Elm327Protocol;
import obd.app.ObdDtcCodec;
import obd.app.WifiElmBridgeService;

/**
 * Deterministic host JVM unit & integration test suite for obd-gen5w:
 *  1. Veepeak GATT resolver & BVM02 hardware-safety exclusion + VIN/UDS guardrails
 *  2. 20-byte chunked BLE prompt assembly, UDS 7F 22 78 pending stripping & ISO-TP multi-frame reassembly
 *  3. Verified Hyundai Ioniq 5 / E-GMP decoders (BMS 7EC, ICCU 7ED, TPMS 7A8, ABS 7D9, Cluster 7CE, Cell Map)
 *  4. Multi-ECU Mode 03/07/0A DTC decoding
 *  5. Live 127.0.0.1 TCP Wi-Fi ELM327 Bridge relay + concurrent MULTICONN 1 BVM02 12V sensor isolation
 */
public final class ObdUnitTests {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
        passed++;
    }

    private static void checkClose(float actual, float expected, float tol, String msg) {
        if (Float.isNaN(actual) || Math.abs(actual - expected) > tol) {
            throw new AssertionError(msg + " — expected " + expected + " +/- " + tol + ", got " + actual);
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        testGattSafetyAndCommandGuardrails();
        testTargetedServiceDiscoveryFallbackAndNotifyEvents();
        testChunkedBleIsoTpReassembly();
        testIoniq5EgmpDecoders();
        testMultiEcuDtcDecoding();
        testLoopbackBridgeAndConcurrentBvm02();
        System.out.println("  [OK] ObdUnitTests passed (" + passed + " assertions verified)");
    }

    private static void testGattSafetyAndCommandGuardrails() {
        // 1. Veepeak OBDCheck BLE GATT table: FFF1=NOTIFY(0x10), FFF2=WRITE|WRITE_NR(0x0C)
        Map<String, Integer> veepeakProps = new LinkedHashMap<String, Integer>();
        veepeakProps.put(Elm327Protocol.UUID_FFF1_NOTIFY, Integer.valueOf(0x10));
        veepeakProps.put(Elm327Protocol.UUID_FFF2_WRITE, Integer.valueOf(0x0C));
        Elm327Protocol.GattBinding vp = Elm327Protocol.resolveAdapterBinding(veepeakProps);
        check(vp != null, "Veepeak FFF0 GATT must resolve");
        check(Elm327Protocol.UUID_FFF1_NOTIFY.equals(vp.notifyUuid), "Veepeak notify UUID must be FFF1");
        check(Elm327Protocol.UUID_FFF2_WRITE.equals(vp.writeUuid), "Veepeak write UUID must be FFF2");
        check(!vp.writeWithoutResponse, "Prefer WRITE with response when 0x08 is advertised");
        check(vp.supportsAlternateWriteMode(), "0x0C write mask must advertise alternate write mode");

        // 1b. Dual-mode BLE+SPP 0x18F0 profile (0x2AF0 Notify + 0x2AF1 Write) as used on dual-mode Veepeak/Beken chips
        Map<String, Integer> dual18f0 = new LinkedHashMap<String, Integer>();
        dual18f0.put("2a00", Integer.valueOf(0x0A));
        dual18f0.put("2a05", Integer.valueOf(0x20));
        dual18f0.put("2af0", Integer.valueOf(0x10));
        dual18f0.put("2af1", Integer.valueOf(0x0C));
        Elm327Protocol.GattBinding d18 = Elm327Protocol.resolveAdapterBinding(dual18f0);
        check(d18 != null && "18F0 (Dual-Mode BLE)".equals(d18.profileName), "18F0 dual-mode GATT must resolve");
        check(Elm327Protocol.UUID_2AF0_NOTIFY.equals(d18.notifyUuid), "18F0 notify must be 2AF0");
        check(Elm327Protocol.UUID_2AF1_WRITE.equals(d18.writeUuid), "18F0 write must be 2AF1");

        // 1c. Reversed FFF0 profile (FFF2 Notify + FFF1 Write without FFF4)
        Map<String, Integer> revFff0 = new LinkedHashMap<String, Integer>();
        revFff0.put("fff1", Integer.valueOf(0x08));
        revFff0.put("fff2", Integer.valueOf(0x10));
        Elm327Protocol.GattBinding rff = Elm327Protocol.resolveAdapterBinding(revFff0);
        check(rff != null && rff.profileName.contains("FFF0 Reversed"), "Reversed FFF0 must resolve when FFF4 absent");
        check(Elm327Protocol.UUID_FFF2_WRITE.equals(rff.notifyUuid), "Reversed FFF0 notify must be FFF2");
        check(Elm327Protocol.UUID_FFF1_NOTIFY.equals(rff.writeUuid), "Reversed FFF0 write must be FFF1");

        // 1d. FFE0 Dual-Char (FFE1 Notify + FFE2 Write_NR) & FFE0/FFE5 (FFE4 Notify + FFE9 Write) & JieLi AE00
        Map<String, Integer> ffeDual = new LinkedHashMap<String, Integer>();
        ffeDual.put("ffe1", Integer.valueOf(0x10));
        ffeDual.put("ffe2", Integer.valueOf(0x04));
        Elm327Protocol.GattBinding fd = Elm327Protocol.resolveAdapterBinding(ffeDual);
        check(fd != null && "FFE0 Dual-Char".equals(fd.profileName) && fd.writeWithoutResponse,
                "FFE0 Dual-Char with WNR must resolve");

        Map<String, Integer> ffe49 = new LinkedHashMap<String, Integer>();
        ffe49.put("ffe4", Integer.valueOf(0x10));
        ffe49.put("ffe9", Integer.valueOf(0x08));
        check(Elm327Protocol.resolveAdapterBinding(ffe49) != null, "FFE0/FFE5 (FFE4+FFE9) must resolve");

        Map<String, Integer> ae00 = new LinkedHashMap<String, Integer>();
        ae00.put("ae02", Integer.valueOf(0x10));
        ae00.put("ae01", Integer.valueOf(0x04));
        check(Elm327Protocol.resolveAdapterBinding(ae00) != null, "JieLi AE00 (AE02+AE01) must resolve");

        // 1e. Custom vendor UART fallback (e.g. 1002 Notify + 1001 Write) MUST resolve,
        // while a partial GAP+GATT system-only table (2a00 READ|WRITE + 2a05 INDICATE) MUST return null!
        Map<String, Integer> customUart = new LinkedHashMap<String, Integer>();
        customUart.put("1002", Integer.valueOf(0x10));
        customUart.put("1001", Integer.valueOf(0x08));
        Elm327Protocol.GattBinding cu = Elm327Protocol.resolveAdapterBinding(customUart);
        check(cu != null && cu.profileName.startsWith("Custom UART"), "Custom vendor UART must resolve");

        Map<String, Integer> gapGattOnly = new LinkedHashMap<String, Integer>();
        gapGattOnly.put("2a00", Integer.valueOf(0x0A)); // Device Name (Read|Write)
        gapGattOnly.put("2a01", Integer.valueOf(0x02)); // Appearance (Read)
        gapGattOnly.put("2a05", Integer.valueOf(0x20)); // Service Changed (Indicate)
        check(Elm327Protocol.resolveAdapterBinding(gapGattOnly) == null,
                "GAP+GATT system attributes (2a00/2a05) must NEVER falsely resolve as an ELM327 UART");

        // 2. LNEX BVM02 GATT table: FFF1=READ(0x02), FFF2=NOTIFY(0x10), FFF3=WRITE(0x08), FFF4=NOTIFY(0x10)
        // MUST BE REJECTED as an OBD adapter so we never send AT commands to a 12V battery sensor!
        Map<String, Integer> bvmProps = new LinkedHashMap<String, Integer>();
        bvmProps.put(Bvm02Protocol.UUID_VERSION_INFO_STR, Integer.valueOf(0x02));
        bvmProps.put(Bvm02Protocol.UUID_READ_VOLTAGE_STR, Integer.valueOf(0x10));
        bvmProps.put(Bvm02Protocol.UUID_CMD_WRITE_STR, Integer.valueOf(0x08));
        bvmProps.put(Bvm02Protocol.UUID_CMD_NOTIFY_STR, Integer.valueOf(0x10));
        check(Elm327Protocol.resolveAdapterBinding(bvmProps) == null,
                "LNEX BVM02 GATT table must NEVER resolve as an ELM327 adapter");
        check(Elm327Protocol.resolveAdapterBindings(bvmProps).isEmpty(),
                "LNEX BVM02 GATT table must produce zero candidate bindings");

        // 3. Name & service advertisement heuristics
        check(Elm327Protocol.isLikelyObdAdapter("OBDCheck BLE", Collections.singletonList(Elm327Protocol.UUID_FFF0_SERVICE)),
                "OBDCheck BLE must match OBD heuristic");
        check(Elm327Protocol.isLikelyObdAdapter("VEEPEAK", Collections.<String>emptyList()),
                "VEEPEAK must match OBD heuristic");
        check(Elm327Protocol.isLikelyObdAdapter("BLE Device", Collections.singletonList("18f0")),
                "0x18F0 service advertisement must match OBD heuristic");
        check(!Elm327Protocol.isLikelyObdAdapter("BVM02", Collections.singletonList(Elm327Protocol.UUID_FFF0_SERVICE)),
                "BVM02 must NOT match OBD heuristic even with FFF0");

        // 4. Privacy & UDS safety guardrails
        check(Elm327Protocol.isSafeInternalCommand("220101"), "220101 allowed");
        check(Elm327Protocol.isSafeInternalCommand("ATSH7E4"), "ATSH7E4 allowed");
        check(Elm327Protocol.isSafeInternalCommand("03"), "Mode 03 allowed");
        check(!Elm327Protocol.isSafeInternalCommand("0902"), "Mode 0902 VIN query must be blocked");
        check(!Elm327Protocol.isSafeInternalCommand("22F190"), "UDS 22F190 VIN query must be blocked");
        check(!Elm327Protocol.isSafeInternalCommand("2EF190"), "UDS WriteDataByIdentifier 2E must be blocked");
        check(!Elm327Protocol.isSafeInternalCommand("2701"), "UDS SecurityAccess 27 must be blocked");
        check(!Elm327Protocol.isSafeInternalCommand("3101FF00"), "UDS RoutineControl 31 must be blocked");
    }

    private static void testTargetedServiceDiscoveryFallbackAndNotifyEvents() {
        final List<String> sentLines = Collections.synchronizedList(new ArrayList<String>());
        Gen5wBleClient.LineChannel mockChannel = new Gen5wBleClient.LineChannel() {
            @Override
            public void sendLine(String line) {
                sentLines.add(line);
            }

            @Override
            public void close() {}
        };

        String veepeakMac = "66:1E:87:00:B2:9F";
        BleCoordinator coord = BleCoordinator.createForTest(mockChannel, veepeakMac, false, "");
        sentLines.clear();

        // Simulate legacy wildcard discovery that saw GATT_SVC 1800, 1801, 18f0,
        // but aborted ATT 0x2803 after GAP (2a00:02,2a01:02) due to a handle gap!
        Gen5wBleClient client = coord.getBleClient();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 1800 0001 0007");
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 1801 0010 0013");
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 18f0 0100 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_READY " + veepeakMac + " 2a00:02,2a01:02");

        // Coordinator must NOT mark Incompatible GATT yet; it must disconnect and retry targeted service 18f0!
        check(sentLines.contains("@" + veepeakMac + " DISCONNECT"),
                "Coordinator must disconnect incomplete wildcard session to run targeted service retry, got: " + sentLines);
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE DISCONNECTED " + veepeakMac + " status=0 reason=USER_REQUESTED");
        check(sentLines.contains("CONNECT " + veepeakMac + " -1 18f0"),
                "Coordinator must immediately reconnect with targeted service filter '18f0', got: " + sentLines);

        // Now deliver targeted GATT_READY for 18f0 (2af0:10,2af1:0c) + OP_OK ENABLE_NOTIFY
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 18f0 0100 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_CHAR 2af0 10 0102 0103");
        client.handleServerLine("@" + veepeakMac + " GATT_CHAR 2af1 0c 0105 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_READY " + veepeakMac + " 2af0:10,2af1:0c");

        check(coord.isObdConnected(), "Targeted 18f0 retry must bind and connect OBD session");
        check(!sentLines.contains("@" + veepeakMac + " REQUEST_MTU 185"),
                "Must NOT send unsolicited REQUEST_MTU 185 before ENABLE_NOTIFY");
        check(sentLines.contains("@" + veepeakMac + " ENABLE_NOTIFY 2af0"),
                "Must enable notifications on 2af0 after targeted GATT_READY, got: " + sentLines);
        client.handleServerLine("@" + veepeakMac + " OP_OK ENABLE_NOTIFY 2af0");
    }

    private static void testChunkedBleIsoTpReassembly() {
        // Construct realistic 62-byte UDS 62 01 01 payload for Ioniq 5 BMS (7EC)
        byte[] uds = buildSampleBms0101Payload();
        check(uds.length == 62, "Sample BMS 220101 payload is 62 bytes");

        // Wrap into ATH1+ATS0 CAN frames preceded by UDS 7F 22 78 ResponsePending!
        StringBuilder rawElm = new StringBuilder();
        rawElm.append("7EC037F2278\r"); // ResponsePending — must be ignored!
        // First Frame: 7EC 10 3E + first 6 bytes
        rawElm.append("7EC103E").append(Elm327Protocol.bytesToHex(Arrays.copyOfRange(uds, 0, 6))).append('\r');
        int seq = 1;
        for (int off = 6; off < uds.length; off += 7) {
            int rem = Math.min(7, uds.length - off);
            byte[] padFrame = new byte[7];
            Arrays.fill(padFrame, (byte) 0xAA);
            System.arraycopy(uds, off, padFrame, 0, rem);
            rawElm.append(String.format("7EC2%X%s\r", seq & 0x0F, Elm327Protocol.bytesToHex(padFrame)));
            seq++;
        }
        rawElm.append('>');

        // Chop into 20-byte BLE ATT notifications
        byte[] wire = rawElm.toString().getBytes(ASCII);
        Elm327Protocol.PromptBuffer pb = new Elm327Protocol.PromptBuffer();
        List<String> prompts = new ArrayList<String>();
        for (int off = 0; off < wire.length; off += 20) {
            byte[] chunk = Arrays.copyOfRange(wire, off, Math.min(wire.length, off + 20));
            prompts.addAll(pb.feed(chunk));
        }
        check(prompts.size() == 1, "Expected exactly 1 completed prompt response across 20-byte chunks");

        List<Elm327Protocol.IsoTpMessage> msgs = Elm327Protocol.parseIsoTpMessages(prompts.get(0), true);
        check(msgs.size() == 1, "Expected 1 reassembled ISO-TP message (7F2278 skipped)");
        check(msgs.get(0).canId == 0x7EC, "Expected CAN ID 0x7EC");
        check(Arrays.equals(uds, msgs.get(0).payload), "Reassembled 62-byte UDS payload must match byte-for-byte");

        checkClose(Elm327Protocol.parseAtRvVolts("ATRV\r14.4V\r"), 14.4f, 0.01f, "ATRV parser");
    }

    private static void testIoniq5EgmpDecoders() {
        EgmpTelemetry tel = new EgmpTelemetry();

        // 1. Apply BMS 220101
        byte[] bms0101 = buildSampleBms0101Payload();
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, bms0101))),
                "BMS 220101 must apply");

        // 2. Apply BMS 220105 (46 bytes)
        byte[] bms0105 = new byte[46];
        bms0105[0] = 0x62; bms0105[1] = 0x01; bms0105[2] = 0x05;
        for (int i = 0; i < 7; i++) bms0105[12 + i] = (byte) (23 + (i % 3));
        bms0105[26] = 38; // PTC heater 38 C
        bms0105[28] = 0x03; bms0105[29] = (byte) 0xE8; // SOH 1000 -> 100.0%
        bms0105[31] = 0x72; bms0105[32] = 0x10; // (29200 * 2)/1000 = 58.40 kWh
        bms0105[34] = (byte) 157; // Display SoC 157 / 2 = 78.5%
        for (int i = 0; i < 4; i++) bms0105[42 + i] = 24;
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, bms0105))),
                "BMS 220105 must apply");

        // 3. Apply BMS 220106 (28 bytes)
        byte[] bms0106 = new byte[28];
        bms0106[0] = 0x62; bms0106[1] = 0x01; bms0106[2] = 0x06;
        bms0106[7] = 24; // Coolant 24 C
        bms0106[12] = 0x7D; // PTC HEATER ON
        bms0106[27] = 0x01; // Charging active
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, bms0106))),
                "BMS 220106 must apply");

        // 4. Apply 192 Cell Voltages across 220102..22010C + verify 0xC8 placeholder flood rejection
        int[] cellDids = { 0x0102, 0x0103, 0x0104, 0x010A, 0x010B, 0x010C };
        for (int b = 0; b < cellDids.length; b++) {
            byte[] cb = new byte[39];
            cb[0] = 0x62;
            cb[1] = (byte) ((cellDids[b] >>> 8) & 0xFF);
            cb[2] = (byte) (cellDids[b] & 0xFF);
            for (int c = 0; c < 32; c++) {
                cb[7 + c] = (byte) (197 + ((b + c) % 2)); // 3.94V / 3.96V
            }
            check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, cb))),
                    "BMS cell block " + Integer.toHexString(cellDids[b]) + " must apply");
        }
        byte[] floodBlock = new byte[39];
        floodBlock[0] = 0x62; floodBlock[1] = 0x01; floodBlock[2] = 0x0C;
        Arrays.fill(floodBlock, 7, 39, (byte) 0xC8);
        check(!tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, floodBlock))),
                "Placeholder 0xC8 cell flood must be rejected");

        // 5. Apply ABS/ESC 7D9 -> 220104 (12 bytes — must NOT collide with BMS 7EC 220104!)
        byte[] abs0104 = new byte[12];
        abs0104[0] = 0x62; abs0104[1] = 0x01; abs0104[2] = 0x04;
        abs0104[9] = 104; // 104 km/h
        abs0104[11] = 4;  // Gear D
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7D9, abs0104))),
                "ABS 220104 must apply");

        // 6. Apply ICCU 7ED -> 22E011 (26 bytes)
        byte[] iccu = new byte[26];
        iccu[0] = 0x62; iccu[1] = (byte) 0xE0; iccu[2] = 0x11;
        iccu[12] = (byte) 134; // 134 - 100 = 34 C
        iccu[13] = 0x38; iccu[14] = 0x54; // 14420 -> 14.420 V
        iccu[15] = 0x07; iccu[16] = 0x3A; // 1850 -> 18.50 A
        iccu[17] = 0x1B; iccu[18] = 0x48; // 6984 -> 698.4 V
        iccu[21] = (byte) 0x82; iccu[22] = 0x28; // 33320 / 100 - 327 = +6.20 A
        iccu[23] = 89; // 89% Aux 12V SoC
        iccu[24] = 0x20; iccu[25] = (byte) 0xBC; // 8380 / 1000 + 6.0 = 14.380 V
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7ED, iccu))),
                "ICCU 22E011 must apply");

        // 7. Apply TPMS 7A8 -> 22C00B (24 bytes) & Cluster 7CE -> 22B002 (12 bytes)
        byte[] tpms = new byte[24];
        tpms[0] = 0x62; tpms[1] = (byte) 0xC0; tpms[2] = 0x0B;
        int[] pIdx = { 7, 12, 17, 22 };
        int[] tIdx = { 8, 13, 18, 23 };
        for (int w = 0; w < 4; w++) {
            tpms[pIdx[w]] = (byte) (180 + w); // 36.0 .. 36.6 PSI
            tpms[tIdx[w]] = (byte) (74 + w);  // 24 .. 27 C
        }
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7A8, tpms))),
                "TPMS 22C00B must apply");

        byte[] cluster = new byte[12];
        cluster[0] = 0x62; cluster[1] = (byte) 0xB0; cluster[2] = 0x02;
        int odo = 42195;
        cluster[9] = (byte) ((odo >>> 16) & 0xFF);
        cluster[10] = (byte) ((odo >>> 8) & 0xFF);
        cluster[11] = (byte) (odo & 0xFF);
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7CE, cluster))),
                "Cluster 22B002 must apply");

        EgmpTelemetry.Snapshot s = tel.snapshot();
        checkClose(s.socBmsPct, 75.5f, 0.05f, "BMS SoC");
        checkClose(s.socDisplayPct, 78.5f, 0.05f, "Display SoC");
        checkClose(s.sohPct, 100.0f, 0.05f, "SOH %");
        checkClose(s.remainingEnergyKwh, 58.4f, 0.05f, "Remaining kWh");
        checkClose(s.packVolts, 698.4f, 0.05f, "Pack Volts");
        checkClose(s.packCurrentA, -185.0f, 0.05f, "Pack Current A (DC fast charge)");
        checkClose(s.packPowerKw, -129.204f, 0.1f, "Pack Power kW");
        check(s.cellDeltaMv == 20, "Cell delta mV expected 20, got " + s.cellDeltaMv);
        check(s.populatedCellCount == 192, "Expected 192 populated cells, got " + s.populatedCellCount);
        check(s.populatedModuleCount == 16, "Expected 16 module temps, got " + s.populatedModuleCount);
        check("PTC HEATER ON".equals(s.thermalModeLabel()), "Thermal mode label");
        checkClose(s.ldcOutVolts, 14.42f, 0.01f, "ICCU LDC Out V");
        checkClose(s.ldcOutAmps, 18.50f, 0.02f, "ICCU LDC Out A");
        checkClose(s.aux12SensorVolts, 14.38f, 0.01f, "Aux 12V Sensor V");
        check(s.aux12SocPct == 89, "Aux 12V SoC");
        checkClose(s.tirePsi[0], 36.0f, 0.05f, "FL Tire PSI");
        check(s.speedKmh == 104 && "D".equals(s.gear), "ABS speed & gear");
        check(s.odometerKm == 42195L, "Cluster odometer");
        check(tel.buildCsvExport().contains("698.4"), "CSV export contains HV telemetry row");
    }

    private static void testMultiEcuDtcDecoding() {
        List<Elm327Protocol.IsoTpMessage> msgs = new ArrayList<Elm327Protocol.IsoTpMessage>();
        // ICCU (7ED) Mode 03 response: 1 DTC = P1A90 (0x1A, 0x90) + 00 00 padding
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7ED, new byte[] { 0x43, 0x01, 0x1A, (byte) 0x90, 0x00, 0x00 }));
        // BMS (7EC) Mode 07 pending response: 1 DTC = P0AA6 (0x0A, 0xA6)
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7EC, new byte[] { 0x47, 0x01, 0x0A, (byte) 0xA6 }));
        // ABS (7D9) Mode 0A permanent response: 1 DTC = C1611 (0x56, 0x11)
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7D9, new byte[] { 0x4A, 0x01, 0x56, 0x11 }));

        List<ObdDtcCodec.DtcEntry> dtcs = ObdDtcCodec.parseDtcResponses(msgs);
        check(dtcs.size() == 3, "Expected 3 decoded DTCs across ECUs, got " + dtcs.size());
        check("P1A90".equals(dtcs.get(0).code) && dtcs.get(0).description.contains("ICCU"), "ICCU P1A90 decoded");
        check("P0AA6".equals(dtcs.get(1).code) && dtcs.get(1).ecuName.contains("BMS"), "BMS P0AA6 decoded");
        check("C1611".equals(dtcs.get(2).code) && dtcs.get(2).ecuName.contains("ABS"), "ABS C1611 decoded");
    }

    private static void testLoopbackBridgeAndConcurrentBvm02() throws Exception {
        final List<String> sentBridgeLines = Collections.synchronizedList(new ArrayList<String>());
        Gen5wBleClient.LineChannel mockChannel = new Gen5wBleClient.LineChannel() {
            @Override
            public void sendLine(String line) {
                sentBridgeLines.add(line);
            }

            @Override
            public void close() {}
        };

        String obdMac = "AA:BB:CC:11:22:33";
        String bvmMac = "DD:EE:FF:44:55:66";
        BleCoordinator coord = BleCoordinator.createForTest(mockChannel, obdMac, true, bvmMac);
        check(sentBridgeLines.contains("MULTICONN 1"), "BleCoordinator must enable MULTICONN 1 on @gen5w_ble");

        // Simulate simultaneous GATT_READY for BOTH Veepeak OBDCheck BLE and LNEX BVM02!
        // Note: @gen5w_ble encodes characteristic property masks in base-16 (0x10=NOTIFY, 0x0c=WRITE|WRITE_NR).
        coord.getBleClient().handleServerLine(
                "GATT_READY " + obdMac + " 0000fff1-0000-1000-8000-00805f9b34fb:10,0000fff2-0000-1000-8000-00805f9b34fb:0c");
        coord.getBleClient().handleServerLine(
                "GATT_READY " + bvmMac + " 0000fff1-0000-1000-8000-00805f9b34fb:02,0000fff2-0000-1000-8000-00805f9b34fb:10,0000fff3-0000-1000-8000-00805f9b34fb:08,0000fff4-0000-1000-8000-00805f9b34fb:10");

        check(coord.isObdConnected(), "OBD session must be connected");
        check(coord.getBvmSlot(0).connected, "Concurrent BVM02 Slot 1 must be connected");

        // Verify BVM02 0xFFF1 is protected, whereas Veepeak 0xFFF1 is enabled for notifications!
        boolean bvmProtected = false;
        boolean obdNotifyEnabled = false;
        synchronized (sentBridgeLines) {
            for (String l : sentBridgeLines) {
                if (l.equals("@" + bvmMac + " PROTECT_CHAR fff1")) {
                    bvmProtected = true;
                }
                if (l.equals("@" + obdMac + " ENABLE_NOTIFY fff1")) {
                    obdNotifyEnabled = true;
                }
            }
        }
        check(bvmProtected, "BVM02 0xFFF1 must be hardware write-protected");
        check(obdNotifyEnabled, "Veepeak 0xFFF1 must have notifications enabled");

        // Feed live BVM02 12.64V (0x04F0), 23C (0x17), 12V (0x00) notification concurrently (4-char normalized UUID 'fff2')
        coord.getBleClient().handleServerLine(
                "@" + bvmMac + " CHAR_NOTIFY fff2 04F01700");
        checkClose(coord.getBvmSlot(0).volts, 12.64f, 0.01f, "Concurrent BVM02 voltage decoded");
        check(coord.getBvmSlot(0).socPct == 97, "Concurrent BVM02 SoC 97%");
        check(coord.getBvmSlot(0).tempC == 23, "Concurrent BVM02 Temp 23C");

        // Start Loopback Wi-Fi ELM327 Bridge on ephemeral port (127.0.0.1:0)
        WifiElmBridgeService.LoopbackBridgeServer srv =
                new WifiElmBridgeService.LoopbackBridgeServer(coord, 0);
        srv.start();
        try {
            check(srv.isRunning(), "LoopbackBridgeServer must be running");
            check(srv.getBoundAddress() != null && srv.getBoundAddress().isLoopbackAddress(),
                    "Wi-Fi ELM327 bridge MUST bind strictly to loopback 127.0.0.1");

            int port = srv.getPort();
            Socket tcp = new Socket("127.0.0.1", port);
            try {
                tcp.setSoTimeout(2000);
                // Wait briefly for accept thread to register client
                long waitUntil = System.currentTimeMillis() + 1500L;
                while (!coord.isBridgePassthroughActive() && System.currentTimeMillis() < waitUntil) {
                    Thread.sleep(20L);
                }
                check(coord.isBridgePassthroughActive(), "Coordinator must pause internal polling when TCP client attaches");

                sentBridgeLines.clear();
                OutputStream out = tcp.getOutputStream();
                out.write("ATRV\r".getBytes(ASCII));
                out.flush();

                waitUntil = System.currentTimeMillis() + 1500L;
                String expectedTx = "@" + obdMac + " WRITE_CHAR fff2 415452560d";
                while (!sentBridgeLines.contains(expectedTx) && System.currentTimeMillis() < waitUntil) {
                    Thread.sleep(20L);
                }
                check(sentBridgeLines.contains(expectedTx),
                        "TCP client command ATRV\\r must relay to Veepeak FFF2 write characteristic");

                // Simulate Veepeak replying "14.4V\r>" on FFF1 (short normalized UUID 'fff1')
                coord.getBleClient().handleServerLine(
                        "@" + obdMac + " CHAR_NOTIFY fff1 31342E34560D3E");

                InputStream in = tcp.getInputStream();
                byte[] rxBuf = new byte[32];
                int got = 0;
                while (got < 7) {
                    int n = in.read(rxBuf, got, rxBuf.length - got);
                    if (n < 0) break;
                    got += n;
                }
                String rxStr = new String(rxBuf, 0, got, ASCII);
                check("14.4V\r>".equals(rxStr), "TCP client must receive exact ELM327 prompt reply, got: " + rxStr);
            } finally {
                tcp.close();
            }

            long waitClose = System.currentTimeMillis() + 1500L;
            while (coord.isBridgePassthroughActive() && System.currentTimeMillis() < waitClose) {
                Thread.sleep(20L);
            }
            check(!coord.isBridgePassthroughActive(), "Coordinator must resume internal polling after TCP client disconnects");
        } finally {
            srv.stop();
        }
    }

    private static byte[] buildSampleBms0101Payload() {
        byte[] u = new byte[62];
        u[0] = 0x62; u[1] = 0x01; u[2] = 0x01;
        u[7] = (byte) 151; // BMS SoC 75.5%
        u[8] = 0x5D; u[9] = (byte) 0xC0; // Avail Charge 240.00 kW
        u[10] = 0x6F; u[11] = 0x54; // Avail Discharge 285.00 kW
        u[12] = 0x01; // BMS relay closed
        short rawCur = (short) -1850; // -185.0 A (charging)
        u[13] = (byte) ((rawCur >>> 8) & 0xFF);
        u[14] = (byte) (rawCur & 0xFF);
        int rawVolt = 6984; // 698.4 V
        u[15] = (byte) ((rawVolt >>> 8) & 0xFF);
        u[16] = (byte) (rawVolt & 0xFF);
        u[17] = 25; // Max temp 25 C
        u[18] = 22; // Min temp 22 C
        for (int i = 0; i < 5; i++) u[19 + i] = (byte) (22 + (i % 2));
        u[25] = 21; // Inlet 21 C
        u[26] = (byte) 198; // Max cell 3.96 V
        u[27] = 42;         // Cell #42
        u[28] = (byte) 197; // Min cell 3.94 V
        u[29] = 118;        // Cell #118
        u[30] = 1; u[31] = 35; // Fan 35 Hz
        u[32] = (byte) 144; // BMS Aux 14.4 V
        putU32(u, 33, 12500L); // 1250.0 Ah chg
        putU32(u, 37, 12100L); // 1210.0 Ah disch
        putU32(u, 41, 84500L); // 8450.0 kWh chg
        putU32(u, 45, 81200L); // 8120.0 kWh disch
        putU32(u, 49, 360000L); // 100 h
        u[53] = 0x04; // Ignition on
        u[54] = 0x02; u[55] = (byte) 0xBA; // Inv 698 V
        short m1 = 4200;
        u[56] = (byte) ((m1 >>> 8) & 0xFF); u[57] = (byte) (m1 & 0xFF);
        short m2 = -150;
        u[58] = (byte) ((m2 >>> 8) & 0xFF); u[59] = (byte) (m2 & 0xFF);
        u[60] = 0x03; u[61] = (byte) 0xE8; // 1000 kOhm
        return u;
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }
}
