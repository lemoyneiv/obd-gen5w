package obd.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verified Hyundai Ioniq 5 / Kia EV6 / Genesis GV60 (E-GMP) UDS Mode 0x22 & SAE J1979 Mode 0x01
 * telemetry state, deterministic byte-level decoders, pack profiles, and RAM CSV ring buffer.
 *
 * Strictly read-only telemetry: zero VIN queries (0902 / 22F190 omitted) and zero internal flash I/O.
 */
public final class EgmpTelemetry {

    // Vehicle / Pack profiles
    public static final int PROFILE_EGMP_192 = 0; // 77.4 kWh / 84.0 kWh Long Range (192 cells, 32 modules)
    public static final int PROFILE_EGMP_180 = 1; // 72.6 kWh Long Range (180 cells, 30 modules)
    public static final int PROFILE_EGMP_144 = 2; // 58.0 kWh / 63.0 kWh Standard Range (144 cells, 24 modules)
    public static final int PROFILE_GENERIC  = 3; // Standard SAE J1979 Mode 01 OBD-II fallback

    public static final int MAX_CELLS = 192;
    public static final int MAX_CSV_SAMPLES = 3600; // ~1 hour at 1 sample/sec in RAM

    // 11-bit E-GMP CAN Request/Response IDs
    public static final int CAN_BMS_RX     = 0x7EC;
    public static final int CAN_ICCU_RX    = 0x7ED;
    public static final int CAN_TPMS_RX    = 0x7A8;
    public static final int CAN_ABS_RX     = 0x7D9;
    public static final int CAN_CLUSTER_RX = 0x7CE;

    public static String profileTitle(int profile) {
        switch (profile) {
            case PROFILE_EGMP_180:
                return "IONIQ 5 / E-GMP  •  72.6 kWh (180 cells)";
            case PROFILE_EGMP_144:
                return "IONIQ 5 / E-GMP  •  58 / 63 kWh (144 cells)";
            case PROFILE_GENERIC:
                return "GENERIC SAE J1979 OBD-II MODE 01";
            case PROFILE_EGMP_192:
            default:
                return "IONIQ 5 / E-GMP  •  77.4 / 84 kWh (192 cells)";
        }
    }

    public static String profileShortLabel(int profile) {
        switch (profile) {
            case PROFILE_EGMP_180: return "72.6 kWh (180c)";
            case PROFILE_EGMP_144: return "58 kWh (144c)";
            case PROFILE_GENERIC:  return "Generic OBD-II";
            case PROFILE_EGMP_192:
            default:               return "77.4/84 kWh (192c)";
        }
    }

    public static int expectedCellCount(int profile) {
        switch (profile) {
            case PROFILE_EGMP_180: return 180;
            case PROFILE_EGMP_144: return 144;
            case PROFILE_GENERIC:  return 0;
            case PROFILE_EGMP_192:
            default:               return 192;
        }
    }

    /**
     * Immutable point-in-time telemetry snapshot for thread-safe UI rendering & CSV logging.
     */
    public static final class Snapshot {
        public boolean hasBms0101;
        public boolean hasBms0105;
        public boolean hasBms0106;
        public boolean hasIccu;
        public boolean hasTpms;
        public boolean hasAbs;
        public boolean hasCluster;

        public float socBmsPct = Float.NaN;
        public float socDisplayPct = Float.NaN;
        public float sohPct = Float.NaN;
        public float remainingEnergyKwh = Float.NaN;

        public float packVolts = Float.NaN;
        public float packCurrentA = Float.NaN;      // +A = discharging, -A = charging/regen
        public float packPowerKw = Float.NaN;       // +kW = discharging, -kW = charging/regen
        public float availChargePowerKw = Float.NaN;
        public float availDischargePowerKw = Float.NaN;

        public boolean bmsRelayClosed;
        public boolean bmsIgnition;
        public boolean hvCharging;

