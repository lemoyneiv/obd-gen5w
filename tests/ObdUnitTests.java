import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import gen5w.ble.Gen5wBleClient;
import obd.app.BleCoordinator;
import obd.app.Bvm02Protocol;
import obd.app.EgmpTelemetry;
import obd.app.Elm327Protocol;
import obd.app.HmgapidClient;
import obd.app.ObdDtcCodec;
import obd.app.WifiElmBridgeService;
import obd.hook.BleGattSessionManager;
import obd.hook.BtNativeBridge;

/**
 * Deterministic host JVM unit & integration test suite for obd-gen5w:
 *  1. Veepeak GATT resolver, QN902x OTA fff4:02 safety & BVM02 exclusion + VIN/UDS guardrails
 *  2. Targeted service fallback, transient empty-GATT boot retry & notify synchronization
 *  3. 20-byte chunked BLE prompt assembly, UDS 7F 22 78 pending stripping & ISO-TP multi-frame reassembly
 *  4. Verified Hyundai Ioniq 5 / E-GMP decoders (BMS 7EC, 48-byte ABS 7D9, mA ICCU 7ED, TPMS 7A8, US/metric Cluster 7CE)
 *  5. Multi-ECU Mode 03/07/0A DTC decoding
 *  6. @hmgapid LFD1 32-bit little-endian wire framing round-trip
 *  7. Multi-link (@gen5w_ble MULTICONN 1) LE ACL credit reclaim & @<MAC> DISCONNECT isolation
 *  8. Live 127.0.0.1 TCP Wi-Fi ELM327 Bridge relay + concurrent MULTICONN 1 BVM02 12V sensor isolation
 */
