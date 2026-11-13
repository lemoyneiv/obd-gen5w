package obd.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import gen5w.ble.Gen5wBleAdvRecord;
import gen5w.ble.Gen5wBleClient;

/**
 * Process-wide BLE coordinator managing simultaneous {@code @gen5w_ble} ({@code MULTICONN 1})
 * connections for:
 *  1. Primary BLE OBD-II Scanner (Veepeak OBDCheck BLE / ELM327 on Service {@code 0xFFF0}:
 *     {@code 0xFFF1} Notify + {@code 0xFFF2} Write, or ISSC/NUS UART fallbacks).
 *  2. Optional concurrent LNEX BVM02 12V Battery Monitor sensors (Slots 1..4 on Service {@code 0xFFF0}:
 *     {@code 0xFFF2} Notify + {@code 0xFFF3} Write + {@code 0xFFF4} Notify, with {@code 0xFFF1} protected).
 *  3. Seamless handoff to {@link WifiElmBridgeService} ({@code 127.0.0.1:35000}), automatically
 *     pausing internal dashboard polling while a TCP client is attached and resuming cleanly on disconnect.
 */
public final class BleCoordinator {

    public interface UiListener {
        void onStateUpdated();
    }

    public interface DtcCallback {
        void onDtcReport(String reportText);
    }

    public interface RawByteSink {
        void onAdapterBytes(byte[] bytes);
    }

    public static final class DiscoveredDevice {
        public final String mac;
        public final int addrType;
        public final int rssi;
        public final String name;
        public final String uuidSummary;
        public final boolean likelyObd;
        public final boolean likelyBvm02;
        public final long seenMs;

        public DiscoveredDevice(String mac, int addrType, int rssi, String name,
                                String uuidSummary, boolean likelyObd, boolean likelyBvm02, long seenMs) {
            this.mac = mac;
            this.addrType = addrType;
            this.rssi = rssi;
            this.name = name;
            this.uuidSummary = uuidSummary;
            this.likelyObd = likelyObd;
            this.likelyBvm02 = likelyBvm02;
            this.seenMs = seenMs;
        }
    }

    public static final class BvmSlotState {
        public final int slot;
        public String mac = "";
        public String name = "";
        public boolean connected = false;
        public float volts = Float.NaN;
        public int centivolts = 0;
        public int socPct = -1;
        public int tempC = 0;
        public boolean hasTemp = false;
        public boolean is24V = false;
        public float firmwareVersion = 0.0f;
        public String statusLabel = "Unpaired";
        public final Bvm02Protocol.FrameAssembler assembler = new Bvm02Protocol.FrameAssembler();
        public final float[] recentVolts = new float[120];
        public int recentCount = 0;

        public BvmSlotState(int slot) {
            this.slot = slot;
        }

        public synchronized void pushSample(float v) {
            if (recentCount < recentVolts.length) {
                recentVolts[recentCount++] = v;
            } else {
                System.arraycopy(recentVolts, 1, recentVolts, 0, recentVolts.length - 1);
                recentVolts[recentVolts.length - 1] = v;
            }
        }

        public synchronized float[] copySamples() {
            float[] out = new float[recentCount];
            System.arraycopy(recentVolts, 0, out, 0, recentCount);
            return out;
        }
    }

    private static BleCoordinator sInstance;

    public static synchronized BleCoordinator getInstance(Context ctx) {
        if (sInstance == null) {
            DevicePrefs prefs = new DevicePrefs(ctx.getApplicationContext());
            sInstance = new BleCoordinator(prefs, false);
        }
        return sInstance;
    }

    /**
     * Creates an isolated coordinator bound to a custom {@link Gen5wBleClient.LineChannel}
     * for deterministic JVM unit testing.
     */
    public static BleCoordinator createForTest(Gen5wBleClient.LineChannel channel,
                                               String obdMac,
                                               boolean bvmEnabled,
                                               String bvmSlot0Mac) {
        BleCoordinator coord = new BleCoordinator(null, true);
        coord.obdMac = obdMac == null ? "" : obdMac.toUpperCase(Locale.US);
        coord.obdName = "Veepeak Test";
        coord.bvmEnabled = bvmEnabled;
        if (bvmSlot0Mac != null && !bvmSlot0Mac.isEmpty()) {
            coord.bvmSlots[0].mac = bvmSlot0Mac.toUpperCase(Locale.US);
            coord.bvmSlots[0].name = "BVM02 Test";
            coord.bvmSlots[0].statusLabel = "Connecting";
        }
        coord.bleClient.attachCustomChannel(channel);
        coord.sessionReady = true;
        return coord;
    }

    private final DevicePrefs prefs;
    private final boolean testMode;
    private final EgmpTelemetry telemetry = new EgmpTelemetry();
    private final List<UiListener> uiListeners = new CopyOnWriteArrayList<UiListener>();
    private final Map<String, DiscoveredDevice> discovered = new LinkedHashMap<String, DiscoveredDevice>();
    private final BvmSlotState[] bvmSlots = new BvmSlotState[DevicePrefs.BVM_SLOTS];

