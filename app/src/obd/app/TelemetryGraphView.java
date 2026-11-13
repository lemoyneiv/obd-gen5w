package obd.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Allocation-free API-19 dual-trace rolling chart for HV Pack Power (kW) and Pack Voltage (V).
 */
public final class TelemetryGraphView extends View {

    public static final int MAX_POINTS = 180;

    private final float[] powerKw = new float[MAX_POINTS];
    private final float[] packVolts = new float[MAX_POINTS];
    private int count = 0;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint powerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint voltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path powerPath = new Path();
    private final Path voltPath = new Path();

    private String topScaleLabel = "+100 kW";
    private String botScaleLabel = "-100 kW";
    private String voltRangeLabel = "HV --- V";

    public TelemetryGraphView(Context context) {
        super(context);
        initPaints();
    }

    public TelemetryGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public TelemetryGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPaints();
    }

    private void initPaints() {
        gridPaint.setColor(0xFF1E2B40);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.0f);

        zeroPaint.setColor(0xFF334155);
        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(1.5f);

        powerPaint.setColor(0xFF00E5FF);
        powerPaint.setStyle(Paint.Style.STROKE);
        powerPaint.setStrokeWidth(2.6f);

        voltPaint.setColor(0xFF22C55E);
        voltPaint.setStyle(Paint.Style.STROKE);
        voltPaint.setStrokeWidth(2.0f);

        labelPaint.setColor(0xFF94A3B8);
        labelPaint.setTextSize(15.0f);
    }

    public void pushSample(float kw, float volts) {
        if (Float.isNaN(kw) && Float.isNaN(volts)) return;
        float safeKw = Float.isNaN(kw) ? 0.0f : kw;
        float safeV = Float.isNaN(volts) ? 0.0f : volts;
        if (count < MAX_POINTS) {
            powerKw[count] = safeKw;
            packVolts[count] = safeV;
            count++;
        } else {
            System.arraycopy(powerKw, 1, powerKw, 0, MAX_POINTS - 1);
            System.arraycopy(packVolts, 1, packVolts, 0, MAX_POINTS - 1);
            powerKw[MAX_POINTS - 1] = safeKw;
            packVolts[MAX_POINTS - 1] = safeV;
        }

        float maxAbsKw = 30.0f;
        float minV = 9999.0f;
        float maxV = 0.0f;
        for (int i = 0; i < count; i++) {
            float a = Math.abs(powerKw[i]);
            if (a > maxAbsKw) maxAbsKw = a;
            if (packVolts[i] > 50.0f) {
                if (packVolts[i] < minV) minV = packVolts[i];
                if (packVolts[i] > maxV) maxV = packVolts[i];
            }
        }
        int roundedKw = Math.max(30, ((int) ((maxAbsKw + 19.0f) / 20.0f)) * 20);
        topScaleLabel = String.format(Locale.US, "+%d kW (Draw)", roundedKw);
        botScaleLabel = String.format(Locale.US, "-%d kW (Regen/Chg)", roundedKw);
        if (maxV > 50.0f) {
            voltRangeLabel = String.format(Locale.US, "HV %.0f-%.0f V", minV, maxV);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 16 || h <= 16) return;

        float padL = 8.0f;
        float padR = 8.0f;
        float padT = 18.0f;
        float padB = 18.0f;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;
        float midY = padT + plotH * 0.5f;

        canvas.drawRect(padL, padT, padL + plotW, padT + plotH, gridPaint);
        canvas.drawLine(padL, midY, padL + plotW, midY, zeroPaint);
        canvas.drawLine(padL, padT + plotH * 0.25f, padL + plotW, padT + plotH * 0.25f, gridPaint);
        canvas.drawLine(padL, padT + plotH * 0.75f, padL + plotW, padT + plotH * 0.75f, gridPaint);

        canvas.drawText(topScaleLabel, padL + 6.0f, padT + 14.0f, labelPaint);
        canvas.drawText(botScaleLabel, padL + 6.0f, padT + plotH - 5.0f, labelPaint);
        canvas.drawText(voltRangeLabel, padL + plotW - 135.0f, padT + 14.0f, labelPaint);

        if (count < 2) {
            canvas.drawText("Waiting for live HV Pack telemetry...", padL + plotW * 0.28f, midY - 6.0f, labelPaint);
            return;
        }

        float maxAbsKw = 30.0f;
        float minV = 9999.0f;
        float maxV = 0.0f;
        for (int i = 0; i < count; i++) {
            float a = Math.abs(powerKw[i]);
            if (a > maxAbsKw) maxAbsKw = a;
            if (packVolts[i] > 50.0f) {
                if (packVolts[i] < minV) minV = packVolts[i];
                if (packVolts[i] > maxV) maxV = packVolts[i];
            }
        }
        float scaleKw = Math.max(30.0f, ((int) ((maxAbsKw + 19.0f) / 20.0f)) * 20.0f);
        float vLow = (maxV > 50.0f) ? (minV - 8.0f) : 550.0f;
        float vHigh = (maxV > 50.0f) ? Math.max(vLow + 16.0f, maxV + 8.0f) : 820.0f;

        powerPath.reset();
        voltPath.reset();
        boolean hasVolt = false;

        for (int i = 0; i < count; i++) {
            float x = padL + (plotW * i) / (float) (MAX_POINTS - 1);
            float normKw = powerKw[i] / scaleKw;
            if (normKw > 1.0f) normKw = 1.0f;
            if (normKw < -1.0f) normKw = -1.0f;
            float yKw = midY - (normKw * (plotH * 0.46f));
            if (i == 0) {
                powerPath.moveTo(x, yKw);
            } else {
                powerPath.lineTo(x, yKw);
            }

            if (packVolts[i] > 50.0f) {
                float normV = (packVolts[i] - vLow) / (vHigh - vLow);
                if (normV < 0.0f) normV = 0.0f;
                if (normV > 1.0f) normV = 1.0f;
                float yV = padT + plotH - (normV * plotH);
                if (!hasVolt) {
                    voltPath.moveTo(x, yV);
                    hasVolt = true;
                } else {
                    voltPath.lineTo(x, yV);
                }
            }
        }

        if (hasVolt) {
            canvas.drawPath(voltPath, voltPaint);
        }
        canvas.drawPath(powerPath, powerPaint);
    }
}
