package obd.app;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bounded RAM-only diagnostic event & 12V BVM02 CSV ring buffer.
 * Zero writes to internal flash; exports atomically to external USB on demand.
 */
public final class DiagLogger {

    public static final String TAG = "OBDGen5W";
    private static final int MAX_LINES = 600;
    private static final int MAX_BVM_CSV = 2400;

    private static final DiagLogger INSTANCE = new DiagLogger();

    public static DiagLogger get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final List<String> lines = new ArrayList<String>(256);
    private final List<String> bvmCsvRows = new ArrayList<String>(256);

    private DiagLogger() {}

    public void info(String category, String msg) {
        append("I", category, msg);
        try { Log.i(TAG, "[" + category + "] " + msg); } catch (Throwable ignored) {}
    }

    public void warn(String category, String msg) {
        append("W", category, msg);
        try { Log.w(TAG, "[" + category + "] " + msg); } catch (Throwable ignored) {}
    }

    public void error(String category, String msg) {
        append("E", category, msg);
        try { Log.e(TAG, "[" + category + "] " + msg); } catch (Throwable ignored) {}
    }

    private void append(String level, String category, String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        String line = sdf.format(new Date()) + " " + level + "/" + category + ": " + msg;
        synchronized (lock) {
            if (lines.size() >= MAX_LINES) {
                lines.remove(0);
            }
            lines.add(line);
        }
    }

    public void recordBvm02Reading(int slot, String mac, float volts, int socPct, int tempC, boolean hasTemp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        String row = String.format(Locale.US, "%s,%d,%s,%.2f,%d,%s",
                sdf.format(new Date()),
                slot + 1,
                mac == null ? "" : mac,
                volts,
                socPct,
                hasTemp ? Integer.toString(tempC) : "");
        synchronized (lock) {
            if (bvmCsvRows.size() >= MAX_BVM_CSV) {
                bvmCsvRows.remove(0);
            }
            bvmCsvRows.add(row);
        }
    }

    public String dumpRecentText(int maxLines) {
        synchronized (lock) {
            int n = lines.size();
            int start = Math.max(0, n - maxLines);
            StringBuilder sb = new StringBuilder(2048);
            for (int i = start; i < n; i++) {
                sb.append(lines.get(i));
                if (i < n - 1) sb.append('\n');
            }
            return sb.toString();
        }
    }

    public String dumpAllText() {
        return dumpRecentText(MAX_LINES);
    }

    public static final class ExportResult {
        public final File diagFile;
        public final File egmpCsvFile;
        public final File bvmCsvFile;

        public ExportResult(File diagFile, File egmpCsvFile, File bvmCsvFile) {
            this.diagFile = diagFile;
            this.egmpCsvFile = egmpCsvFile;
            this.bvmCsvFile = bvmCsvFile;
        }
    }

    /**
     * Exports RAM diagnostic logs + E-GMP HV telemetry CSV + optional BVM02 12V CSV to {@code <usb>/OBDLogs/}.
     */
    public ExportResult exportAllToUsb(EgmpTelemetry telemetry) throws IOException {
        File usbDir = UsbStorageHelper.findWritableLogDir();
        if (usbDir == null) {
            throw new IOException("No writable USB drive mounted (expected /storage/usb0/OBDLogs)");
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        String header = "=== OBD-II & E-GMP Telemetry v" + AppVersion.APP_VERSION + " Diagnostic Export ===\n"
                + "Export Timestamp: " + ts + "\n\n";
        File diagOut = UsbStorageHelper.writeTextAtomically(
                usbDir, "obd_diag_" + ts + ".txt", header + dumpAllText() + "\n");

        File egmpOut = null;
        if (telemetry != null && telemetry.csvRowCount() > 0) {
            egmpOut = UsbStorageHelper.writeTextAtomically(
                    usbDir, "obd_egmp_telemetry_" + ts + ".csv", telemetry.buildCsvExport());
        }

        File bvmOut = null;
        String bvmCsv = null;
        synchronized (lock) {
            if (!bvmCsvRows.isEmpty()) {
                StringBuilder sb = new StringBuilder(2048);
                sb.append("timestamp,slot,mac,volts,soc_pct,temp_c\n");
                for (int i = 0; i < bvmCsvRows.size(); i++) {
                    sb.append(bvmCsvRows.get(i)).append('\n');
                }
                bvmCsv = sb.toString();
            }
        }
        if (bvmCsv != null) {
            bvmOut = UsbStorageHelper.writeTextAtomically(
                    usbDir, "obd_bvm02_12v_" + ts + ".csv", bvmCsv);
        }

        return new ExportResult(diagOut, egmpOut, bvmOut);
    }
}
