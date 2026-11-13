package obd.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Primary 24:9 widescreen cockpit Activity for OBD-II & E-GMP Telemetry on Gen5W (API 19).
 */
public final class MainActivity extends Activity implements BleCoordinator.UiListener {

    private static final int TAB_EV    = 0;
    private static final int TAB_CELLS = 1;
    private static final int TAB_12V   = 2;
    private static final int TAB_DTC   = 3;
    private static final int TAB_BLE   = 4;

    private static final int[] CANDIDATE_BRIDGE_PORTS = { 35000, 35001, 23000 };

    private DevicePrefs prefs;
    private BleCoordinator coordinator;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int activeTab = TAB_EV;
    // 0 = Primary OBD, 1..4 = BVM02 Slot 1..4
    private int pairTargetRole = 0;
    private HmgapidClient.ConditioningStatus lastCondStatus =
            new HmgapidClient.ConditioningStatus(false, false, true, false, "PROBING", "Probing @hmgapid...");
    private long lastGraphSampleMs = 0L;

    // Sidebar & Header Views
    private TextView tvAppVersion;
    private TextView tvBadgeObd;
    private TextView tvBadgeBridge;
    private TextView tvBadgeBvm;
    private Button btnNavEv;
    private Button btnNavCells;
    private Button btnNav12v;
    private Button btnNavDtc;
    private Button btnNavBle;
    private TextView tvTopVehicleProfile;
    private TextView tvTopPollStats;

    // Panels
    private View panelEv;
    private View panelCells;
    private View panel12v;
    private View panelDtc;
    private View panelBle;

    // Panel 1: EV Live
    private TextView tvEvPowerKw;
    private TextView tvEvPowerSub;
    private TextView tvEvSocDisp;
    private TextView tvEvSocSub;
    private TextView tvEvPackVi;
    private TextView tvEvPackSub;
    private TextView tvEvTempMinMax;
    private TextView tvEvDriveSub;
    private TelemetryGraphView graphEvTelemetry;
    private TextView tvPrecondStatus;
    private TextView tvPrecondDetail;
    private LinearLayout llPrecondButtons;
    private Button btnPrecondStart;
    private Button btnPrecondWarm;
    private Button btnPrecondStop;

    // Panel 2: Cells & Thermal
    private TextView tvCellsSummary;
    private Button btnCycleProfile;
    private CellHeatmapView heatmapCells;
    private TextView tvModuleTemps;
    private TextView tvEnergyCounters;

    // Panel 3: 12V / ICCU & BVM02
    private TextView tvIccuLdcMain;
    private TextView tvIccuLdcSub;
    private TextView tvAux12Sensor;
    private TextView tvTpmsFl;
    private TextView tvTpmsFr;
    private TextView tvTpmsRl;
    private TextView tvTpmsRr;
    private TextView tvBvmSectionStatus;
    private Button btnToggleBvm;
    private Button btnBvmSyncHistory;
    private final TextView[] tvBvmSlots = new TextView[DevicePrefs.BVM_SLOTS];
    private VoltageSparklineView sparkBvm12v;

    // Panel 4: DTC & Wi-Fi Bridge
    private TextView tvDtcResults;
    private TextView tvBridgeDetail;
    private TextView tvBridgeCounters;
    private Button btnBridgeToggle;
    private Button btnBridgePort;

    // Panel 5: BLE & Logs
    private Button btnBleScan;
    private Button btnBleTargetRole;
    private TextView tvBlePairedSummary;
    private ScanListAdapter scanAdapter;
    private Button btnUnitTemp;
    private Button btnUnitDist;
    private Button btnUnitPress;
    private TextView tvDiagLog;