        public int battTempMaxC = Integer.MIN_VALUE;
        public int battTempMinC = Integer.MIN_VALUE;
        public int battInletTempC = Integer.MIN_VALUE;
        public int coolantTempC = Integer.MIN_VALUE;
        public int ptcHeaterTempC = Integer.MIN_VALUE;
        public int thermalModeRaw = -1;
        public int fanStatus = 0;
        public int fanSpeedHz = 0;

        public float cellMaxVolts = Float.NaN;
        public int cellMaxIndex = 0;
        public float cellMinVolts = Float.NaN;
        public int cellMinIndex = 0;
        public int cellDeltaMv = -1;
        public final float[] cellVoltages = new float[MAX_CELLS];
        public int populatedCellCount = 0;
        public final int[] moduleTempsC = new int[16];
        public int populatedModuleCount = 0;

        public int inverterCapVolts = -1;
        public int motor1Rpm = 0;
        public int motor2Rpm = 0;
        public int isolationKohm = -1;

        public float bmsAux12Volts = Float.NaN;
        public float adapterAtRvVolts = Float.NaN;

        // ICCU LDC (7E5 -> 7ED)
        public int ldcTempC = Integer.MIN_VALUE;
        public float ldcOutVolts = Float.NaN;
        public float ldcOutAmps = Float.NaN;
        public float ldcHvInVolts = Float.NaN;
        public float aux12CurrentA = Float.NaN;
        public int aux12SocPct = -1;
        public float aux12SensorVolts = Float.NaN;

        // TPMS (7A0 -> 7A8): FL=0, FR=1, RL=2, RR=3
        public final float[] tirePsi = new float[] { Float.NaN, Float.NaN, Float.NaN, Float.NaN };
        public final int[] tireTempC = new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };

        // ABS / Cluster / Generic
        public int speedKmh = -1;
        public String gear = "-";
        public long odometerKm = -1L;
        public long odometerMiles = -1L;
        public int genericRpm = -1;

        // Lifetime cumulative BMS counters
        public float cumChargeAh = Float.NaN;
        public float cumDischargeAh = Float.NaN;
        public float cumChargeKwh = Float.NaN;
        public float cumDischargeKwh = Float.NaN;
        public long operatingTimeSec = -1L;

        public long lastUpdateMs = 0L;

        public Snapshot() {
            Arrays.fill(cellVoltages, Float.NaN);
            Arrays.fill(moduleTempsC, Integer.MIN_VALUE);
        }