public final class ObdUnitTests {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF8 = Charset.forName("UTF-8");
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
        testTargetedServiceFallbackAndEmptyGattBootRetry();
        testChunkedBleIsoTpReassembly();
        testIoniq5EgmpDecoders();
        testMultiEcuDtcDecoding();
        testHmgapidLfd1LittleEndianProtocol();
        testMultiConnAclCreditReclaimAndDisconnectIsolation();
        testLoopbackBridgeAndConcurrentBvm02();
        System.out.println("  [OK] ObdUnitTests passed (" + passed + " assertions verified)");
    }

    private static void testGattSafetyAndCommandGuardrails() {
        // 1. Real-car QN902x Veepeak OBDCheck BLE GATT table including READ-only OTA fff3:02, fff4:02, fff5:02!
        Map<String, Integer> veepeakProps = new LinkedHashMap<String, Integer>();
        veepeakProps.put("2a00", Integer.valueOf(0x02));
        veepeakProps.put("2a05", Integer.valueOf(0x20));
        veepeakProps.put(Elm327Protocol.UUID_FFF1_NOTIFY, Integer.valueOf(0x10));
        veepeakProps.put(Elm327Protocol.UUID_FFF2_WRITE, Integer.valueOf(0x0C));
        veepeakProps.put("fff3", Integer.valueOf(0x02));
        veepeakProps.put("fff4", Integer.valueOf(0x02)); // QN902x OTA READ-only fff4 (NOT BVM02 NOTIFY!)
        veepeakProps.put("fff5", Integer.valueOf(0x02));
        Elm327Protocol.GattBinding vp = Elm327Protocol.resolveAdapterBinding(veepeakProps);
        check(vp != null, "Veepeak FFF0 GATT with READ-only OTA fff4:02 must resolve");
        check(Elm327Protocol.UUID_FFF1_NOTIFY.equals(vp.notifyUuid), "Veepeak notify UUID must be FFF1");
        check(Elm327Protocol.UUID_FFF2_WRITE.equals(vp.writeUuid), "Veepeak write UUID must be FFF2");
        check(!vp.writeWithoutResponse, "Prefer WRITE with response when 0x08 is advertised");
        check(vp.supportsAlternateWriteMode(), "0x0C write mask must advertise alternate write mode");

        // 1b. Dual-mode BLE+SPP 0x18F0 profile (0x2AF0 Notify + 0x2AF1 Write)
        Map<String, Integer> dual18f0 = new LinkedHashMap<String, Integer>();
        dual18f0.put("2a00", Integer.valueOf(0x0A));
        dual18f0.put("2a05", Integer.valueOf(0x20));
        dual18f0.put("2af0", Integer.valueOf(0x10));
        dual18f0.put("2af1", Integer.valueOf(0x0C));
        Elm327Protocol.GattBinding d18 = Elm327Protocol.resolveAdapterBinding(dual18f0);
        check(d18 != null && "18F0 (Dual-Mode BLE)".equals(d18.profileName), "18F0 dual-mode GATT must resolve");
        check(Elm327Protocol.UUID_2AF0_NOTIFY.equals(d18.notifyUuid), "18F0 notify must be 2AF0");
        check(Elm327Protocol.UUID_2AF1_WRITE.equals(d18.writeUuid), "18F0 write must be 2AF1");

        // 1c. Reversed FFF0 profile (FFF2 Notify + FFF1 Write without FFF4 NOTIFY)
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

        // 1e. Custom vendor UART fallback vs system-only GAP+GATT exclusion
        Map<String, Integer> customUart = new LinkedHashMap<String, Integer>();
        customUart.put("1002", Integer.valueOf(0x10));
        customUart.put("1001", Integer.valueOf(0x08));
        Elm327Protocol.GattBinding cu = Elm327Protocol.resolveAdapterBinding(customUart);
        check(cu != null && cu.profileName.startsWith("Custom UART"), "Custom vendor UART must resolve");

        Map<String, Integer> gapGattOnly = new LinkedHashMap<String, Integer>();
        gapGattOnly.put("2a00", Integer.valueOf(0x0A));
        gapGattOnly.put("2a01", Integer.valueOf(0x02));
        gapGattOnly.put("2a05", Integer.valueOf(0x20));
        check(Elm327Protocol.resolveAdapterBinding(gapGattOnly) == null,
                "GAP+GATT system attributes (2a00/2a05) must NEVER falsely resolve as an ELM327 UART");

        // 2. LNEX BVM02 GATT table: FFF1=READ(0x02), FFF2=NOTIFY(0x10), FFF3=WRITE(0x08), FFF4=NOTIFY(0x10)
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

    private static void testTargetedServiceFallbackAndEmptyGattBootRetry() {
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
        Gen5wBleClient client = coord.getBleClient();
        sentLines.clear();

        // Part A: Transient empty-GATT boot race (real-car 00:49:19 & 00:49:38 scenario)
        // Must NOT permanently lock into "Incompatible GATT"!
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " CONN_STATE DISCONNECTED " + veepeakMac
                + " status=0 reason=EMPTY_GATT_TABLE");
        check(!"Incompatible GATT".equals(coord.getObdStatusText()),
                "EMPTY_GATT_TABLE disconnect must NOT lock into Incompatible GATT, got: " + coord.getObdStatusText());
        check(coord.getObdStatusText().startsWith("Adapter booting"),
                "EMPTY_GATT_TABLE should set transient booting status, got: " + coord.getObdStatusText());

        // Legacy server emitting empty GATT_READY <mac> none must ALSO schedule transient retry, not Incompatible GATT
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " GATT_READY " + veepeakMac + " none");
        check(!"Incompatible GATT".equals(coord.getObdStatusText()),
                "Empty GATT_READY must NOT lock into Incompatible GATT, got: " + coord.getObdStatusText());
        check(sentLines.contains("@" + veepeakMac + " DISCONNECT"),
                "Empty GATT_READY must disconnect cleanly for transient retry");
        client.handleServerLine("@" + veepeakMac + " CONN_STATE DISCONNECTED " + veepeakMac + " status=0 reason=CLOSED");
        check(coord.getObdStatusText().startsWith("Adapter booting"),
                "Transient boot status must persist across disconnect until retry, got: " + coord.getObdStatusText());

        // Part B: Partial discovery on QN902x Veepeak where OTA service (0000d0ff-..., fff4:02 READ-only) was seen
        // alongside fff0, but fff1/fff2 were missed -> must skip 0000d0ff OTA and NOT treat fff4:02 as BVM02!
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 1800 0001 0007");
        client.handleServerLine("@" + veepeakMac + " GATT_SVC 0000d0ff-3c17-d293-8e48-14fe2e4da212 0020 0029");
        client.handleServerLine("@" + veepeakMac + " GATT_SVC fff0 0100 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_READY " + veepeakMac + " 2a00:02,fff4:02");

        check(sentLines.contains("@" + veepeakMac + " DISCONNECT"),
                "Coordinator must disconnect incomplete wildcard session to run targeted service retry");
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE DISCONNECTED " + veepeakMac + " status=0 reason=USER_REQUESTED");
        check(sentLines.contains("CONNECT " + veepeakMac + " -1 fff0"),
                "Coordinator must skip QN902x OTA service and retry targeted 'fff0' despite READ-only fff4:02, got: " + sentLines);

        // Deliver targeted GATT_READY for fff0 (fff1:10,fff2:0c)
        sentLines.clear();
        client.handleServerLine("@" + veepeakMac + " CONN_STATE CONNECTING " + veepeakMac);
        client.handleServerLine("@" + veepeakMac + " GATT_SVC fff0 0100 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_CHAR fff1 10 0102 0103");
        client.handleServerLine("@" + veepeakMac + " GATT_CHAR fff2 0c 0105 0106");
        client.handleServerLine("@" + veepeakMac + " GATT_READY " + veepeakMac + " fff1:10,fff2:0c");

        check(coord.isObdConnected(), "Targeted fff0 retry must bind and connect OBD session");
        check(sentLines.contains("@" + veepeakMac + " ENABLE_NOTIFY fff1"),
                "Must enable notifications on fff1 after targeted GATT_READY, got: " + sentLines);
        client.handleServerLine("@" + veepeakMac + " OP_OK ENABLE_NOTIFY fff1");
    }

    private static void testChunkedBleIsoTpReassembly() {
        byte[] uds = buildSampleBms0101Payload();
        check(uds.length == 62, "Sample BMS 220101 payload is 62 bytes");

        StringBuilder rawElm = new StringBuilder();
        rawElm.append("7EC037F2278\r"); // ResponsePending — must be ignored!
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

        // 1. Apply BMS 220101 (with real-car uds[25]=44 which MUST NOT pollute battInletTempC!)
        byte[] bms0101 = buildSampleBms0101Payload();
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7EC, bms0101))),
                "BMS 220101 must apply");
        check(tel.snapshot().battInletTempC == Integer.MIN_VALUE,
                "BMS 220101 uds[25]=44 must NOT falsely populate battInletTempC before 220106 arrives");

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

        // 3. Apply BMS 220106 (28 bytes: coolant in uds[7], pack inlet in uds[9])
        byte[] bms0106 = new byte[28];
        bms0106[0] = 0x62; bms0106[1] = 0x01; bms0106[2] = 0x06;
        bms0106[7] = 24;    // Coolant 24 C
        bms0106[9] = 21;    // Pack inlet 21 C
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

        // 5. Apply REAL-CAR 48-byte ABS/ESC 7D9 -> 220104 (must NOT be dropped by length<35 or collide with 39B 7EC 220104!)
        byte[] abs0104 = new byte[48];
        abs0104[0] = 0x62; abs0104[1] = 0x01; abs0104[2] = 0x04;
        abs0104[9] = 104; // 104 km/h
        abs0104[11] = 4;  // Gear D
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7D9, abs0104))),
                "48-byte real-car ABS 7D9 220104 must apply");

        // 6. Apply ICCU 7ED -> 22E011 (26 bytes: uds[15..16] in milliamps, uds[21..22] with 32768 midpoint)
        byte[] iccu = new byte[26];
        iccu[0] = 0x62; iccu[1] = (byte) 0xE0; iccu[2] = 0x11;
        iccu[12] = (byte) 134; // 134 - 100 = 34 C
        iccu[13] = 0x38; iccu[14] = 0x54; // 14420 -> 14.420 V
        iccu[15] = 0x48; iccu[16] = 0x44; // 18500 mA -> 18.50 A
        iccu[17] = 0x1B; iccu[18] = 0x48; // 6984 -> 698.4 V
        int rawAuxCur = 32768 + 620;      // +6.20 A
        iccu[21] = (byte) ((rawAuxCur >>> 8) & 0xFF);
        iccu[22] = (byte) (rawAuxCur & 0xFF);
        iccu[23] = 89; // 89% Aux 12V SoC
        iccu[24] = 0x20; iccu[25] = (byte) 0xBC; // 8380 / 1000 + 6.0 = 14.380 V
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7ED, iccu))),
                "ICCU 22E011 must apply");

        // 7. Apply TPMS 7A8 -> 22C00B (24 bytes) & Metric Cluster 7CE -> 22B002 (15 bytes)
        byte[] tpms = new byte[24];
        tpms[0] = 0x62; tpms[1] = (byte) 0xC0; tpms[2] = 0x0B;
        int[] pIdx = { 7, 12, 17, 22 };
        int[] tIdx = { 8, 13, 18, 23 };
        for (int w = 0; w < 4; w++) {
            tpms[pIdx[w]] = (byte) (180 + w);
            tpms[tIdx[w]] = (byte) (74 + w);
        }
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7A8, tpms))),
                "TPMS 22C00B must apply");

        byte[] clusterKm = new byte[15];
        clusterKm[0] = 0x62; clusterKm[1] = (byte) 0xB0; clusterKm[2] = 0x02;
        int odo = 42195;
        clusterKm[9] = (byte) ((odo >>> 16) & 0xFF);
        clusterKm[10] = (byte) ((odo >>> 8) & 0xFF);
        clusterKm[11] = (byte) (odo & 0xFF);
        check(tel.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7CE, clusterKm))),
                "Metric Cluster 22B002 must apply");

        EgmpTelemetry.Snapshot s = tel.snapshot();
        checkClose(s.socBmsPct, 75.5f, 0.05f, "BMS SoC");
        checkClose(s.socDisplayPct, 78.5f, 0.05f, "Display SoC");
        checkClose(s.sohPct, 100.0f, 0.05f, "SOH %");
        checkClose(s.remainingEnergyKwh, 58.4f, 0.05f, "Remaining kWh");
        checkClose(s.packVolts, 698.4f, 0.05f, "Pack Volts");
        checkClose(s.packCurrentA, -185.0f, 0.05f, "Pack Current A (DC fast charge)");
        checkClose(s.packPowerKw, -129.204f, 0.1f, "Pack Power kW");
        check(s.coolantTempC == 24, "Coolant temp 24C");
        check(s.battInletTempC == 21, "Pack inlet temp 21C from 220106 uds[9]");
        check(s.cellDeltaMv == 20, "Cell delta mV expected 20, got " + s.cellDeltaMv);
        check(s.populatedCellCount == 192, "Expected 192 populated cells, got " + s.populatedCellCount);
        check(s.populatedModuleCount == 16, "Expected 16 module temps, got " + s.populatedModuleCount);
        check("PTC HEATER ON".equals(s.thermalModeLabel()), "Thermal mode label");
        checkClose(s.ldcOutVolts, 14.42f, 0.01f, "ICCU LDC Out V");
        checkClose(s.ldcOutAmps, 18.50f, 0.02f, "ICCU LDC Out A (mA scale)");
        checkClose(s.aux12CurrentA, 6.20f, 0.02f, "Aux 12V Current A (32768 midpoint)");
        checkClose(s.aux12SensorVolts, 14.38f, 0.01f, "Aux 12V Sensor V");
        check(s.aux12SocPct == 89, "Aux 12V SoC");
        checkClose(s.tirePsi[0], 36.0f, 0.05f, "FL Tire PSI");
        check(s.speedKmh == 104 && "D".equals(s.gear), "48-byte ABS speed & gear");
        check(s.odometerKm == 42195L, "Cluster odometer km");
        check(tel.buildCsvExport().contains("698.4"), "CSV export contains HV telemetry row");

        // 8. Verify real-car parked Ioniq 5 values:
        //    - packCurrentA = +0.1 A (+0.07 kW idle cabin draw)
        //    - ICCU ldcOutVolts = 14323 (14.323 V), ldcOutAmps = 5118 mA (5.118 A = 73.3 W)
        //    - US-market Cluster 22B002 where uds[9..11]=000000 and uds[12..14]=18450 miles!
        EgmpTelemetry usCar = new EgmpTelemetry();
        byte[] usIccu = new byte[26];
        usIccu[0] = 0x62; usIccu[1] = (byte) 0xE0; usIccu[2] = 0x11;
        usIccu[12] = (byte) 114; // 14 C
        usIccu[13] = 0x37; usIccu[14] = (byte) 0xF3; // 14323 -> 14.323 V
        usIccu[15] = 0x13; usIccu[16] = (byte) 0xFE; // 5118 mA -> 5.118 A
        usIccu[17] = 0x1B; usIccu[18] = 0x56; // 6998 -> 699.8 V
        usIccu[21] = (byte) 0x80; usIccu[22] = 0x05; // 32773 -> +0.05 A
        usIccu[23] = 81;
        usIccu[24] = 0x20; usIccu[25] = 0x76; // 8310 -> 14.310 V
        check(usCar.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7ED, usIccu))),
                "Real-car US Ioniq 5 ICCU frame must apply");

        byte[] usCluster = new byte[16];
        usCluster[0] = 0x62; usCluster[1] = (byte) 0xB0; usCluster[2] = 0x02;
        // uds[9..11] = 00 00 00 (metric km empty on US cluster), uds[12..14] = 18450 miles!
        int miles = 18450;
        usCluster[12] = (byte) ((miles >>> 16) & 0xFF);
        usCluster[13] = (byte) ((miles >>> 8) & 0xFF);
        usCluster[14] = (byte) (miles & 0xFF);
        check(usCar.applyMessages(Collections.singletonList(new Elm327Protocol.IsoTpMessage(0x7CE, usCluster))),
                "US-market miles Cluster 22B002 must apply");

        EgmpTelemetry.Snapshot usSnap = usCar.snapshot();
        checkClose(usSnap.ldcOutVolts, 14.323f, 0.005f, "Real-car LDC 14.323 V");
        checkClose(usSnap.ldcOutAmps, 5.118f, 0.005f, "Real-car LDC 5.118 A");
        check(usSnap.odometerMiles == 18450L, "US odometer exact miles 18450, got " + usSnap.odometerMiles);
        check(usSnap.odometerKm == 29692L, "US odometer converted km 29692, got " + usSnap.odometerKm);
        check("18,450 mi".equals(EgmpTelemetry.formatDistance(usSnap.odometerKm, usSnap.odometerMiles, true)),
                "formatDistance miles must use exact miles without double conversion");
        check("29,692 km".equals(EgmpTelemetry.formatDistance(usSnap.odometerKm, usSnap.odometerMiles, false)),
                "formatDistance km from US miles");

        // 9. Verify HMG-API vehicle signal fallback when CAN speed/gear/odo have not arrived yet
        EgmpTelemetry hmgFallback = new EgmpTelemetry();
        Map<String, String> sigs = new LinkedHashMap<String, String>();
        sigs.put("gear.park", "true");
        sigs.put("speed", "0");
        sigs.put("odometer", "18450 mi");
        hmgFallback.applyHmgVehicleSignals(sigs);
        EgmpTelemetry.Snapshot fb = hmgFallback.snapshot();
        check("P".equals(fb.gear), "HMG signal gear.park fallback");
        check(fb.speedKmh == 0, "HMG signal speed fallback");
        check(fb.odometerMiles == 18450L && fb.odometerKm == 29692L, "HMG signal miles odometer fallback");
    }

    private static void testMultiEcuDtcDecoding() {
        List<Elm327Protocol.IsoTpMessage> msgs = new ArrayList<Elm327Protocol.IsoTpMessage>();
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7ED, new byte[] { 0x43, 0x01, 0x1A, (byte) 0x90, 0x00, 0x00 }));
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7EC, new byte[] { 0x47, 0x01, 0x0A, (byte) 0xA6 }));
        msgs.add(new Elm327Protocol.IsoTpMessage(0x7D9, new byte[] { 0x4A, 0x01, 0x56, 0x11 }));

        List<ObdDtcCodec.DtcEntry> dtcs = ObdDtcCodec.parseDtcResponses(msgs);
        check(dtcs.size() == 3, "Expected 3 decoded DTCs across ECUs, got " + dtcs.size());
        check("P1A90".equals(dtcs.get(0).code) && dtcs.get(0).description.contains("ICCU"), "ICCU P1A90 decoded");
        check("P0AA6".equals(dtcs.get(1).code) && dtcs.get(1).ecuName.contains("BMS"), "BMS P0AA6 decoded");
        check("C1611".equals(dtcs.get(2).code) && dtcs.get(2).ecuName.contains("ABS"), "ABS C1611 decoded");
    }

    private static void testHmgapidLfd1LittleEndianProtocol() throws Exception {
        byte[] req = HmgapidClient.encodeLfd1Request("service-start", "btprobe");
        // Header: 'L','F','D','1' + u32le(2) = 02 00 00 00
        check(req[0] == 'L' && req[1] == 'F' && req[2] == 'D' && req[3] == '1', "LFD1 magic");
        check((req[4] & 0xFF) == 2 && req[5] == 0 && req[6] == 0 && req[7] == 0,
                "LFD1 arg count must be 32-bit LITTLE-ENDIAN (02 00 00 00)");
        check((req[8] & 0xFF) == 13 && req[9] == 0 && req[10] == 0 && req[11] == 0,
                "First arg length must be 32-bit LITTLE-ENDIAN (13 = 0x0d 00 00 00)");

        // Build simulated @hmgapid reply stream: two 'O' chunks + 'X' exitCode=0
        ByteArrayOutputStream rawReply = new ByteArrayOutputStream();
        byte[] c1 = "BTPID: 1842 (com.android.bluetooth)\n".getBytes(UTF8);
        rawReply.write('O');
        writeLe32(rawReply, c1.length);
        rawReply.write(c1);

        byte[] c2 = "=== END BTPROBE ROOT READ-ONLY SNAPSHOT ===\n".getBytes(UTF8);
        rawReply.write('O');
        writeLe32(rawReply, c2.length);
        rawReply.write(c2);

        rawReply.write('X');
        writeLe32(rawReply, 4);
        writeLe32(rawReply, 0);

        HmgapidClient.Reply parsed = HmgapidClient.decodeLfd1Reply(
                new ByteArrayInputStream(rawReply.toByteArray()));
        check(parsed.ok(), "LFD1 reply exit code 0 must be ok()");
        check(parsed.text.contains("BTPID: 1842")
                && parsed.text.contains("=== END BTPROBE ROOT READ-ONLY SNAPSHOT ==="),
                "LFD1 stdout chunks must concatenate cleanly");
    }

    private static void testMultiConnAclCreditReclaimAndDisconnectIsolation() throws Exception {
        final ExecutorService hciRxExec = Executors.newSingleThreadExecutor();
        final BleGattSessionManager[] mgrRef = new BleGattSessionManager[1];
        final AtomicBoolean dropCompletedPkts = new AtomicBoolean(false);
        final AtomicInteger slowReadMs = new AtomicInteger(0);
        final AtomicInteger nextHandle = new AtomicInteger(0x0040);

        BtNativeBridge.Transport mockTransport = new BtNativeBridge.Transport() {
            @Override
            public boolean sendHciCommand(final byte[] cmd) {
                final int opcode = (cmd[0] & 0xFF) | ((cmd[1] & 0xFF) << 8);
                hciRxExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        BleGattSessionManager m = mgrRef[0];
                        if (opcode == BleGattSessionManager.HCI_LE_READ_BUFFER_SIZE) {
                            // Gen5W Broadcom controller: leAclLen=27 (0x001B), leNumPkts=0 (shared BR/EDR+LE pool)
                            m.onHciEvent(new byte[] { 0x0E, 0x07, 0x01, 0x02, 0x20, 0x00, 0x1B, 0x00, 0x00 });
                        } else if (opcode == BleGattSessionManager.HCI_LE_CREATE_CONNECTION) {
                            m.onHciEvent(new byte[] { 0x0F, 0x04, 0x00, 0x01, 0x0D, 0x20 });
                            int h = nextHandle.getAndIncrement();
                            byte[] cc = new byte[19];
                            cc[0] = 0x3E; cc[1] = 17; cc[2] = 0x01; cc[3] = 0x00;
                            cc[4] = (byte) (h & 0xFF); cc[5] = (byte) ((h >> 8) & 0xFF);
                            System.arraycopy(cmd, 9, cc, 8, 6);
                            m.onHciEvent(cc);
                        } else if (opcode == BleGattSessionManager.HCI_DISCONNECT) {
                            int h = (cmd[3] & 0xFF) | ((cmd[4] & 0xFF) << 8);
                            m.onHciEvent(new byte[] { 0x0F, 0x04, 0x00, 0x01, 0x06, 0x04 });
                            m.onHciEvent(new byte[] {
                                    0x05, 0x04, 0x00,
                                    (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF), 0x16
                            });
                        } else {
                            m.onHciEvent(new byte[] { 0x0E, 0x04, 0x01, cmd[0], cmd[1], 0x00 });
                        }
                    }
                });
                return true;
            }

            @Override
            public boolean sendLeAcl(final byte[] acl) {
                final int h = ((acl[0] & 0xFF) | ((acl[1] & 0xFF) << 8)) & 0x0FFF;
                final int attOp = (acl.length > 8) ? (acl[8] & 0xFF) : -1;
                final int startH = (acl.length >= 11) ? ((acl[9] & 0xFF) | ((acl[10] & 0xFF) << 8)) : 0;
                hciRxExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        BleGattSessionManager m = mgrRef[0];
                        if (!dropCompletedPkts.get()) {
                            m.onHciEvent(new byte[] {
                                    0x13, 0x05, 0x01,
                                    (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF), 0x01, 0x00
                            });
                        }
                        byte[] attRsp = null;
                        if (attOp == BleGattSessionManager.ATT_READ_BY_GROUP_TYPE_REQ) {
                            if (startH <= 0x0010) {
                                attRsp = new byte[] { 0x11, 0x06, 0x10, 0x00, 0x16, 0x00, (byte) 0xF0, (byte) 0xFF };
                            } else {
                                attRsp = new byte[] { 0x01, 0x10, acl[9], acl[10], 0x0A };
                            }
                        } else if (attOp == BleGattSessionManager.ATT_READ_BY_TYPE_REQ) {
                            if (startH <= 0x0011) {
                                if (h == 0x0040) {
                                    // Veepeak: fff1 NOTIFY (0x10), fff2 WRITE|WRITE_NR (0x0C)
                                    attRsp = new byte[] {
                                            0x09, 0x07,
                                            0x11, 0x00, 0x10, 0x12, 0x00, (byte) 0xF1, (byte) 0xFF,
                                            0x14, 0x00, 0x0C, 0x15, 0x00, (byte) 0xF2, (byte) 0xFF
                                    };
                                } else {
                                    // BVM02: fff1 READ (0x02), fff3 WRITE (0x08)
                                    attRsp = new byte[] {
                                            0x09, 0x07,
                                            0x11, 0x00, 0x02, 0x12, 0x00, (byte) 0xF1, (byte) 0xFF,
                                            0x14, 0x00, 0x08, 0x15, 0x00, (byte) 0xF3, (byte) 0xFF
                                    };
                                }
                            } else {
                                attRsp = new byte[] { 0x01, 0x08, acl[9], acl[10], 0x0A };
                            }
                        } else if (attOp == BleGattSessionManager.ATT_FIND_INFO_REQ) {
                            if (startH <= 0x0013) {
                                attRsp = new byte[] { 0x05, 0x01, 0x13, 0x00, 0x02, 0x29 };
                            } else {
                                attRsp = new byte[] { 0x01, 0x04, acl[9], acl[10], 0x0A };
                            }
                        } else if (attOp == BleGattSessionManager.ATT_WRITE_REQ) {
                            attRsp = new byte[] { 0x13 };
                        } else if (attOp == BleGattSessionManager.ATT_READ_REQ) {
                            int delay = slowReadMs.get();
                            if (delay > 0) {
                                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                            }
                            attRsp = new byte[] { 0x0B, 0x4F, 0x4B }; // "OK" -> 4f4b
                        }
                        if (attRsp != null) {
                            byte[] rxAcl = new byte[8 + attRsp.length];
                            int hdr = (h & 0x0FFF) | (0x02 << 12);
                            rxAcl[0] = (byte) (hdr & 0xFF);
                            rxAcl[1] = (byte) ((hdr >> 8) & 0xFF);
                            int l2Len = 4 + attRsp.length;
                            rxAcl[2] = (byte) (l2Len & 0xFF);
                            rxAcl[3] = (byte) ((l2Len >> 8) & 0xFF);
                            rxAcl[4] = (byte) (attRsp.length & 0xFF);
                            rxAcl[5] = (byte) ((attRsp.length >> 8) & 0xFF);
                            rxAcl[6] = 0x04; rxAcl[7] = 0x00;
                            System.arraycopy(attRsp, 0, rxAcl, 8, attRsp.length);
                            m.onLeAcl(rxAcl);
                        }
                    }
                });
                return true;
            }

            @Override
            public void setFilterState(boolean listening, int activeLeHandle) {}
        };

        final BleGattSessionManager mgr = new BleGattSessionManager(mockTransport);
        mgrRef[0] = mgr;

        PipedOutputStream clientWrite = new PipedOutputStream();
        PipedInputStream serverRead = new PipedInputStream(clientWrite, 8192);
        PipedOutputStream serverWrite = new PipedOutputStream();
        PipedInputStream clientRead = new PipedInputStream(serverWrite, 16384);

        final List<String> serverLines = Collections.synchronizedList(new ArrayList<String>());
        Thread rxReader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(clientRead, UTF8));
                    String l;
                    while ((l = br.readLine()) != null) {
                        serverLines.add(l);
                    }
                } catch (Throwable ignored) {}
            }
        });
        rxReader.setDaemon(true);
        rxReader.start();

        Thread sessionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mgr.runStreamSession(null, new BufferedReader(new InputStreamReader(serverRead, UTF8)), serverWrite, true);
            }
        });
        sessionThread.setDaemon(true);
        sessionThread.start();

        try {
            String vpMac = "66:1E:87:00:B2:9F";
            String bvmMac = "A4:C1:38:A2:71:BA";

            clientWrite.write(("MULTICONN 1\nCONNECT " + vpMac + " 0 *\nCONNECT " + bvmMac + " 0 *\n").getBytes(UTF8));
            clientWrite.flush();

            waitForLine(serverLines, "@" + vpMac + " GATT_READY " + vpMac, 4000L);
            waitForLine(serverLines, "@" + bvmMac + " GATT_READY " + bvmMac, 4000L);
            check(mgr.describeActiveSessions().contains("credits=4/4"),
                    "Shared Broadcom pool (leNumPkts=0) must initialize to 4/4 ACL credits, got: "
                            + mgr.describeActiveSessions());

            // Test 1: Drop 0x13 Number_Of_Completed_Packets and issue 5 WRITE_CHAR_NR on Veepeak
            // (exceeding 4 credits) — must reclaim stale credits and NEVER fail with ACL_TX_FAILED!
            dropCompletedPkts.set(true);
            serverLines.clear();
            for (int i = 0; i < 5; i++) {
                clientWrite.write(("@" + vpMac + " WRITE_CHAR_NR fff2 41545a0d\n").getBytes(UTF8));
            }
            clientWrite.flush();

            long waitDeadline = System.currentTimeMillis() + 5000L;
            while (countMatchingLines(serverLines, "@" + vpMac + " OP_OK WRITE_CHAR_NR fff2") < 5
                    && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(25L);
            }
            check(countMatchingLines(serverLines, "@" + vpMac + " OP_OK WRITE_CHAR_NR fff2") == 5,
                    "All 5 WRITE_CHAR_NR commands must succeed via stale ACL credit reclaim when 0x13 is dropped, got: "
                            + serverLines);

            // Test 2: Start a slow READ_CHAR on Veepeak (220ms) and immediately issue @<BVM_MAC> DISCONNECT!
            // Disconnecting BVM02 MUST NOT abort Veepeak's in-flight ATT transaction!
            dropCompletedPkts.set(false);
            slowReadMs.set(220);
            serverLines.clear();
            clientWrite.write(("@" + vpMac + " READ_CHAR fff1\n").getBytes(UTF8));
            clientWrite.flush();
            Thread.sleep(40L); // Ensure READ_CHAR is actively waiting inside transactAtt on Veepeak!
            clientWrite.write(("@" + bvmMac + " DISCONNECT\n").getBytes(UTF8));
            clientWrite.flush();

            waitForLine(serverLines, "@" + vpMac + " OP_OK READ_CHAR fff1 4f4b", 3000L);
            waitForLine(serverLines, "@" + bvmMac + " CONN_STATE DISCONNECTED " + bvmMac, 3000L);
            check(true, "Concurrent @<BVM_MAC> DISCONNECT preserved Veepeak ATT transaction and freed BVM02 link");
        } finally {
            try { clientWrite.write("QUIT\n".getBytes(UTF8)); clientWrite.flush(); } catch (Throwable ignored) {}
            try { clientWrite.close(); } catch (Throwable ignored) {}
            sessionThread.join(2000L);
            hciRxExec.shutdownNow();
        }
    }

    private static void waitForLine(List<String> lines, String prefix, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lines) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(prefix)) {
                        passed++;
                        return;
                    }
                }
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("Timed out waiting for '" + prefix + "', saw: " + lines);
    }

    private static int countMatchingLines(List<String> lines, String exact) {
        int c = 0;
        synchronized (lines) {
            for (int i = 0; i < lines.size(); i++) {
                if (exact.equals(lines.get(i))) c++;
            }
        }
        return c;
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

        coord.getBleClient().handleServerLine(
                "GATT_READY " + obdMac + " 0000fff1-0000-1000-8000-00805f9b34fb:10,0000fff2-0000-1000-8000-00805f9b34fb:0c");
        coord.getBleClient().handleServerLine(
                "GATT_READY " + bvmMac + " 0000fff1-0000-1000-8000-00805f9b34fb:02,0000fff2-0000-1000-8000-00805f9b34fb:10,0000fff3-0000-1000-8000-00805f9b34fb:08,0000fff4-0000-1000-8000-00805f9b34fb:10");

        check(coord.isObdConnected(), "OBD session must be connected");
        check(coord.getBvmSlot(0).connected, "Concurrent BVM02 Slot 1 must be connected");

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

        coord.getBleClient().handleServerLine(
                "@" + bvmMac + " CHAR_NOTIFY fff2 04F01700");
        checkClose(coord.getBvmSlot(0).volts, 12.64f, 0.01f, "Concurrent BVM02 voltage decoded");
        check(coord.getBvmSlot(0).socPct == 97, "Concurrent BVM02 SoC 97%");
        check(coord.getBvmSlot(0).tempC == 23, "Concurrent BVM02 Temp 23C");

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
        u[25] = 44; // Non-inlet BMS status byte (=44 on real Ioniq 5; must NOT set battInletTempC!)
        u[26] = (byte) 198; // Max cell 3.96 V
        u[27] = 42;         // Cell #42
        u[28] = (byte) 197; // Min cell 3.94 V
        u[29] = 118;        // Cell #118
        u[30] = 1; u[31] = 35; // Fan 35 Hz
        u[32] = (byte) 144; // BMS Aux 14.4 V
        putU32(u, 33, 12500L);
        putU32(u, 37, 12100L);
        putU32(u, 41, 84500L);
        putU32(u, 45, 81200L);
        putU32(u, 49, 360000L);
        u[53] = 0x04;
        u[54] = 0x02; u[55] = (byte) 0xBA;
        short m1 = 4200;
        u[56] = (byte) ((m1 >>> 8) & 0xFF); u[57] = (byte) (m1 & 0xFF);
        short m2 = -150;
        u[58] = (byte) ((m2 >>> 8) & 0xFF); u[59] = (byte) (m2 & 0xFF);
        u[60] = 0x03; u[61] = (byte) 0xE8;
        return u;
    }

    private static void writeLe32(OutputStream out, int v) throws Exception {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }
}