    private final Runnable periodicUiTick = new Runnable() {
        @Override
        public void run() {
            renderAll();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable periodicHmgPoll = new Runnable() {
        @Override
        public void run() {
            probeHmgConditioningAsync();
            uiHandler.postDelayed(this, 8000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new DevicePrefs(this);
        coordinator = BleCoordinator.getInstance(this);

        bindViews();
        wireActions();
        switchTab(TAB_EV);
        renderAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        coordinator.addUiListener(this);
        coordinator.setUiForeground(true);
        uiHandler.post(periodicUiTick);
        uiHandler.post(periodicHmgPoll);
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(periodicUiTick);
        uiHandler.removeCallbacks(periodicHmgPoll);
        coordinator.removeUiListener(this);
        coordinator.setUiForeground(false);
        super.onStop();
    }

    @Override
    public void onStateUpdated() {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                renderAll();
            }
        });
    }

    private void bindViews() {
        tvAppVersion = (TextView) findViewById(R.id.tv_app_version);
        tvAppVersion.setText("v" + AppVersion.APP_VERSION);

        tvBadgeObd = (TextView) findViewById(R.id.tv_badge_obd);
        tvBadgeBridge = (TextView) findViewById(R.id.tv_badge_bridge);
        tvBadgeBvm = (TextView) findViewById(R.id.tv_badge_bvm);

        btnNavEv = (Button) findViewById(R.id.btn_nav_ev);
        btnNavCells = (Button) findViewById(R.id.btn_nav_cells);
        btnNav12v = (Button) findViewById(R.id.btn_nav_12v);
        btnNavDtc = (Button) findViewById(R.id.btn_nav_dtc);
        btnNavBle = (Button) findViewById(R.id.btn_nav_ble);

        tvTopVehicleProfile = (TextView) findViewById(R.id.tv_top_vehicle_profile);
        tvTopPollStats = (TextView) findViewById(R.id.tv_top_poll_stats);

        panelEv = findViewById(R.id.panel_ev);
        panelCells = findViewById(R.id.panel_cells);
        panel12v = findViewById(R.id.panel_12v);
        panelDtc = findViewById(R.id.panel_dtc);
        panelBle = findViewById(R.id.panel_ble);

        // EV Live
        tvEvPowerKw = (TextView) findViewById(R.id.tv_ev_power_kw);
        tvEvPowerSub = (TextView) findViewById(R.id.tv_ev_power_sub);
        tvEvSocDisp = (TextView) findViewById(R.id.tv_ev_soc_disp);
        tvEvSocSub = (TextView) findViewById(R.id.tv_ev_soc_sub);
        tvEvPackVi = (TextView) findViewById(R.id.tv_ev_pack_vi);
        tvEvPackSub = (TextView) findViewById(R.id.tv_ev_pack_sub);
        tvEvTempMinMax = (TextView) findViewById(R.id.tv_ev_temp_minmax);
        tvEvDriveSub = (TextView) findViewById(R.id.tv_ev_drive_sub);
        graphEvTelemetry = (TelemetryGraphView) findViewById(R.id.graph_ev_telemetry);
        tvPrecondStatus = (TextView) findViewById(R.id.tv_precond_status);
        tvPrecondDetail = (TextView) findViewById(R.id.tv_precond_detail);
        llPrecondButtons = (LinearLayout) findViewById(R.id.ll_precond_buttons);
        btnPrecondStart = (Button) findViewById(R.id.btn_precond_start);
        btnPrecondWarm = (Button) findViewById(R.id.btn_precond_warm);
        btnPrecondStop = (Button) findViewById(R.id.btn_precond_stop);

        // Cells & Thermal
        tvCellsSummary = (TextView) findViewById(R.id.tv_cells_summary);
        btnCycleProfile = (Button) findViewById(R.id.btn_cycle_profile);
        heatmapCells = (CellHeatmapView) findViewById(R.id.heatmap_cells);
        tvModuleTemps = (TextView) findViewById(R.id.tv_module_temps);
        tvEnergyCounters = (TextView) findViewById(R.id.tv_energy_counters);

        // 12V / ICCU & BVM02
        tvIccuLdcMain = (TextView) findViewById(R.id.tv_iccu_ldc_main);
        tvIccuLdcSub = (TextView) findViewById(R.id.tv_iccu_ldc_sub);
        tvAux12Sensor = (TextView) findViewById(R.id.tv_aux12_sensor);
        tvTpmsFl = (TextView) findViewById(R.id.tv_tpms_fl);
        tvTpmsFr = (TextView) findViewById(R.id.tv_tpms_fr);
        tvTpmsRl = (TextView) findViewById(R.id.tv_tpms_rl);
        tvTpmsRr = (TextView) findViewById(R.id.tv_tpms_rr);
        tvBvmSectionStatus = (TextView) findViewById(R.id.tv_bvm_section_status);
        btnToggleBvm = (Button) findViewById(R.id.btn_toggle_bvm);
        btnBvmSyncHistory = (Button) findViewById(R.id.btn_bvm_sync_history);
        tvBvmSlots[0] = (TextView) findViewById(R.id.tv_bvm_slot1);
        tvBvmSlots[1] = (TextView) findViewById(R.id.tv_bvm_slot2);
        tvBvmSlots[2] = (TextView) findViewById(R.id.tv_bvm_slot3);
        tvBvmSlots[3] = (TextView) findViewById(R.id.tv_bvm_slot4);
        sparkBvm12v = (VoltageSparklineView) findViewById(R.id.spark_bvm_12v);

        // DTC & Wi-Fi Bridge
        tvDtcResults = (TextView) findViewById(R.id.tv_dtc_results);
        tvBridgeDetail = (TextView) findViewById(R.id.tv_bridge_detail);
        tvBridgeCounters = (TextView) findViewById(R.id.tv_bridge_counters);
        btnBridgeToggle = (Button) findViewById(R.id.btn_bridge_toggle);
        btnBridgePort = (Button) findViewById(R.id.btn_bridge_port);

        // BLE & Logs
        btnBleScan = (Button) findViewById(R.id.btn_ble_scan);
        btnBleTargetRole = (Button) findViewById(R.id.btn_ble_target_role);
        tvBlePairedSummary = (TextView) findViewById(R.id.tv_ble_paired_summary);
        ListView lvScan = (ListView) findViewById(R.id.lv_scan_devices);
        scanAdapter = new ScanListAdapter();
        lvScan.setAdapter(scanAdapter);
        lvScan.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BleCoordinator.DiscoveredDevice d = scanAdapter.getItem(position);
                if (d == null) return;
                handleDeviceSelected(d);
            }
        });

        btnUnitTemp = (Button) findViewById(R.id.btn_unit_temp);
        btnUnitDist = (Button) findViewById(R.id.btn_unit_dist);
        btnUnitPress = (Button) findViewById(R.id.btn_unit_press);
        tvDiagLog = (TextView) findViewById(R.id.tv_diag_log);
    }

    private void wireActions() {
        findViewById(R.id.ll_sidebar_header).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ScreenshotHelper.captureToUsbAsync(MainActivity.this, new ScreenshotHelper.Callback() {
                    @Override
                    public void onResult(boolean ok, String message) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnNavEv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(TAB_EV); }
        });
        btnNavCells.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(TAB_CELLS); }
        });
        btnNav12v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(TAB_12V); }
        });
        btnNavDtc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(TAB_DTC); }
        });
        btnNavBle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(TAB_BLE); }
        });

        findViewById(R.id.btn_export_usb).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogsToUsbAsync();
            }
        });

        findViewById(R.id.btn_exit_app).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Preconditioning buttons
        btnPrecondStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendConditioningCmdAsync("start", false); }
        });
        btnPrecondWarm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendConditioningCmdAsync("start", true); }
        });
        btnPrecondStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendConditioningCmdAsync("stop", false); }
        });

        // Cells & Profile
        findViewById(R.id.btn_scan_cells).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!coordinator.isObdConnected()) {
                    Toast.makeText(MainActivity.this, "Connect BLE OBD-II adapter first", Toast.LENGTH_SHORT).show();
                    return;
                }
                coordinator.requestCellScan();
                Toast.makeText(MainActivity.this, "Scanning all HV cell blocks...", Toast.LENGTH_SHORT).show();
            }
        });

        btnCycleProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (coordinator.getVehicleProfile() + 1) % 4;
                coordinator.setVehicleProfile(next);
                renderAll();
            }
        });

        // BVM02 Optional 12V Monitor controls
        btnToggleBvm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !coordinator.isBvmEnabled();
                coordinator.setBvmEnabled(next);
                renderAll();
            }
        });

        btnBvmSyncHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                coordinator.syncBvm02History();
                Toast.makeText(MainActivity.this, "Requested BVM02 history sync", Toast.LENGTH_SHORT).show();
            }
        });

        // DTC & Bridge controls
        findViewById(R.id.btn_scan_dtc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!coordinator.isObdConnected()) {
                    tvDtcResults.setText("OBD-II adapter is not connected. Pair your Veepeak OBDCheck BLE on the 'BLE & Logs' tab.");
                    return;
                }
                tvDtcResults.setText("Scanning Mode 03 (Stored), Mode 07 (Pending), and Mode 0A (Permanent) DTCs across all ECUs...");
                coordinator.requestDtcScan(new BleCoordinator.DtcCallback() {
                    @Override
                    public void onDtcReport(final String reportText) {
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvDtcResults.setText(reportText);
                            }
                        });
                    }
                });
            }
        });

        findViewById(R.id.btn_clear_dtc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearDtcs();
            }
        });

        btnBridgeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWifiBridgeService();
            }
        });

        btnBridgePort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = prefs.getBridgePort();
                int next = CANDIDATE_BRIDGE_PORTS[0];
                for (int i = 0; i < CANDIDATE_BRIDGE_PORTS.length; i++) {
                    if (CANDIDATE_BRIDGE_PORTS[i] == cur) {
                        next = CANDIDATE_BRIDGE_PORTS[(i + 1) % CANDIDATE_BRIDGE_PORTS.length];
                        break;
                    }
                }
                prefs.setBridgePort(next);
                if (WifiElmBridgeService.isBridgeRunning()) {
                    Intent restart = new Intent(MainActivity.this, WifiElmBridgeService.class);
                    restart.setAction(WifiElmBridgeService.ACTION_START);
                    restart.putExtra(WifiElmBridgeService.EXTRA_PORT, next);
                    startService(restart);
                }
                renderAll();
            }
        });

        // BLE & Preferences controls
        btnBleScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (coordinator.isScanning()) {
                    coordinator.stopBleScan();
                } else {
                    coordinator.startBleScan();
                }
            }
        });

        findViewById(R.id.btn_ble_reconnect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                coordinator.requestReconnectAll();
                Toast.makeText(MainActivity.this, "Reconnecting paired BLE devices...", Toast.LENGTH_SHORT).show();
            }
        });

        btnBleTargetRole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pairTargetRole = (pairTargetRole + 1) % 5;
                renderAll();
            }
        });

        findViewById(R.id.btn_ble_unpair_obd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pairTargetRole == 0) {
                    coordinator.clearObdAdapter();
                    Toast.makeText(MainActivity.this, "Cleared primary OBD-II pairing", Toast.LENGTH_SHORT).show();
                } else {
                    int slot = pairTargetRole - 1;
                    prefs.clearBvmSensor(slot);
                    coordinator.reloadFromPrefs();
                    Toast.makeText(MainActivity.this, "Cleared BVM02 Slot " + pairTargetRole, Toast.LENGTH_SHORT).show();
                }
                renderAll();
            }
        });

        btnUnitTemp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setFahrenheit(!prefs.isFahrenheit());
                renderAll();
            }
        });

        btnUnitDist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setMiles(!prefs.isMiles());
                renderAll();
            }
        });

        btnUnitPress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBar(!prefs.isBar());
                renderAll();
            }
        });

        findViewById(R.id.btn_run_btprobe).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBtProbeAsync();
            }
        });

        findViewById(R.id.btn_reboot_hu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRebootHu();
            }
        });
    }

    private void switchTab(int tab) {
        activeTab = tab;
        panelEv.setVisibility(tab == TAB_EV ? View.VISIBLE : View.GONE);
        panelCells.setVisibility(tab == TAB_CELLS ? View.VISIBLE : View.GONE);
        panel12v.setVisibility(tab == TAB_12V ? View.VISIBLE : View.GONE);
        panelDtc.setVisibility(tab == TAB_DTC ? View.VISIBLE : View.GONE);
        panelBle.setVisibility(tab == TAB_BLE ? View.VISIBLE : View.GONE);

        styleNavButton(btnNavEv, tab == TAB_EV);
        styleNavButton(btnNavCells, tab == TAB_CELLS);
        styleNavButton(btnNav12v, tab == TAB_12V);
        styleNavButton(btnNavDtc, tab == TAB_DTC);
        styleNavButton(btnNavBle, tab == TAB_BLE);
        renderAll();
    }

    private void styleNavButton(Button b, boolean selected) {
        b.setBackgroundColor(getResources().getColor(selected ? R.color.btn_nav_active : R.color.btn_nav_idle));
        b.setTextColor(getResources().getColor(selected ? R.color.accent_cyan : R.color.text_primary));
    }

    private void handleDeviceSelected(BleCoordinator.DiscoveredDevice d) {
        if (pairTargetRole == 0) {
            if (d.likelyBvm02 && !d.likelyObd) {
                Toast.makeText(this, "Device looks like an LNEX BVM02 sensor; switch Target to BVM Slot 1-4 to pair.", Toast.LENGTH_LONG).show();
                return;
            }
            coordinator.pairObdAdapter(d.mac, d.name, d.addrType);
            Toast.makeText(this, "Paired OBD-II adapter: " + d.name + " (" + d.mac + ")", Toast.LENGTH_SHORT).show();
        } else {
            int slot = pairTargetRole - 1;
            coordinator.pairBvmSensor(slot, d.mac, d.name, d.addrType);
            Toast.makeText(this, "Paired BVM02 Slot " + pairTargetRole + ": " + d.name, Toast.LENGTH_SHORT).show();
        }
        renderAll();
    }

    private void renderAll() {
        boolean useF = prefs.isFahrenheit();
        boolean useMi = prefs.isMiles();
        boolean useBar = prefs.isBar();
        int profile = coordinator.getVehicleProfile();
        EgmpTelemetry.Snapshot s = coordinator.getTelemetry().snapshot();

        // Sidebar badges & Top strip
        tvBadgeObd.setText("OBD: " + coordinator.getObdStatusText());
        tvBadgeObd.setTextColor(getResources().getColor(
                coordinator.isObdConnected() ? R.color.accent_green : R.color.accent_amber));

        WifiElmBridgeService.LoopbackBridgeServer srv = WifiElmBridgeService.getActiveServer();
        int port = prefs.getBridgePort();
        if (srv != null && srv.isRunning()) {
            tvBadgeBridge.setText(String.format(Locale.US, "Bridge: :%d (%d cli)", srv.getPort(), srv.getClientCount()));
            tvBadgeBridge.setTextColor(getResources().getColor(R.color.accent_green));
        } else {
            tvBadgeBridge.setText("Bridge: OFF (:" + port + ")");
            tvBadgeBridge.setTextColor(getResources().getColor(R.color.text_secondary));
        }

        if (!coordinator.isBvmEnabled()) {
            tvBadgeBvm.setText("BVM02: Optional OFF");
            tvBadgeBvm.setTextColor(getResources().getColor(R.color.text_muted));
        } else {
            int online = 0;
            for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
                if (coordinator.getBvmSlot(i).connected) online++;
            }
            tvBadgeBvm.setText("BVM02: " + online + "/4 Online");
            tvBadgeBvm.setTextColor(getResources().getColor(online > 0 ? R.color.accent_violet : R.color.text_secondary));
        }

        tvTopVehicleProfile.setText(EgmpTelemetry.profileTitle(profile));
        File usbDir = UsbStorageHelper.findWritableLogDir();
        String usbLabel = (usbDir != null) ? usbDir.getAbsolutePath() : "USB not mounted";
        tvTopPollStats.setText(String.format(Locale.US, "@gen5w_ble (%s)  |  %s  |  %.1f Hz  |  %s",
                coordinator.isSessionReady() ? coordinator.getBridgeServerVersion() : "offline",
                coordinator.getElmVersionText(),
                coordinator.getPollHz(),
                usbLabel));

        // Update live EV graph at most once per second when valid HV data exists
        long now = System.currentTimeMillis();
        if (!Float.isNaN(s.packPowerKw) && (now - lastGraphSampleMs >= 950L)) {
            lastGraphSampleMs = now;
            graphEvTelemetry.pushSample(s.packPowerKw, s.packVolts);
        }

        // Panel 1: EV Live
        if (!Float.isNaN(s.packPowerKw)) {
            tvEvPowerKw.setText(String.format(Locale.US, "%+.1f kW", s.packPowerKw));
            tvEvPowerKw.setTextColor(getResources().getColor(
                    s.packPowerKw < -0.5f ? R.color.accent_green : R.color.accent_cyan));
        } else {
            tvEvPowerKw.setText("--.- kW");
        }
        tvEvPowerSub.setText(String.format(Locale.US, "Regen %s kW • Max %s kW",
                Float.isNaN(s.availChargePowerKw) ? "--" : String.format(Locale.US, "%.0f", s.availChargePowerKw),
                Float.isNaN(s.availDischargePowerKw) ? "--" : String.format(Locale.US, "%.0f", s.availDischargePowerKw)));

        float primarySoc = !Float.isNaN(s.socDisplayPct) ? s.socDisplayPct : s.socBmsPct;
        tvEvSocDisp.setText(Float.isNaN(primarySoc) ? "--.- %" : String.format(Locale.US, "%.1f %%", primarySoc));
        tvEvSocSub.setText(String.format(Locale.US, "BMS %s • Rem %s",
                Float.isNaN(s.socBmsPct) ? "-- %" : String.format(Locale.US, "%.1f %%", s.socBmsPct),
                Float.isNaN(s.remainingEnergyKwh) ? "-- kWh" : String.format(Locale.US, "%.1f kWh", s.remainingEnergyKwh)));

        tvEvPackVi.setText(String.format(Locale.US, "%s • %s",
                Float.isNaN(s.packVolts) ? "--- V" : String.format(Locale.US, "%.1f V", s.packVolts),
                Float.isNaN(s.packCurrentA) ? "-- A" : String.format(Locale.US, "%+.1f A", s.packCurrentA)));
        tvEvPackSub.setText(String.format(Locale.US, "Inv %s • Iso %s",
                s.inverterCapVolts < 0 ? "--- V" : (s.inverterCapVolts + " V"),
                s.isolationKohm < 0 ? "---- kOhm" : (s.isolationKohm + " kOhm")));

        if (s.battTempMinC != Integer.MIN_VALUE && s.battTempMaxC != Integer.MIN_VALUE) {
            tvEvTempMinMax.setText(EgmpTelemetry.formatTemp(s.battTempMinC, useF)
                    + " / " + EgmpTelemetry.formatTemp(s.battTempMaxC, useF));
        } else if (s.coolantTempC != Integer.MIN_VALUE) {
            tvEvTempMinMax.setText(EgmpTelemetry.formatTemp(s.coolantTempC, useF));
        } else {
            tvEvTempMinMax.setText("-- / -- " + (useF ? "°F" : "°C"));
        }
        tvEvDriveSub.setText(String.format(Locale.US, "%s • %s • M1 %d / M2 %d",
                s.gear,
                EgmpTelemetry.formatSpeed(s.speedKmh, useMi),
                s.motor1Rpm,
                s.motor2Rpm));

        tvPrecondStatus.setText(String.format(Locale.US, "Thermal: %s  |  PTC: %s",
                s.thermalModeLabel(),
                EgmpTelemetry.formatTemp(s.ptcHeaterTempC, useF)));
        tvPrecondDetail.setText(String.format(Locale.US,
                "Inlet: %s  •  Coolant: %s  •  Fan: %d Hz\nHMG-API (%s): %s",
                EgmpTelemetry.formatTemp(s.battInletTempC, useF),
                EgmpTelemetry.formatTemp(s.coolantTempC, useF),
                s.fanSpeedHz,
                lastCondStatus.stateLabel,
                lastCondStatus.detail));
        llPrecondButtons.setVisibility(lastCondStatus.supported ? View.VISIBLE : View.GONE);

        // Panel 2: Cells & Thermal
        btnCycleProfile.setText("Profile: " + EgmpTelemetry.profileShortLabel(profile));
        tvCellsSummary.setText(String.format(Locale.US,
                "Min %s (#%03d)  •  Max %s (#%03d)  •  dV %s  •  SOH %s",
                Float.isNaN(s.cellMinVolts) ? "--.-- V" : String.format(Locale.US, "%.2f V", s.cellMinVolts),
                s.cellMinIndex,
                Float.isNaN(s.cellMaxVolts) ? "--.-- V" : String.format(Locale.US, "%.2f V", s.cellMaxVolts),
                s.cellMaxIndex,
                s.cellDeltaMv < 0 ? "-- mV" : (s.cellDeltaMv + " mV"),
                Float.isNaN(s.sohPct) ? "---.- %" : String.format(Locale.US, "%.1f %%", s.sohPct)));
        heatmapCells.updateCells(s.cellVoltages, EgmpTelemetry.expectedCellCount(profile));

        StringBuilder modSb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (s.moduleTempsC[i] == Integer.MIN_VALUE) continue;
            if (modSb.length() > 0) modSb.append((i % 4 == 0) ? "\n" : "   ");
            modSb.append(String.format(Locale.US, "M%02d:%s", i + 1, EgmpTelemetry.formatTemp(s.moduleTempsC[i], useF)));
        }
        if (modSb.length() == 0) {
            modSb.append("Waiting for BMS 220101 / 220105 module sensors...");
        }
        modSb.append("\nInlet: ").append(EgmpTelemetry.formatTemp(s.battInletTempC, useF))
             .append("  •  Coolant: ").append(EgmpTelemetry.formatTemp(s.coolantTempC, useF))
             .append("  •  PTC: ").append(EgmpTelemetry.formatTemp(s.ptcHeaterTempC, useF));
        tvModuleTemps.setText(modSb.toString());

        long opHours = (s.operatingTimeSec > 0) ? (s.operatingTimeSec / 3600L) : -1L;
        tvEnergyCounters.setText(String.format(Locale.US,
                "Charged:    %s  (%s)\nDischarged: %s  (%s)\nOp Time:    %s  •  Odometer: %s",
                Float.isNaN(s.cumChargeKwh) ? "-- kWh" : String.format(Locale.US, "%,.1f kWh", s.cumChargeKwh),
                Float.isNaN(s.cumChargeAh) ? "-- Ah" : String.format(Locale.US, "%,.1f Ah", s.cumChargeAh),
                Float.isNaN(s.cumDischargeKwh) ? "-- kWh" : String.format(Locale.US, "%,.1f kWh", s.cumDischargeKwh),
                Float.isNaN(s.cumDischargeAh) ? "-- Ah" : String.format(Locale.US, "%,.1f Ah", s.cumDischargeAh),
                opHours < 0 ? "-- h" : (opHours + " h"),
                EgmpTelemetry.formatDistance(s.odometerKm, s.odometerMiles, useMi)));

        // Panel 3: 12V / ICCU & BVM02
        tvIccuLdcMain.setText(String.format(Locale.US, "%s  •  %s",
                Float.isNaN(s.ldcOutVolts) ? "--.-- V" : String.format(Locale.US, "%.2f V", s.ldcOutVolts),
                Float.isNaN(s.ldcOutAmps) ? "--.- A" : String.format(Locale.US, "%.1f A", s.ldcOutAmps)));
        tvIccuLdcSub.setText(String.format(Locale.US, "LDC Temp: %s  •  HV In: %s  •  BMS Aux: %s",
                EgmpTelemetry.formatTemp(s.ldcTempC, useF),
                Float.isNaN(s.ldcHvInVolts) ? "--- V" : String.format(Locale.US, "%.0f V", s.ldcHvInVolts),
                Float.isNaN(s.bmsAux12Volts) ? "--.- V" : String.format(Locale.US, "%.1f V", s.bmsAux12Volts)));
        float bestAuxV = !Float.isNaN(s.aux12SensorVolts) ? s.aux12SensorVolts : s.adapterAtRvVolts;
        tvAux12Sensor.setText(String.format(Locale.US, "Aux 12V: %s  •  SoC: %s  •  Current: %s",
                Float.isNaN(bestAuxV) ? "--.-- V" : String.format(Locale.US, "%.2f V", bestAuxV),
                s.aux12SocPct < 0 ? "-- %" : (s.aux12SocPct + " %"),
                Float.isNaN(s.aux12CurrentA) ? "--.- A" : String.format(Locale.US, "%+.1f A", s.aux12CurrentA)));

        tvTpmsFl.setText("FL: " + EgmpTelemetry.formatTirePressure(s.tirePsi[0], useBar)
                + "  (" + EgmpTelemetry.formatTemp(s.tireTempC[0], useF) + ")");
        tvTpmsFr.setText("FR: " + EgmpTelemetry.formatTirePressure(s.tirePsi[1], useBar)
                + "  (" + EgmpTelemetry.formatTemp(s.tireTempC[1], useF) + ")");
        tvTpmsRl.setText("RL: " + EgmpTelemetry.formatTirePressure(s.tirePsi[2], useBar)
                + "  (" + EgmpTelemetry.formatTemp(s.tireTempC[2], useF) + ")");
        tvTpmsRr.setText("RR: " + EgmpTelemetry.formatTirePressure(s.tirePsi[3], useBar)
                + "  (" + EgmpTelemetry.formatTemp(s.tireTempC[3], useF) + ")");

        boolean bvmOn = coordinator.isBvmEnabled();
        btnToggleBvm.setText(bvmOn ? "Disable BVM02" : "Enable BVM02");
        btnBvmSyncHistory.setEnabled(bvmOn);
        tvBvmSectionStatus.setText(bvmOn
                ? "CONCURRENT LNEX BVM02 12V BATTERY MONITOR: ENABLED (MULTICONN BLE)"
                : "OPTIONAL LNEX BVM02 12V BATTERY MONITOR: DISABLED (ICCU 12V ACTIVE)");

        BleCoordinator.BvmSlotState primarySparkSlot = null;
        for (int i = 0; i < DevicePrefs.BVM_SLOTS; i++) {
            BleCoordinator.BvmSlotState st = coordinator.getBvmSlot(i);
            if (st.mac.isEmpty()) {
                tvBvmSlots[i].setText("Slot " + (i + 1) + ": Unpaired");
            } else if (!Float.isNaN(st.volts)) {
                String tStr = st.hasTemp ? (" • " + EgmpTelemetry.formatTemp(st.tempC, useF)) : "";
                tvBvmSlots[i].setText(String.format(Locale.US, "S%d %s: %.2f V (%d%%)%s [%s]",
                        i + 1, st.name, st.volts, st.socPct, tStr, st.statusLabel));
                if (primarySparkSlot == null) primarySparkSlot = st;
            } else {
                tvBvmSlots[i].setText(String.format(Locale.US, "S%d %s (%s): %s",
                        i + 1, st.name, st.mac, st.statusLabel));
            }
        }
        if (primarySparkSlot != null) {
            sparkBvm12v.setSamples("BVM02 " + primarySparkSlot.name, primarySparkSlot.copySamples());
        } else if (!Float.isNaN(bestAuxV)) {
            sparkBvm12v.pushFallbackSample("OBD / ICCU Aux 12V", bestAuxV);
        }

        // Panel 4: DTC & Bridge
        btnBridgePort.setText("Port: " + port);
        if (srv != null && srv.isRunning()) {
            tvBridgeDetail.setText("Status: RUNNING (Background Service)  |  127.0.0.1:" + srv.getPort());
            tvBridgeCounters.setText(String.format(Locale.US,
                    "Clients: %d  |  TX: %,d B  |  RX: %,d B  |  Polling: %s",
                    srv.getClientCount(),
                    srv.getTxBytes(),
                    srv.getRxBytes(),
                    srv.getClientCount() > 0 ? "PAUSED FOR CLIENT" : "ACTIVE"));
            btnBridgeToggle.setText("Stop Background Bridge");
            btnBridgeToggle.setTextColor(getResources().getColor(R.color.accent_amber));
        } else {
            tvBridgeDetail.setText("Status: STOPPED  |  Bind target: 127.0.0.1:" + port);
            tvBridgeCounters.setText("Clients: 0  |  Internal E-GMP dashboard polling active");
            btnBridgeToggle.setText("Start Background Bridge (:" + port + ")");
            btnBridgeToggle.setTextColor(getResources().getColor(R.color.accent_green));
        }

        // Panel 5: BLE & Logs
        btnBleScan.setText(coordinator.isScanning() ? "Stop Scan" : "Scan BLE");
        if (pairTargetRole == 0) {
            btnBleTargetRole.setText("Target: OBD-II");
            String om = coordinator.getObdMac();
            tvBlePairedSummary.setText(om.isEmpty()
                    ? "Primary OBD-II: Unpaired — tap Veepeak OBDCheck BLE below to pair."
                    : ("Primary OBD-II: " + coordinator.getObdName() + " [" + om + "]"));
        } else {
            int slot = pairTargetRole - 1;
            btnBleTargetRole.setText("Target: BVM #" + pairTargetRole);
            BleCoordinator.BvmSlotState st = coordinator.getBvmSlot(slot);
            tvBlePairedSummary.setText(st.mac.isEmpty()
                    ? ("BVM02 Slot " + pairTargetRole + ": Unpaired — tap an LNEX BVM02 sensor below to pair.")
                    : ("BVM02 Slot " + pairTargetRole + ": " + st.name + " [" + st.mac + "]"));
        }

        btnUnitTemp.setText("Temp: " + (useF ? "°F" : "°C"));
        btnUnitDist.setText("Dist: " + (useMi ? "mi" : "km"));
        btnUnitPress.setText("Tire: " + (useBar ? "bar" : "PSI"));

        if (activeTab == TAB_BLE) {
            scanAdapter.replaceItems(coordinator.getDiscoveredDevices());
            tvDiagLog.setText(DiagLogger.get().dumpRecentText(80));
        }
    }

    private void toggleWifiBridgeService() {
        Intent svc = new Intent(this, WifiElmBridgeService.class);
        if (WifiElmBridgeService.isBridgeRunning()) {
            svc.setAction(WifiElmBridgeService.ACTION_STOP);
            startService(svc);
            Toast.makeText(this, "Stopping localhost Wi-Fi ELM327 bridge...", Toast.LENGTH_SHORT).show();
        } else {
            int port = prefs.getBridgePort();
            svc.setAction(WifiElmBridgeService.ACTION_START);
            svc.putExtra(WifiElmBridgeService.EXTRA_PORT, port);
            startService(svc);
            Toast.makeText(this, "Bridge active on 127.0.0.1:" + port, Toast.LENGTH_SHORT).show();
        }
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() { renderAll(); }
        }, 250L);
    }

    private void probeHmgConditioningAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final HmgapidClient.ConditioningStatus st = HmgapidClient.queryConditioningStatus();
                coordinator.getTelemetry().applyHmgVehicleSignals(st.signals);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lastCondStatus = st;
                        if (activeTab == TAB_EV || activeTab == TAB_CELLS) {
                            renderAll();
                        }
                    }
                });
            }
        }, "ObdHmgCondProbe");
        t.setDaemon(true);
        t.start();
    }

    private void sendConditioningCmdAsync(final String action, final boolean warm) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    HmgapidClient.Reply r = HmgapidClient.controlConditioning(action, warm);
                    msg = r.ok()
                            ? ("Preconditioning " + action + ": " + (r.text.isEmpty() ? "OK" : r.text))
                            : ("Conditioning refused: " + r.text);
                } catch (Throwable e) {
                    msg = "Conditioning unavailable: " + e.getMessage();
                }
                final String toastMsg = msg;
                final HmgapidClient.ConditioningStatus updated = HmgapidClient.queryConditioningStatus();
                coordinator.getTelemetry().applyHmgVehicleSignals(updated.signals);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lastCondStatus = updated;
                        Toast.makeText(MainActivity.this, toastMsg, Toast.LENGTH_SHORT).show();
                        renderAll();
                    }
                });
            }
        }, "ObdHmgCondAct");
        t.setDaemon(true);
        t.start();
    }

    private void confirmClearDtcs() {
        if (!coordinator.isObdConnected()) {
            Toast.makeText(this, "Connect BLE OBD-II adapter first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear Stored & Pending DTCs?")
                .setMessage("Sends SAE J1979 Mode 04 clear command while stationary in Park (P/N). Freeze-frame data and diagnostic readiness monitors will reset.")
                .setPositiveButton("Clear DTCs", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        tvDtcResults.setText("Sending parked Mode 04 clear request...");
                        coordinator.requestDtcClear(new BleCoordinator.DtcCallback() {
                            @Override
                            public void onDtcReport(final String reportText) {
                                uiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvDtcResults.setText(reportText);
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportLogsToUsbAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    DiagLogger.ExportResult res = DiagLogger.get().exportAllToUsb(coordinator.getTelemetry());
                    StringBuilder sb = new StringBuilder("Exported to USB: ");
                    sb.append(res.diagFile.getName());
                    if (res.egmpCsvFile != null) sb.append(" + ").append(res.egmpCsvFile.getName());
                    if (res.bvmCsvFile != null) sb.append(" + ").append(res.bvmCsvFile.getName());
                    msg = sb.toString();
                } catch (Throwable e) {
                    msg = "USB Export failed: " + e.getMessage();
                }
                final String finalMsg = msg;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, finalMsg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "ObdUsbExport");
        t.setDaemon(true);
        t.start();
    }

    private void runBtProbeAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    coordinator.getBleClient().requestBridgeStatus();
                    HmgapidClient.Reply r = HmgapidClient.runBtProbe(6500);
                    DiagLogger.get().info("BTPROBE", "Exit=" + r.code + " (" + r.text.length() + " chars)");
                    List<String> lines = HmgapidClient.filterBtProbeLines(r.text, 140);
                    for (int i = 0; i < lines.size(); i++) {
                        DiagLogger.get().info("BTPROBE", lines.get(i));
                    }
                } catch (Throwable e) {
                    DiagLogger.get().warn("BTPROBE", "Probe unavailable: " + e.getMessage());
                }
                uiHandler.post(new Runnable() {
                    @Override public void run() { renderAll(); }
                });
            }
        }, "ObdBtProbe");
        t.setDaemon(true);
        t.start();
    }

    private void confirmRebootHu() {
        new AlertDialog.Builder(this)
                .setTitle("Reboot Head Unit?")
                .setMessage("Triggers a clean infotainment head-unit reboot via HMG-API.")
                .setPositiveButton("Reboot", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    HmgapidClient.startService("reboot", 4000);
                                } catch (Throwable ignored) {}
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class ScanListAdapter extends BaseAdapter {
        private final List<BleCoordinator.DiscoveredDevice> items = new ArrayList<BleCoordinator.DiscoveredDevice>();

        void replaceItems(List<BleCoordinator.DiscoveredDevice> next) {
            items.clear();
            if (next != null) {
                items.addAll(next);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public BleCoordinator.DiscoveredDevice getItem(int position) {
            return (position >= 0 && position < items.size()) ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_scan_device, parent, false);
            }
            BleCoordinator.DiscoveredDevice d = getItem(position);
            if (d != null) {
                TextView tvName = (TextView) row.findViewById(R.id.tv_scan_name);
                TextView tvMeta = (TextView) row.findViewById(R.id.tv_scan_meta);
                TextView tvRole = (TextView) row.findViewById(R.id.tv_scan_role);

                tvName.setText(d.name);
                tvMeta.setText(String.format(Locale.US, "%s  |  %d dBm  |  UUID %s",
                        d.mac, d.rssi, d.uuidSummary));
                if (d.likelyObd) {
                    tvRole.setText("OBD-II BLE");
                    tvRole.setTextColor(getResources().getColor(R.color.accent_cyan));
                } else if (d.likelyBvm02) {
                    tvRole.setText("BVM02 12V");
                    tvRole.setTextColor(getResources().getColor(R.color.accent_violet));
                } else {
                    tvRole.setText("PAIR");
                    tvRole.setTextColor(getResources().getColor(R.color.text_secondary));
                }
            }
            return row;
        }
    }
}