        public String thermalModeLabel() {
            switch (thermalModeRaw) {
                case 0x00: return "OFF";
                case 0x64: return "LOW-TEMP LOOP";
                case 0x7D: return "PTC HEATER ON";
                case 0xB9: return "ACTIVE COOLING";
                default:
                    return thermalModeRaw < 0 ? "--" : String.format(Locale.US, "0x%02X", thermalModeRaw);
            }
        }
    }

    private final Object lock = new Object();
    private final Snapshot live = new Snapshot();
    private final List<String> csvRows = new ArrayList<String>(512);
    private long lastCsvSampleMs = 0L;

    public Snapshot snapshot() {
        synchronized (lock) {
            Snapshot copy = new Snapshot();
            copy.hasBms0101 = live.hasBms0101;
            copy.hasBms0105 = live.hasBms0105;
            copy.hasBms0106 = live.hasBms0106;
            copy.hasIccu = live.hasIccu;
            copy.hasTpms = live.hasTpms;
            copy.hasAbs = live.hasAbs;
            copy.hasCluster = live.hasCluster;

            copy.socBmsPct = live.socBmsPct;
            copy.socDisplayPct = live.socDisplayPct;
            copy.sohPct = live.sohPct;
            copy.remainingEnergyKwh = live.remainingEnergyKwh;

            copy.packVolts = live.packVolts;
            copy.packCurrentA = live.packCurrentA;
            copy.packPowerKw = live.packPowerKw;
            copy.availChargePowerKw = live.availChargePowerKw;
            copy.availDischargePowerKw = live.availDischargePowerKw;

            copy.bmsRelayClosed = live.bmsRelayClosed;
            copy.bmsIgnition = live.bmsIgnition;
            copy.hvCharging = live.hvCharging;

            copy.battTempMaxC = live.battTempMaxC;
            copy.battTempMinC = live.battTempMinC;
            copy.battInletTempC = live.battInletTempC;
            copy.coolantTempC = live.coolantTempC;
            copy.ptcHeaterTempC = live.ptcHeaterTempC;
            copy.thermalModeRaw = live.thermalModeRaw;
            copy.fanStatus = live.fanStatus;
            copy.fanSpeedHz = live.fanSpeedHz;

            copy.cellMaxVolts = live.cellMaxVolts;
            copy.cellMaxIndex = live.cellMaxIndex;
            copy.cellMinVolts = live.cellMinVolts;
            copy.cellMinIndex = live.cellMinIndex;
            copy.cellDeltaMv = live.cellDeltaMv;
            System.arraycopy(live.cellVoltages, 0, copy.cellVoltages, 0, MAX_CELLS);
            copy.populatedCellCount = live.populatedCellCount;
            System.arraycopy(live.moduleTempsC, 0, copy.moduleTempsC, 0, 16);
            copy.populatedModuleCount = live.populatedModuleCount;

            copy.inverterCapVolts = live.inverterCapVolts;
            copy.motor1Rpm = live.motor1Rpm;
            copy.motor2Rpm = live.motor2Rpm;
            copy.isolationKohm = live.isolationKohm;

            copy.bmsAux12Volts = live.bmsAux12Volts;
            copy.adapterAtRvVolts = live.adapterAtRvVolts;

            copy.ldcTempC = live.ldcTempC;
            copy.ldcOutVolts = live.ldcOutVolts;
            copy.ldcOutAmps = live.ldcOutAmps;
            copy.ldcHvInVolts = live.ldcHvInVolts;
            copy.aux12CurrentA = live.aux12CurrentA;
            copy.aux12SocPct = live.aux12SocPct;
            copy.aux12SensorVolts = live.aux12SensorVolts;

            System.arraycopy(live.tirePsi, 0, copy.tirePsi, 0, 4);
            System.arraycopy(live.tireTempC, 0, copy.tireTempC, 0, 4);

            copy.speedKmh = live.speedKmh;
            copy.gear = live.gear;
            copy.odometerKm = live.odometerKm;
            copy.odometerMiles = live.odometerMiles;
            copy.genericRpm = live.genericRpm;

            copy.cumChargeAh = live.cumChargeAh;
            copy.cumDischargeAh = live.cumDischargeAh;
            copy.cumChargeKwh = live.cumChargeKwh;
            copy.cumDischargeKwh = live.cumDischargeKwh;
            copy.operatingTimeSec = live.operatingTimeSec;
            copy.lastUpdateMs = live.lastUpdateMs;
            return copy;
        }
    }

    public void updateAtRvVolts(float volts) {
        if (Float.isNaN(volts)) return;
        synchronized (lock) {
            live.adapterAtRvVolts = volts;
            live.lastUpdateMs = System.currentTimeMillis();
        }
    }

    /**
     * Applies read-only HMG-API vehicle readings as non-overwriting fallbacks when direct OBD
     * ABS ({@code 7D9}) or Cluster ({@code 7CE}) frames have not yet arrived.
     */
    public void applyHmgVehicleSignals(Map<String, String> sigs) {
        if (sigs == null || sigs.isEmpty()) return;
        synchronized (lock) {
            if (!live.hasAbs) {
                String sp = sigs.get("speed");
                if (sp != null && !sp.isEmpty()) {
                    String lowerSp = sp.trim().toLowerCase(Locale.US);
                    boolean isMph = lowerSp.endsWith("mph");
                    String numSp = lowerSp.replaceAll("[^0-9.\\-]", "");
                    if (!numSp.isEmpty()) {
                        try {
                            float raw = Float.parseFloat(numSp);
                            int kmh = Math.round(isMph ? (raw * 1.609344f) : raw);
                            if (kmh >= 0 && kmh <= 300) {
                                live.speedKmh = kmh;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                String park = sigs.get("gear.park");
                if (park != null && "-".equals(live.gear)) {
                    if ("true".equalsIgnoreCase(park) || "1".equals(park)) {
                        live.gear = "P";
                    }
                }
                String gearSig = sigs.get("gear");
                if (gearSig != null && !gearSig.trim().isEmpty() && "-".equals(live.gear)) {
                    live.gear = gearSig.trim().toUpperCase(Locale.US);
                }
            }
            if (!live.hasCluster) {
                String odo = sigs.get("odometer");
                if (odo != null && !odo.isEmpty()) {
                    String lowerOdo = odo.trim().toLowerCase(Locale.US);
                    boolean isMi = lowerOdo.endsWith("mi") || lowerOdo.contains(" mile");
                    String numOdo = lowerOdo.replaceAll("[^0-9.]", "");
                    if (!numOdo.isEmpty()) {
                        try {
                            double val = Double.parseDouble(numOdo);
                            if (isMi) {
                                long mi = Math.round(val);
                                if (mi > 0 && mi < 1_500_000L) {
                                    live.odometerMiles = mi;
                                    live.odometerKm = Math.round(mi * 1.609344d);
                                }
                            } else {
                                long km = Math.round(val);
                                if (km > 0 && km < 2_000_000L) {
                                    live.odometerKm = km;
                                    live.odometerMiles = Math.round(km * 0.621371d);
                                }
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
    }

    /**
     * Applies one or more reassembled ISO-TP messages to the live telemetry model.
     * Returns true if at least one recognized DID/PID updated telemetry fields.
     */
    public boolean applyMessages(List<Elm327Protocol.IsoTpMessage> messages) {
        if (messages == null || messages.isEmpty()) return false;
        boolean updated = false;
        synchronized (lock) {
            for (int i = 0; i < messages.size(); i++) {
                Elm327Protocol.IsoTpMessage msg = messages.get(i);
                if (msg == null || msg.payload == null || msg.payload.length < 3) continue;
                int sid = msg.payload[0] & 0xFF;
                if (sid == 0x62) {
                    updated |= applyUds22Locked(msg.canId, msg.payload);
                } else if (sid == 0x41) {
                    updated |= applyMode01Locked(msg.payload);
                }
            }
            if (updated) {
                long now = System.currentTimeMillis();
                live.lastUpdateMs = now;
                maybeRecordCsvRowLocked(now);
            }
        }
        return updated;
    }

    private boolean applyUds22Locked(int canId, byte[] uds) {
        int did = ((uds[1] & 0xFF) << 8) | (uds[2] & 0xFF);

        // 1. BMS 7EC -> 22 0101 (Primary HV Pack Telemetry, >=62 bytes)
        if (did == 0x0101 && uds.length >= 62 && (canId == CAN_BMS_RX || canId == -1)) {
            live.hasBms0101 = true;
            live.socBmsPct = (uds[7] & 0xFF) / 2.0f;
            live.availChargePowerKw = u16be(uds, 8) / 100.0f;
            live.availDischargePowerKw = u16be(uds, 10) / 100.0f;
            live.bmsRelayClosed = (uds[12] & 0x01) != 0;
            live.packCurrentA = s16be(uds, 13) / 10.0f;
            live.packVolts = u16be(uds, 15) / 10.0f;
            live.packPowerKw = (live.packVolts * live.packCurrentA) / 1000.0f;

            live.battTempMaxC = (int) uds[17];
            live.battTempMinC = (int) uds[18];
            for (int m = 0; m < 5; m++) {
                live.moduleTempsC[m] = (int) uds[19 + m];
            }
            recountModulesLocked();

            live.cellMaxVolts = (uds[26] & 0xFF) / 50.0f;
            live.cellMaxIndex = uds[27] & 0xFF;
            live.cellMinVolts = (uds[28] & 0xFF) / 50.0f;
            live.cellMinIndex = uds[29] & 0xFF;
            live.cellDeltaMv = Math.max(0, Math.round((live.cellMaxVolts - live.cellMinVolts) * 1000.0f));

            live.fanStatus = uds[30] & 0xFF;
            live.fanSpeedHz = uds[31] & 0xFF;
            live.bmsAux12Volts = (uds[32] & 0xFF) / 10.0f;

            live.cumChargeAh = u32be(uds, 33) / 10.0f;
            live.cumDischargeAh = u32be(uds, 37) / 10.0f;
            live.cumChargeKwh = u32be(uds, 41) / 10.0f;
            live.cumDischargeKwh = u32be(uds, 45) / 10.0f;
            live.operatingTimeSec = u32be(uds, 49);

            int ignByte = uds[53] & 0xFF;
            live.bmsIgnition = ((ignByte >>> 2) & 0x01) != 0 || (ignByte & 0x01) != 0;
            live.inverterCapVolts = u16be(uds, 54);
            live.motor1Rpm = s16be(uds, 56);
            live.motor2Rpm = s16be(uds, 58);
            live.isolationKohm = u16be(uds, 60);
            return true;
        }

        // 2. BMS 7EC -> 22 0105 (Display SoC, SOH, Remaining kWh, PTC Heater, Module Temps 6..16)
        if (did == 0x0105 && uds.length >= 35 && (canId == CAN_BMS_RX || canId == -1)) {
            live.hasBms0105 = true;
            for (int m = 0; m < 7 && (12 + m) < uds.length; m++) {
                live.moduleTempsC[5 + m] = (int) uds[12 + m];
            }
            if (uds.length >= 46) {
                for (int m = 0; m < 4; m++) {
                    live.moduleTempsC[12 + m] = (int) uds[42 + m];
                }
            }
            recountModulesLocked();

            live.ptcHeaterTempC = (int) uds[26];
            float rawSoh = u16be(uds, 28) / 10.0f;
            if (rawSoh > 0.0f && rawSoh <= 105.0f) {
                live.sohPct = Math.min(100.0f, rawSoh);
            }
            live.remainingEnergyKwh = (u16be(uds, 31) * 2.0f) / 1000.0f;
            live.socDisplayPct = (uds[34] & 0xFF) / 2.0f;
            return true;
        }

        // 3. BMS 7EC -> 22 0106 (Cooling Water Temp uds[7], Optional Coolant2/Status uds[9], Thermal Mode, Charging Flag)
        if (did == 0x0106 && uds.length >= 28 && (canId == CAN_BMS_RX || canId == -1)) {
            live.hasBms0106 = true;
            int rawCoolant = (int) uds[7];
            boolean validCoolant = (rawCoolant >= -40 && rawCoolant <= 120);
            if (validCoolant) {
                live.coolantTempC = rawCoolant;
            }
            if (uds.length >= 10) {
                int rawInlet = (int) uds[9];
                // On US E-GMP BMS firmware, uds[9] is a 0/1 coolant-pump/status byte rather than a
                // second thermistor, whereas uds[7] is the verified HV battery cooling water temp.
                boolean looksLikeStatusFlag = (rawInlet == 0 || rawInlet == 1)
                        && ((validCoolant && rawCoolant >= 4) || live.battTempMinC >= 4);
                if (!looksLikeStatusFlag && rawInlet >= -40 && rawInlet <= 120
                        && (!validCoolant || Math.abs(rawInlet - rawCoolant) <= 10)) {
                    live.battInletTempC = rawInlet;
                } else if (validCoolant) {
                    live.battInletTempC = rawCoolant;
                }
            } else if (live.battInletTempC == Integer.MIN_VALUE && validCoolant) {
                live.battInletTempC = rawCoolant;
            }
            live.thermalModeRaw = uds[12] & 0xFF;
            boolean chgBit = (uds[27] & 0x01) != 0;
            live.hvCharging = chgBit && (Float.isNaN(live.packPowerKw) || live.packPowerKw <= 0.5f);
            return true;
        }

        // 4. BMS 7EC -> Cell Voltage Blocks 22 0102/0103/0104/010A/010B/010C (39 bytes)
        if ((canId == CAN_BMS_RX || (canId == -1 && uds.length <= 42)) && uds.length >= 39) {
            int cellBase = -1;
            switch (did) {
                case 0x0102: cellBase = 0; break;
                case 0x0103: cellBase = 32; break;
                case 0x0104: cellBase = 64; break;
                case 0x010A: cellBase = 96; break;
                case 0x010B: cellBase = 128; break;
                case 0x010C: cellBase = 160; break;
                default: break;
            }
            if (cellBase >= 0) {
                return applyCellBlockLocked(cellBase, uds);
            }
        }

        // 5. ABS/ESC 7D9 -> 22 0104 (Vehicle Speed & Gear Selector; 48 bytes on E-GMP CAN)
        if (did == 0x0104 && uds.length >= 12
                && (canId == CAN_ABS_RX || (canId == -1 && (uds.length < 39 || uds.length >= 44)))) {
            live.hasAbs = true;
            live.speedKmh = uds[9] & 0xFF;
            int g = uds[11] & 0xFF;
            if (g == 1) live.gear = "P/N";
            else if (g == 2) live.gear = "R";
            else if (g == 4) live.gear = "D";
            else live.gear = String.format(Locale.US, "0x%X", g);
            return true;
        }

        // 6. ICCU (OBC + LDC) 7ED -> 22 E011 or MY25 22 E101 (>=26 bytes)
        if ((did == 0xE011 || did == 0xE101) && uds.length >= 26 && (canId == CAN_ICCU_RX || canId == -1)) {
            live.hasIccu = true;
            live.ldcTempC = (uds[12] & 0xFF) - 100;
            live.ldcOutVolts = u16be(uds, 13) / 1000.0f;
            live.ldcOutAmps = u16be(uds, 15) / 1000.0f;
            live.ldcHvInVolts = u16be(uds, 17) / 10.0f;
            live.aux12CurrentA = (u16be(uds, 21) - 32768) / 100.0f;
            int soc12 = uds[23] & 0xFF;
            if (soc12 <= 100) {
                live.aux12SocPct = soc12;
            }
            live.aux12SensorVolts = (u16be(uds, 24) / 1000.0f) + 6.0f;
            return true;
        }

        // 7. TPMS 7A8 -> 22 C00B (4-Corner Tire Pressure & Temperature, >=24 bytes)
        if (did == 0xC00B && uds.length >= 24 && (canId == CAN_TPMS_RX || canId == -1)) {
            live.hasTpms = true;
            int[] pIdx = { 7, 12, 17, 22 };
            int[] tIdx = { 8, 13, 18, 23 };
            for (int w = 0; w < 4; w++) {
                int rawP = uds[pIdx[w]] & 0xFF;
                int rawT = uds[tIdx[w]] & 0xFF;
                if (rawP > 0 && rawP < 0xFF) {
                    live.tirePsi[w] = rawP * 0.2f;
                }
                if (rawT > 0 && rawT < 0xFF) {
                    live.tireTempC[w] = rawT - 50;
                }
            }
            return true;
        }

        // 8. Instrument Cluster 7CE -> 22 B002 (Metric km in uds[9..11], or US/UK miles in uds[12..14])
        if (did == 0xB002 && uds.length >= 12 && (canId == CAN_CLUSTER_RX || canId == -1)) {
            long odoKm = u24be(uds, 9);
            if (odoKm > 0 && odoKm < 2_000_000L && odoKm != 0xAAAAAAL) {
                live.hasCluster = true;
                live.odometerKm = odoKm;
                live.odometerMiles = Math.round(odoKm * 0.621371d);
                return true;
            }
            if (uds.length >= 15) {
                long odoMi = u24be(uds, 12);
                if (odoMi > 0 && odoMi < 1_500_000L && odoMi != 0xAAAAAAL) {
                    live.hasCluster = true;
                    live.odometerMiles = odoMi;
                    live.odometerKm = Math.round(odoMi * 1.609344d);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean applyCellBlockLocked(int cellBase, byte[] uds) {
        // Reject placeholder floods (e.g. >=28 bytes of 0xC8=4.00V, 0xFF, or 0x00 on smaller packs)
        int c8Count = 0;
        int ffCount = 0;
        int zeroCount = 0;
        for (int i = 0; i < 32; i++) {
            int v = uds[7 + i] & 0xFF;
            if (v == 0xC8) c8Count++;
            else if (v == 0xFF) ffCount++;
            else if (v == 0x00) zeroCount++;
        }
        if (c8Count >= 28 || ffCount >= 28 || zeroCount >= 28) {
            return false;
        }

        boolean any = false;
        for (int i = 0; i < 32 && (cellBase + i) < MAX_CELLS; i++) {
            int raw = uds[7 + i] & 0xFF;
            if (raw >= 125 && raw <= 220) {
                live.cellVoltages[cellBase + i] = raw / 50.0f;
                any = true;
            }
        }
        if (any) {
            int count = 0;
            for (int c = 0; c < MAX_CELLS; c++) {
                if (!Float.isNaN(live.cellVoltages[c])) count++;
            }
            live.populatedCellCount = count;
        }
        return any;
    }

    private void recountModulesLocked() {
        int warmest = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            if (live.moduleTempsC[i] != Integer.MIN_VALUE && live.moduleTempsC[i] > warmest) {
                warmest = live.moduleTempsC[i];
            }
        }
        // Trim unpopulated trailing 0 °C slots on 24/30-module packs when warmest module >= 15 °C
        if (warmest >= 15) {
            for (int i = 15; i >= 8; i--) {
                if (live.moduleTempsC[i] == 0) {
                    live.moduleTempsC[i] = Integer.MIN_VALUE;
                } else if (live.moduleTempsC[i] != Integer.MIN_VALUE) {
                    break;
                }
            }
        }
        int cnt = 0;
        for (int i = 0; i < 16; i++) {
            if (live.moduleTempsC[i] != Integer.MIN_VALUE) cnt++;
        }
        live.populatedModuleCount = cnt;
    }

    private boolean applyMode01Locked(byte[] p) {
        int pid = p[1] & 0xFF;
        switch (pid) {
            case 0x05: // Coolant temperature
                if (p.length >= 3) {
                    live.coolantTempC = (p[2] & 0xFF) - 40;
                    return true;
                }
                break;
            case 0x0C: // Engine RPM
                if (p.length >= 4) {
                    live.genericRpm = u16be(p, 2) / 4;
                    return true;
                }
                break;
            case 0x0D: // Vehicle Speed km/h
                if (p.length >= 3) {
                    live.speedKmh = p[2] & 0xFF;
                    return true;
                }
                break;
            case 0x42: // Control module voltage
                if (p.length >= 4) {
                    live.aux12SensorVolts = u16be(p, 2) / 1000.0f;
                    return true;
                }
                break;
            case 0x5B: // Hybrid/EV pack remaining %
                if (p.length >= 3) {
                    live.socDisplayPct = (p[2] & 0xFF) * 100.0f / 255.0f;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    private void maybeRecordCsvRowLocked(long nowMs) {
        if (!live.hasBms0101 && (live.hasBms0105 || live.hasBms0106 || live.hasAbs || live.hasIccu)) {
            return;
        }
        if (nowMs - lastCsvSampleMs < 1000L) {
            return;
        }
        lastCsvSampleMs = nowMs;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        String row = String.format(Locale.US,
                "%s,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f,%.2f,%d,%d,%d,%d,%.2f,%.2f,%d,%.3f,%.2f,%.2f,%d,%d,%s,%d",
                sdf.format(new Date(nowMs)),
                nanZero(live.socDisplayPct),
                nanZero(live.socBmsPct),
                nanZero(live.sohPct),
                nanZero(live.remainingEnergyKwh),
                nanZero(live.packVolts),
                nanZero(live.packCurrentA),
                nanZero(live.packPowerKw),
                tempZero(live.battTempMinC),
                tempZero(live.battTempMaxC),
                tempZero(live.battInletTempC),
                tempZero(live.ptcHeaterTempC),
                nanZero(live.cellMinVolts),
                nanZero(live.cellMaxVolts),
                Math.max(0, live.cellDeltaMv),
                nanZero(live.ldcOutVolts),
                nanZero(live.ldcOutAmps),
                nanZero(live.aux12SensorVolts),
                Math.max(0, live.aux12SocPct),
                Math.max(0, live.speedKmh),
                live.gear,
                Math.max(0L, live.odometerKm));
        if (csvRows.size() >= MAX_CSV_SAMPLES) {
            csvRows.remove(0);
        }
        csvRows.add(row);
    }

    public String buildCsvExport() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("timestamp,soc_disp_pct,soc_bms_pct,soh_pct,rem_kwh,pack_v,pack_a,pack_kw,")
              .append("batt_min_c,batt_max_c,inlet_c,ptc_heater_c,cell_min_v,cell_max_v,cell_delta_mv,")
              .append("ldc_out_v,ldc_out_a,aux12_v,aux12_soc_pct,speed_kmh,gear,odometer_km\n");
            for (int i = 0; i < csvRows.size(); i++) {
                sb.append(csvRows.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    public int csvRowCount() {
        synchronized (lock) {
            return csvRows.size();
        }
    }

    // ================= UNIT FORMATTERS =================

    public static String formatTemp(int tempC, boolean useFahrenheit) {
        if (tempC == Integer.MIN_VALUE) return "--";
        if (useFahrenheit) {
            int f = Math.round(tempC * 9.0f / 5.0f + 32.0f);
            return f + " °F";
        }
        return tempC + " °C";
    }

    public static String formatSpeed(int kmh, boolean useMiles) {
        if (kmh < 0) return "-- " + (useMiles ? "mph" : "km/h");
        if (useMiles) {
            return Math.round(kmh * 0.621371f) + " mph";
        }
        return kmh + " km/h";
    }

    public static String formatDistance(long km, boolean useMiles) {
        return formatDistance(km, -1L, useMiles);
    }

    public static String formatDistance(long km, long miles, boolean useMiles) {
        if (useMiles) {
            if (miles >= 0) {
                return String.format(Locale.US, "%,d mi", miles);
            }
            if (km >= 0) {
                return String.format(Locale.US, "%,d mi", Math.round(km * 0.621371d));
            }
            return "-- mi";
        }
        if (km >= 0) {
            return String.format(Locale.US, "%,d km", km);
        }
        if (miles >= 0) {
            return String.format(Locale.US, "%,d km", Math.round(miles * 1.609344d));
        }
        return "-- km";
    }

    public static String formatTirePressure(float psi, boolean useBar) {
        if (Float.isNaN(psi) || psi <= 0.0f) {
            return "--.- " + (useBar ? "bar" : "PSI");
        }
        if (useBar) {
            return String.format(Locale.US, "%.2f bar", psi * 0.0689476f);
        }
        return String.format(Locale.US, "%.1f PSI", psi);
    }

    private static float nanZero(float v) {
        return Float.isNaN(v) ? 0.0f : v;
    }

    private static int tempZero(int t) {
        return t == Integer.MIN_VALUE ? 0 : t;
    }

    public static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static int s16be(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    public static long u24be(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 16)
                | ((long) (b[off + 1] & 0xFF) << 8)
                | (long) (b[off + 2] & 0xFF);
    }

    public static long u32be(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (long) (b[off + 3] & 0xFF);
    }
}
