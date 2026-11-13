package obd.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures screenshots directly to external USB ({@code <usb>/OBDLogs/}) either via HMG-API's
 * hardware framebuffer {@code screenshot} service or via a pure-Java root view fallback.
 */
public final class ScreenshotHelper {

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private ScreenshotHelper() {}

    public static void captureToUsbAsync(final Activity activity, final Callback cb) {
        if (activity == null) return;
        final View root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        Bitmap fallbackBmp = null;
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            try {
                fallbackBmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(fallbackBmp);
                root.draw(c);
            } catch (Throwable ignored) {
                fallbackBmp = null;
            }
        }
        final Bitmap capturedBmp = fallbackBmp;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String resultMsg;
                boolean success = false;
                try {
                    HmgapidClient.Reply r = HmgapidClient.startService("screenshot", 5000);
                    if (r.ok()) {
                        success = true;
                        resultMsg = "Saved framebuffer screenshot to USB OBDLogs/";
                    } else {
                        throw new RuntimeException(r.text);
                    }
                } catch (Throwable ignored) {
                    if (capturedBmp != null) {
                        try {
                            File usbDir = UsbStorageHelper.findWritableLogDir();
                            if (usbDir == null) {
                                resultMsg = "No writable USB drive mounted for screenshot";
                            } else {
                                ByteArrayOutputStream png = new ByteArrayOutputStream(65536);
                                capturedBmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                                File out = UsbStorageHelper.writeBytesAtomically(
                                        usbDir, "obd_screenshot_" + ts + ".png", png.toByteArray());
                                success = true;
                                resultMsg = "Saved screenshot: " + out.getName();
                            }
                        } catch (Throwable e) {
                            resultMsg = "Screenshot failed: " + e.getMessage();
                        }
                    } else {
                        resultMsg = "Screenshot unavailable (no USB or framebuffer)";
                    }
                } finally {
                    if (capturedBmp != null && !capturedBmp.isRecycled()) {
                        capturedBmp.recycle();
                    }
                }

                final boolean okFinal = success;
                final String msgFinal = resultMsg;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(okFinal, msgFinal);
                    }
                });
            }
        }, "ObdScreenshotWorker");
        t.setDaemon(true);
        t.start();
    }
}