    private final Gen5wBleClient bleClient;
    private final Elm327Protocol.PromptBuffer promptBuffer = new Elm327Protocol.PromptBuffer();
    private final Object promptLock = new Object();
    private String latestPromptResponse = null;

    private volatile boolean sessionReady = false;
    private volatile String bridgeServerVersion = "connecting";
    private volatile boolean scanning = false;
    private volatile boolean uiForeground = false;
    private volatile boolean keepAliveBackground = false;

    // Primary OBD state
    private volatile String obdMac = "";
    private volatile String obdName = "";
    private volatile int obdAddrType = -1;
    private volatile int obdLastRssi = 0;
    private volatile boolean obdConnecting = false;
    private volatile boolean obdConnected = false;
    private volatile Elm327Protocol.GattBinding obdBinding = null;
    private volatile int obdChunkBytes = 20;
    private volatile String obdStatusText = "Unpaired";
    private volatile String elmVersionText = "idle";
    private volatile float pollHz = 0.0f;
    private volatile int vehicleProfile = EgmpTelemetry.PROFILE_EGMP_192;

    // One-shot command requests
    private final AtomicBoolean pendingCellScan = new AtomicBoolean(false);
    private volatile DtcCallback pendingDtcScan = null;
    private volatile DtcCallback pendingDtcClear = null;
    private volatile boolean iccuUseMy25Did = false;

    // Raw TCP Wi-Fi Bridge state
    private volatile RawByteSink bridgeRawSink = null;
    private volatile int bridgeClientCount = 0;
    private final AtomicBoolean needReinitAfterBridge = new AtomicBoolean(false);

    // Optional BVM02 state
    private volatile boolean bvmEnabled = false;
    private final AtomicBoolean stopWorker = new AtomicBoolean(false);
    private Thread pollThread;

