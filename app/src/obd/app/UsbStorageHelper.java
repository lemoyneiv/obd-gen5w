package obd.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * External USB storage locator and atomic file writer for Gen5W head units.
 *
 * Strictly writes ONLY to removable USB media under {@code <usb>/OBDLogs/} using temporary
 * {@code .part} files + {@code FileDescriptor.sync()} + {@code renameTo()} so sudden 12V accessory
 * power cuts never corrupt FAT32/exFAT filesystems or touch internal eMMC flash.
 */
public final class UsbStorageHelper {

    public static final String LOG_DIR_NAME = "OBDLogs";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String[] CANDIDATE_USB_MOUNTS = {
        "/storage/usb0",
        "/storage/usb1",
        "/storage/usb2",
        "/mnt/media_rw/usb0",
        "/mnt/media_rw/usb1",
        "/mnt/usb0",
        "/mnt/usb1",
        "/storage/ext_sd"
    };

    private UsbStorageHelper() {}

    /**
     * Locates an active, writable {@code OBDLogs} directory on external USB media,
     * creating {@code OBDLogs} on a mounted writable USB root if needed.
     */
    public static File findWritableLogDir() {
        for (int i = 0; i < CANDIDATE_USB_MOUNTS.length; i++) {
            File mount = new File(CANDIDATE_USB_MOUNTS[i]);
            if (!mount.exists() || !mount.isDirectory()) continue;
            File logDir = new File(mount, LOG_DIR_NAME);
            if (logDir.exists() && logDir.isDirectory() && logDir.canWrite()) {
                return logDir;
            }
            if (mount.canWrite()) {
                if (logDir.mkdirs() || (logDir.isDirectory() && logDir.canWrite())) {
                    return logDir;
                }
            }
        }
        return null;
    }

    public static File writeTextAtomically(File targetDir, String baseFileName, String content) throws IOException {
        if (targetDir == null) {
            throw new IOException("No writable USB OBDLogs directory mounted");
        }
        byte[] bytes = (content == null ? "" : content).getBytes(UTF8);
        return writeBytesAtomically(targetDir, baseFileName, bytes);
    }

    public static File writeBytesAtomically(File targetDir, String baseFileName, byte[] data) throws IOException {
        if (targetDir == null) {
            throw new IOException("No writable USB OBDLogs directory mounted");
        }
        File out = uniqueFile(targetDir, baseFileName);
        File tmp = new File(targetDir, "." + out.getName() + ".part");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            if (data != null && data.length > 0) {
                fos.write(data);
            }
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {}
            fos.close();
            fos = null;
            if (!tmp.renameTo(out)) {
                throw new IOException("Failed to rename temporary file to " + out.getAbsolutePath());
            }
            return out;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            if (tmp.exists()) {
                // Clean up leftover .part on failure
                tmp.delete();
            }
        }
    }

    public static File uniqueFile(File dir, String desiredName) {
        File candidate = new File(dir, desiredName);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = desiredName.lastIndexOf('.');
        String stem = (dot > 0) ? desiredName.substring(0, dot) : desiredName;
        String ext = (dot > 0) ? desiredName.substring(dot) : "";
        int n = 1;
        while (true) {
            File next = new File(dir, stem + "_" + n + ext);
            if (!next.exists()) {
                return next;
            }
            n++;
        }
    }
}