    private BleCoordinator(DevicePrefs prefs, boolean testMode) {
        this.prefs = prefs;
        this.testMode = testMode;
        for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
            bvmSlots[i] = new BvmSlotState(i);
        }
        if (prefs != null) {
            reloadFromPrefs();
        }
        this.bleClient = new Gen5wBleClient(new BleCallback());
        if (!testMode) {
            this.bleClient.connectBridge();
            startSupervisorThread();
        }
    }

    public void reloadFromPrefs() {
        if (prefs == null) return;
        this.obdMac = prefs.getObdMac().toUpperCase(Locale.US);
        this.obdName = prefs.getObdName();
        this.obdAddrType = prefs.getObdAddrType();
        this.vehicleProfile = prefs.getVehicleProfile();
        this.bvmEnabled = prefs.isBvm02Enabled();
        if (obdMac.isEmpty()) {
            obdStatusText = "Unpaired";
        } else if (!obdConnected && !obdConnecting) {
            obdStatusText = "Paired (" + shortLabel(obdName, obdMac) + ")";
        }
        for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
            String m = prefs.getBvmMac(i).toUpperCase(Locale.US);
            bvmSlots[i].mac = m;
            bvmSlots[i].name = prefs.getBvmName(i);
            if (m.isEmpty()) {
                bvmSlots[i].statusLabel = "Unpaired";
            } else if (!bvmSlots[i].connected) {
                bvmSlots[i].statusLabel = bvmEnabled ? "Standby" : "Disabled";
            }
        }
    }

    public EgmpTelemetry getTelemetry() {
        return telemetry;
    }

    public Gen5wBleClient getBleClient() {
        return bleClient;
    }

    public void addUiListener(UiListener l) {
        if (l != null && !uiListeners.contains(l)) {
            uiListeners.add(l);
        }
    }

    public void removeUiListener(UiListener l) {
        uiListeners.remove(l);
    }

    private void notifyUi() {
        for (int i = 0; i < uiListeners.size(); i++) {
            try {
                uiListeners.get(i).onStateUpdated();
            } catch (Throwable ignored) {}
        }
    }

    public void setUiForeground(boolean fg) {
        this.uiForeground = fg;
        if (fg) {
            ensureConfiguredDevicesConnected();
        }
    }

    public void setKeepAliveInBackground(boolean keepAlive) {
        this.keepAliveBackground = keepAlive;
        if (keepAlive) {
            ensureConfiguredDevicesConnected();
        }
    }

    public void setVehicleProfile(int profile) {
        this.vehicleProfile = profile;
        if (prefs != null) {
            prefs.setVehicleProfile(profile);
        }
        notifyUi();
    }

    public int getVehicleProfile() {
        return vehicleProfile;
    }

    public boolean isSessionReady() {
        return sessionReady;
    }

    public String getBridgeServerVersion() {
        return bridgeServerVersion;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isObdConnected() {
        return obdConnected && obdBinding != null;
    }

    public String getObdStatusText() {
        return obdStatusText;
    }

    public String getObdMac() {
        return obdMac;
    }

    public String getObdName() {
        return obdName;
    }

    public int getObdLastRssi() {
        return obdLastRssi;
    }

    public String getElmVersionText() {
        return elmVersionText;
    }

    public float getPollHz() {
        return pollHz;
    }

    public boolean isBridgePassthroughActive() {
        return bridgeClientCount > 0;
    }

    public BvmSlotState getBvmSlot(int slot) {
        return bvmSlots[slot];
    }

    public boolean isBvmEnabled() {
        return bvmEnabled;
    }

    public void setBvmEnabled(boolean enabled) {
        this.bvmEnabled = enabled;
        if (prefs != null) {
            prefs.setBvm02Enabled(enabled);
        }
        if (!enabled) {
            for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
                if (!bvmSlots[i].mac.isEmpty()) {
                    bleClient.disconnectDevice(bvmSlots[i].mac);
                    bvmSlots[i].connected = false;
                    bvmSlots[i].statusLabel = "Disabled";
                }
            }
        } else {
            ensureConfiguredDevicesConnected();
        }
        notifyUi();
    }

    public List<DiscoveredDevice> getDiscoveredDevices() {
        synchronized (discovered) {
            return new ArrayList<DiscoveredDevice>(discovered.values());
        }
    }

    public void startBleScan() {
        synchronized (discovered) {
            discovered.clear();
        }
        bleClient.startScan();
        notifyUi();
    }

    public void stopBleScan() {
        bleClient.stopScan();
        notifyUi();
    }

    public void pairObdAdapter(String mac, String name, int addrType) {
        if (mac == null || mac.trim().isEmpty()) return;
        String clean = mac.trim().toUpperCase(Locale.US);
        if (!obdMac.isEmpty() && !obdMac.equals(clean)) {
            bleClient.disconnectDevice(obdMac);
        }
        this.obdMac = clean;
        this.obdName = (name == null || name.trim().isEmpty()) ? "BLE OBD-II" : name.trim();
        this.obdAddrType = addrType;
        this.obdConnected = false;
        this.obdBinding = null;
        this.obdStatusText = "Connecting...";
        if (prefs != null) {
            prefs.setObdAdapter(clean, this.obdName, addrType);
        }
        bleClient.stopScan();
        bleClient.connectDevice(clean, addrType, "*");
        notifyUi();
    }

    public void clearObdAdapter() {
        if (!obdMac.isEmpty()) {
            bleClient.disconnectDevice(obdMac);
        }
        obdMac = "";
        obdName = "";
        obdConnected = false;
        obdConnecting = false;
        obdBinding = null;
        obdStatusText = "Unpaired";
        if (prefs != null) {
            prefs.clearObdAdapter();
        }
        notifyUi();
    }

    public void pairBvmSensor(int slot, String mac, String name, int addrType) {
        if (slot < 0 || slot >= DevicePrefs.BVM_SLOTS || mac == null) return;
        String clean = mac.trim().toUpperCase(Locale.US);
        if (!bvmSlots[slot].mac.isEmpty() && !bvmSlots[slot].mac.equals(clean)) {
            bleClient.disconnectDevice(bvmSlots[slot].mac);
        }
        bvmSlots[slot].mac = clean;
        bvmSlots[slot].name = (name == null || name.trim().isEmpty()) ? ("BVM02 #" + (slot + 1)) : name.trim();
        bvmSlots[slot].connected = false;
        bvmSlots[slot].statusLabel = "Connecting...";
        if (prefs != null) {
            prefs.setBvmSensor(slot, clean, bvmSlots[slot].name, addrType);
            if (!bvmEnabled) {
                bvmEnabled = true;
                prefs.setBvm02Enabled(true);
            }
        }
        bleClient.connectDevice(clean, addrType, "fff0");
        notifyUi();
    }

    public void requestReconnectAll() {
        if (!obdMac.isEmpty()) {
            obdConnected = false;
            obdConnecting = false;
            bleClient.disconnectDevice(obdMac);
        }
        ensureConfiguredDevicesConnected();
    }

    public void requestCellScan() {
        pendingCellScan.set(true);
        DiagLogger.get().info("OBD", "Queued full E-GMP cell voltage sweep (220102..22010C)");
    }

    public void requestDtcScan(DtcCallback cb) {
        this.pendingDtcScan = cb;
    }

    public void requestDtcClear(DtcCallback cb) {
        this.pendingDtcClear = cb;
    }

    public void syncBvm02History() {
        if (!bvmEnabled) return;
        for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
            BvmSlotState st = bvmSlots[i];
            if (st.connected && !st.mac.isEmpty()) {
                byte[] histCmd = Bvm02Protocol.buildFrame(Bvm02Protocol.CMD_GET_HISTORY_V1, new byte[0]);
                writeBvmCommand(st.mac, histCmd);
                DiagLogger.get().info("BVM02", "Requested history sync on Slot " + (i + 1) + " (" + st.mac + ")");
            }
        }
    }

    // ================= WIFI ELM327 TCP BRIDGE INTEGRATION =================

    public void attachBridgeClient(RawByteSink sink, int activeClients) {
        this.bridgeRawSink = sink;
        this.bridgeClientCount = Math.max(0, activeClients);
        if (this.bridgeClientCount > 0) {
            this.needReinitAfterBridge.set(true);
            synchronized (promptLock) {
                promptLock.notifyAll();
            }
        }
        notifyUi();
    }

    public boolean sendRawFromBridge(byte[] rawBytes) {
        Elm327Protocol.GattBinding binding = this.obdBinding;
        String mac = this.obdMac;
        if (!obdConnected || binding == null || mac.isEmpty() || rawBytes == null || rawBytes.length == 0) {
            return false;
        }
        int limit = Math.max(20, obdChunkBytes);
        for (int off = 0; off < rawBytes.length; off += limit) {
            int len = Math.min(limit, rawBytes.length - off);
            byte[] part = new byte[len];
            System.arraycopy(rawBytes, off, part, 0, len);
            bleClient.writeCharacteristic(mac, binding.writeUuid, part, binding.writeWithoutResponse);
        }
        return true;
    }

    public void ensureConfiguredDevicesConnected() {
        if (!sessionReady) return;
        if (!obdMac.isEmpty() && !obdConnected && !obdConnecting) {
            obdConnecting = true;
            obdStatusText = "Connecting...";
            bleClient.connectDevice(obdMac, obdAddrType, "*");
        }
        if (bvmEnabled) {
            for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
                BvmSlotState st = bvmSlots[i];
                if (!st.mac.isEmpty() && !st.connected) {
                    st.statusLabel = "Connecting...";
                    int addrHint = (prefs != null) ? prefs.getBvmAddrType(i) : -1;
                    bleClient.connectDevice(st.mac, addrHint, "fff0");
                }
            }
        }
        notifyUi();
    }

    private void startSupervisorThread() {
        Thread sup = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopWorker.get()) {
                    try {
                        Thread.sleep(6000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    if (!bleClient.isBridgeOpen()) {
                        sessionReady = false;
                        bleClient.connectBridge();
                        continue;
                    }
                    if (uiForeground || keepAliveBackground) {
                        ensureConfiguredDevicesConnected();
                    }
                }
            }
        }, "ObdBleSupervisor");
        sup.setDaemon(true);
        sup.start();
    }

    private synchronized void startObdPollWorkerIfNeeded() {
        if (testMode) return;
        if (pollThread != null && pollThread.isAlive()) return;
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runObdPollLoop();
            }
        }, "ObdElmPollWorker");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void runObdPollLoop() {
        DiagLogger.get().info("OBD", "Starting ELM327 session init on " + obdMac);
        // Brief settle after ENABLE_NOTIFY CCCD write
        sleepQuiet(250L);

        String ver = sendElmCommandBlocking("ATI", 2200L);
        if (ver == null || ver.isEmpty()) {
            ver = sendElmCommandBlocking("ATZ", 2500L);
        }
        if (ver != null && !ver.isEmpty()) {
            String cleanVer = ver.replace('\r', ' ').replace('\n', ' ').trim();
            if (cleanVer.length() > 28) cleanVer = cleanVer.substring(0, 28);
            elmVersionText = cleanVer;
            DiagLogger.get().info("OBD", "Adapter identified: " + cleanVer);
        }

        runElmInitSequence();

        String currentCanHeader = "";
        int cycle = 0;
        int consecutiveNoData = 0;
        long rateWindowStart = System.currentTimeMillis();
        int rateCommands = 0;

        while (obdConnected && obdBinding != null && !stopWorker.get()) {
            // Pause internal polling while a 3rd-party Wi-Fi ELM327 TCP client is connected
            if (bridgeClientCount > 0) {
                pollHz = 0.0f;
                obdStatusText = "TCP Bridge Active";
                notifyUi();
                sleepQuiet(250L);
                continue;
            }

            // If Neither UI nor background bridge needs active polling, idle gently
            if (!uiForeground && !keepAliveBackground) {
                pollHz = 0.0f;
                sleepQuiet(800L);
                continue;
            }

            if (needReinitAfterBridge.compareAndSet(true, false)) {
                DiagLogger.get().info("OBD", "TCP bridge client disconnected; restoring E-GMP CAN framing");
                runElmInitSequence();
                currentCanHeader = "";
            }

            // 1. Priority: Parked-only DTC Clear
            DtcCallback clearCb = pendingDtcClear;
            if (clearCb != null) {
                pendingDtcClear = null;
                EgmpTelemetry.Snapshot snap = telemetry.snapshot();
                if (snap.speedKmh > 0 || ("D".equals(snap.gear) || "R".equals(snap.gear))) {
                    clearCb.onDtcReport("REFUSED: Vehicle must be stationary in Park (P/N) with speed = 0 to clear DTCs.");
                } else {
                    currentCanHeader = switchBroadcastHeader(currentCanHeader);
                    sendElmCommandBlocking("04", 3000L);
                    String scanAfter = executeFullDtcScan();
                    clearCb.onDtcReport("Mode 04 Clear DTC command sent.\n\nPost-clear verification:\n" + scanAfter);
                }
                continue;
            }

            // 2. Priority: Stored / Pending / Permanent DTC Scan
            DtcCallback dtcCb = pendingDtcScan;
            if (dtcCb != null) {
                pendingDtcScan = null;
                currentCanHeader = switchBroadcastHeader(currentCanHeader);
                String report = executeFullDtcScan();
                currentCanHeader = "";
                dtcCb.onDtcReport(report);
                continue;
            }

            // 3. Priority: Full 192/180/144-Cell Voltage Sweep
            if (pendingCellScan.compareAndSet(true, false)) {
                currentCanHeader = switchTargetEcu(currentCanHeader, "7E4", "7EC");
                String[] cellDids = (vehicleProfile == EgmpTelemetry.PROFILE_EGMP_144)
                        ? new String[] { "220102", "220103", "220104", "22010A", "22010B" }
                        : new String[] { "220102", "220103", "220104", "22010A", "22010B", "22010C" };
                for (int i = 0; i < cellDids.length && obdConnected && bridgeClientCount == 0; i++) {
                    queryIsoTpAndApply(cellDids[i], 1400L);
                }
                notifyUi();
            }

            // 4. Cyclic Telemetry Schedule
            boolean gotData = false;
            if (vehicleProfile == EgmpTelemetry.PROFILE_GENERIC) {
                if (!"".equals(currentCanHeader)) {
                    sendElmCommandBlocking("ATSH7DF", 800L);
                    sendElmCommandBlocking("ATAR", 800L);
                    currentCanHeader = "";
                }
                gotData |= queryIsoTpAndApply("010D", 900L);
                gotData |= queryIsoTpAndApply("010C", 900L);
                if ((cycle % 3) == 0) {
                    gotData |= queryIsoTpAndApply("0105", 900L);
                    gotData |= queryIsoTpAndApply("0142", 900L);
                    String rv = sendElmCommandBlocking("ATRV", 800L);
                    telemetry.updateAtRvVolts(Elm327Protocol.parseAtRvVolts(rv));
                }
                rateCommands += 2;
            } else {
                // E-GMP Fast Cycle: BMS 220101 + ABS 220104
                currentCanHeader = switchTargetEcu(currentCanHeader, "7E4", "7EC");
                gotData |= queryIsoTpAndApply("220101", 1300L);
                rateCommands++;

                currentCanHeader = switchTargetEcu(currentCanHeader, "7D1", "7D9");
                gotData |= queryIsoTpAndApply("220104", 900L);
                rateCommands++;

                // E-GMP Medium Cycle: BMS 220105, 220106 + ICCU LDC 22E011 (or MY25 22E101)
                if ((cycle % 3) == 0 && bridgeClientCount == 0) {
                    currentCanHeader = switchTargetEcu(currentCanHeader, "7E4", "7EC");
                    gotData |= queryIsoTpAndApply("220105", 1300L);
                    gotData |= queryIsoTpAndApply("220106", 1100L);

                    currentCanHeader = switchTargetEcu(currentCanHeader, "7E5", "7ED");
                    String iccuCmd = iccuUseMy25Did ? "22E101" : "22E011";
                    boolean iccuOk = queryIsoTpAndApply(iccuCmd, 1100L);
                    if (!iccuOk) {
                        iccuUseMy25Did = !iccuUseMy25Did;
                    }
                    gotData |= iccuOk;
                    rateCommands += 3;
                }

                // E-GMP Slow Cycle: TPMS 22C00B + Cluster Odometer 22B002 + Adapter ATRV
                if ((cycle % 10) == 0 && bridgeClientCount == 0) {
                    currentCanHeader = switchTargetEcu(currentCanHeader, "7A0", "7A8");
                    gotData |= queryIsoTpAndApply("22C00B", 1100L);

                    currentCanHeader = switchTargetEcu(currentCanHeader, "7C6", "7CE");
                    gotData |= queryIsoTpAndApply("22B002", 1000L);

                    String rv = sendElmCommandBlocking("ATRV", 800L);
                    telemetry.updateAtRvVolts(Elm327Protocol.parseAtRvVolts(rv));
                    rateCommands += 3;
                }
            }

            cycle++;
            long now = System.currentTimeMillis();
            long elapsed = now - rateWindowStart;
            if (elapsed >= 2000L) {
                pollHz = (rateCommands * 1000.0f) / (float) elapsed;
                rateWindowStart = now;
                rateCommands = 0;
            }

            if (gotData) {
                consecutiveNoData = 0;
                obdStatusText = "Online (" + shortLabel(obdName, obdMac) + ")";
                notifyUi();
                sleepQuiet(60L);
            } else {
                consecutiveNoData++;
                if (consecutiveNoData >= 4) {
                    // Vehicle CAN is asleep (IG OFF) — back off to 5s standby so 12V battery rests
                    pollHz = 0.2f;
                    obdStatusText = "Standby (IG OFF)";
                    notifyUi();
                    sleepQuiet(5000L);
                } else {
                    notifyUi();
                    sleepQuiet(250L);
                }
            }
        }
        pollHz = 0.0f;
    }

    private void runElmInitSequence() {
        for (int i = 0; i < Elm327Protocol.EGMP_INIT_COMMANDS.length; i++) {
            if (!obdConnected || bridgeClientCount > 0) break;
            sendElmCommandBlocking(Elm327Protocol.EGMP_INIT_COMMANDS[i], 1000L);
        }
    }

    private String switchTargetEcu(String currentHeader, String txHeader, String rxFilter) {
        if (txHeader.equals(currentHeader)) {
            return currentHeader;
        }
        sendElmCommandBlocking("ATSH" + txHeader, 800L);
        sendElmCommandBlocking("ATCRA" + rxFilter, 800L);
        return txHeader;
    }

    private String switchBroadcastHeader(String currentHeader) {
        if ("7DF".equals(currentHeader)) {
            return currentHeader;
        }
        sendElmCommandBlocking("ATSH7DF", 800L);
        sendElmCommandBlocking("ATAR", 800L);
        return "7DF";
    }

    private String executeFullDtcScan() {
        List<ObdDtcCodec.DtcEntry> all = new ArrayList<ObdDtcCodec.DtcEntry>();
        String[] modes = { "03", "07", "0A" };
        for (int i = 0; i < modes.length; i++) {
            String raw = sendElmCommandBlocking(modes[i], 2500L);
            if (raw != null && !Elm327Protocol.isErrorOrNoData(raw)) {
                List<Elm327Protocol.IsoTpMessage> msgs = Elm327Protocol.parseIsoTpMessages(raw, true);
                all.addAll(ObdDtcCodec.parseDtcResponses(msgs));
            }
        }
        return ObdDtcCodec.formatReport(all);
    }

    private boolean queryIsoTpAndApply(String cmd, long timeoutMs) {
        if (!Elm327Protocol.isSafeInternalCommand(cmd)) {
            DiagLogger.get().warn("OBD", "Blocked unsafe internal command: " + cmd);
            return false;
        }
        String raw = sendElmCommandBlocking(cmd, timeoutMs);
        if (raw == null || Elm327Protocol.isErrorOrNoData(raw)) {
            return false;
        }
        List<Elm327Protocol.IsoTpMessage> msgs = Elm327Protocol.parseIsoTpMessages(raw, true);
        return telemetry.applyMessages(msgs);
    }

    private String sendElmCommandBlocking(String cmd, long timeoutMs) {
        Elm327Protocol.GattBinding binding = this.obdBinding;
        String mac = this.obdMac;
        if (!obdConnected || binding == null || mac.isEmpty() || bridgeClientCount > 0) {
            return null;
        }
        synchronized (promptLock) {
            latestPromptResponse = null;
            promptBuffer.reset();
        }
        List<byte[]> chunks = Elm327Protocol.encodeCommandChunks(cmd, obdChunkBytes);
        for (int i = 0; i < chunks.size(); i++) {
            bleClient.writeCharacteristic(mac, binding.writeUuid, chunks.get(i), binding.writeWithoutResponse);
        }
        long deadline = System.currentTimeMillis() + Math.max(200L, timeoutMs);
        synchronized (promptLock) {
            while (latestPromptResponse == null && obdConnected && bridgeClientCount == 0) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0L) break;
                try {
                    promptLock.wait(rem);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return latestPromptResponse;
        }
    }

    private void writeBvmCommand(String mac, byte[] frame) {
        List<byte[]> chunks = Bvm02Protocol.splitIntoBleChunks(frame, 20);
        for (int i = 0; i < chunks.size(); i++) {
            bleClient.writeCharacteristic(mac, Bvm02Protocol.UUID_CMD_WRITE_STR, chunks.get(i), false);
        }
    }

    private BvmSlotState findBvmSlot(String mac) {
        if (mac == null || mac.isEmpty()) return null;
        for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
            if (mac.equalsIgnoreCase(bvmSlots[i].mac)) {
                return bvmSlots[i];
            }
        }
        return null;
    }

    private static String shortLabel(String name, String mac) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return mac;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ================= @GEN5W_BLE CALLBACK ROUTER =================

    private final class BleCallback extends Gen5wBleClient.SimpleListener {

        @Override
        public void onSessionReady(String serverVersion) {
            sessionReady = true;
            bridgeServerVersion = serverVersion;
            DiagLogger.get().info("BLE", "Connected to @gen5w_ble server (" + serverVersion + ")");
            ensureConfiguredDevicesConnected();
            notifyUi();
        }

        @Override
        public void onScanStateChanged(boolean isScanning, String error) {
            scanning = isScanning;
            if (error != null) {
                DiagLogger.get().warn("BLE", "Scan state changed: scanning=" + isScanning + " err=" + error);
            }
            notifyUi();
        }

        @Override
        public void onAdvertisement(String mac, int addrType, int rssi, Gen5wBleAdvRecord record) {
            if (mac == null) return;
            String upperMac = mac.toUpperCase(Locale.US);
            if (upperMac.equals(obdMac)) {
                obdLastRssi = rssi;
            }
            String localName = (record != null && record.localName != null && !record.localName.isEmpty())
                    ? record.localName
                    : "BLE Device";
            List<String> uuids = (record != null) ? record.serviceUuids : Collections.<String>emptyList();
            byte[] rawAd = (record != null) ? record.rawBytes : null;

            boolean isBvm = Bvm02Protocol.isLikelyBvm02Device(localName, rawAd)
                    && !Elm327Protocol.isLikelyObdAdapter(localName, Collections.<String>emptyList());
            boolean isObd = Elm327Protocol.isLikelyObdAdapter(localName, uuids);

            StringBuilder uSummary = new StringBuilder();
            for (int i = 0; i < uuids.size() && i < 2; i++) {
                String u = uuids.get(i);
                if (u.startsWith("0000") && u.length() >= 8) {
                    u = u.substring(4, 8);
                } else if (u.length() > 8) {
                    u = u.substring(0, 8);
                }
                if (uSummary.length() > 0) uSummary.append(',');
                uSummary.append(u);
            }

            DiscoveredDevice d = new DiscoveredDevice(
                    upperMac,
                    addrType,
                    rssi,
                    localName,
                    uSummary.length() == 0 ? "-" : uSummary.toString(),
                    isObd,
                    isBvm,
                    System.currentTimeMillis());
            synchronized (discovered) {
                discovered.put(upperMac, d);
            }
            notifyUi();
        }

        @Override
        public void onConnecting(String mac) {
            if (mac != null && mac.equalsIgnoreCase(obdMac)) {
                obdConnecting = true;
                obdStatusText = "Connecting...";
                notifyUi();
            }
        }

        @Override
        public void onConnected(String mac, Map<String, Integer> charPropsByUuid) {
            if (mac == null) return;
            String upperMac = mac.toUpperCase(Locale.US);

            // 1. Primary OBD-II Adapter Session
            if (upperMac.equals(obdMac)) {
                obdConnecting = false;
                Elm327Protocol.GattBinding binding = Elm327Protocol.resolveAdapterBinding(charPropsByUuid);
                if (binding == null) {
                    obdConnected = false;
                    obdStatusText = "Incompatible GATT";
                    DiagLogger.get().error("OBD", "Rejected GATT table on " + upperMac + " (no ELM327 notify/write pair)");
                    bleClient.disconnectDevice(upperMac);
                    notifyUi();
                    return;
                }
                obdBinding = binding;
                obdChunkBytes = 20;
                obdConnected = true;
                obdStatusText = "GATT Ready (" + binding.profileName + ")";
                DiagLogger.get().info("OBD", "Bound " + upperMac + " -> " + binding.profileName
                        + " notify=" + binding.notifyUuid + " write=" + binding.writeUuid);

                bleClient.requestMtu(upperMac, 185);
                bleClient.enableNotifications(upperMac, binding.notifyUuid);
                startObdPollWorkerIfNeeded();
                notifyUi();
                return;
            }

            // 2. Optional Concurrent BVM02 12V Sensor Session
            BvmSlotState bvm = findBvmSlot(upperMac);
            if (bvm != null) {
                bvm.connected = true;
                bvm.statusLabel = "Online";
                bvm.assembler.reset();
                // Hardware safety guardrail: block writes to 0xFFF1 firmware characteristic
                bleClient.protectCharacteristic(upperMac, Bvm02Protocol.UUID_VERSION_INFO_STR);
                bleClient.enableNotifications(upperMac, Bvm02Protocol.UUID_READ_VOLTAGE_STR);
                bleClient.enableNotifications(upperMac, Bvm02Protocol.UUID_CMD_NOTIFY_STR);
                bleClient.readCharacteristic(upperMac, Bvm02Protocol.UUID_VERSION_INFO_STR);
                writeBvmCommand(upperMac, Bvm02Protocol.buildSyncTimeCmd(System.currentTimeMillis(), false));
                DiagLogger.get().info("BVM02", "Connected concurrent 12V sensor Slot " + (bvm.slot + 1) + " (" + upperMac + ")");
                notifyUi();
            }
        }

        @Override
        public void onDisconnected(String mac, int status, String reason) {
            if (mac == null) return;
            String upperMac = mac.toUpperCase(Locale.US);
            if (upperMac.equals(obdMac)) {
                obdConnecting = false;
                obdConnected = false;
                obdBinding = null;
                pollHz = 0.0f;
                obdStatusText = "Disconnected (" + reason + ")";
                synchronized (promptLock) {
                    promptLock.notifyAll();
                }
                DiagLogger.get().warn("OBD", "OBD adapter disconnected: " + upperMac + " reason=" + reason);
                notifyUi();
                return;
            }
            BvmSlotState bvm = findBvmSlot(upperMac);
            if (bvm != null) {
                bvm.connected = false;
                bvm.statusLabel = "Disconnected";
                DiagLogger.get().warn("BVM02", "BVM02 Slot " + (bvm.slot + 1) + " disconnected: " + reason);
                notifyUi();
            }
        }

        @Override
        public void onMtuChanged(String mac, int mtu, String error) {
            if (mac != null && mac.equalsIgnoreCase(obdMac) && error == null && mtu >= 23) {
                obdChunkBytes = Math.min(180, Math.max(20, mtu - 3));
                DiagLogger.get().info("OBD", "Negotiated ATT MTU=" + mtu + " (write chunk=" + obdChunkBytes + " B)");
            }
        }

        @Override
        public void onCharacteristicRead(String mac, String uuid, byte[] value, String error) {
            if (mac == null || value == null || error != null) return;
            String normU = Gen5wBleAdvRecord.normalizeUuid(uuid);
            BvmSlotState bvm = findBvmSlot(mac.toUpperCase(Locale.US));
            if (bvm != null && Gen5wBleAdvRecord.normalizeUuid(Bvm02Protocol.UUID_VERSION_INFO_STR).equals(normU)) {
                Bvm02Protocol.VersionInfo vi = Bvm02Protocol.parseVersionInfo(value);
                if (vi != null && vi.firmwareVersion > 0.0f) {
                    bvm.firmwareVersion = vi.firmwareVersion;
                    notifyUi();
                }
            }
        }

        @Override
        public void onCharacteristicChanged(String mac, String uuid, byte[] value) {
            if (mac == null || value == null || value.length == 0) return;
            String upperMac = mac.toUpperCase(Locale.US);
            String normU = Gen5wBleAdvRecord.normalizeUuid(uuid);

            // Route 1: Primary OBD-II Adapter Notifications
            Elm327Protocol.GattBinding binding = obdBinding;
            if (upperMac.equals(obdMac) && binding != null
                    && Gen5wBleAdvRecord.normalizeUuid(binding.notifyUuid).equals(normU)) {
                RawByteSink rawSink = bridgeRawSink;
                if (rawSink != null && bridgeClientCount > 0) {
                    rawSink.onAdapterBytes(value);
                    return;
                }
                List<String> completed = promptBuffer.feed(value);
                if (!completed.isEmpty()) {
                    synchronized (promptLock) {
                        latestPromptResponse = completed.get(completed.size() - 1);
                        promptLock.notifyAll();
                    }
                }
                return;
            }

            // Route 2: Optional Concurrent BVM02 12V Battery Monitor Notifications
            BvmSlotState bvm = findBvmSlot(upperMac);
            if (bvm != null) {
                if (Gen5wBleAdvRecord.normalizeUuid(Bvm02Protocol.UUID_READ_VOLTAGE_STR).equals(normU)) {
                    Bvm02Protocol.RealtimeReading r = Bvm02Protocol.parseRealtimeVoltage(value);
                    if (r != null) {
                        bvm.centivolts = r.centivolts;
                        bvm.volts = r.volts;
                        bvm.tempC = r.tempCelsius;
                        bvm.hasTemp = r.hasTemp;
                        bvm.is24V = r.hardwareIs24V;
                        bvm.socPct = Bvm02Protocol.computeStateOfChargePercent(r.centivolts, r.hardwareIs24V);
                        bvm.statusLabel = Bvm02Protocol.classifyVoltageStatus(r.centivolts, r.hardwareIs24V).label;
                        bvm.pushSample(r.volts);
                        DiagLogger.get().recordBvm02Reading(bvm.slot, bvm.mac, r.volts, bvm.socPct, r.tempCelsius, r.hasTemp);
                        notifyUi();
                    }
                } else if (Gen5wBleAdvRecord.normalizeUuid(Bvm02Protocol.UUID_CMD_NOTIFY_STR).equals(normU)) {
                    List<Bvm02Protocol.DecodedFrame> frames = bvm.assembler.feed(value);
                    for (int i = 0; i < frames.size(); i++) {
                        Bvm02Protocol.DecodedFrame df = frames.get(i);
                        if (df.cmd == Bvm02Protocol.CMD_GET_HISTORY_V1 || df.cmd == Bvm02Protocol.CMD_GET_HISTORY_V2) {
                            // Extract 2-byte centivolt history samples into sparkline if present
                            for (int off = 6; off + 1 < df.payload.length; off += 2) {
                                int cv = ((df.payload[off] & 0xFF) << 8) | (df.payload[off + 1] & 0xFF);
                                if (cv >= 600 && cv <= 3200) {
                                    bvm.pushSample(cv / 100.0f);
                                }
                            }
                            notifyUi();
                        }
                    }
                }
            }
        }

        @Override
        public void onSessionClosed(String reason) {
            sessionReady = false;
            obdConnected = false;
            obdConnecting = false;
            pollHz = 0.0f;
            DiagLogger.get().warn("BLE", "@gen5w_ble session closed: " + reason);
            notifyUi();
        }
    }
}
