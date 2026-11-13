package obd.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Allocation-free API-19 12V battery voltage sparkline view supporting both concurrent LNEX BVM02
 * direct sensor samples and ICCU Aux 12V fallback samples.
 */
public final class VoltageSparklineView extends View {

    private static final int MAX_SAMPLES = 120;
    private final float[] values = new float[MAX_SAMPLES];
    private int count = 0;
    private String sourceLabel = "12V Voltage Trace";

    private final Paint borderPaint = new Paint();
    private final Paint refPaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public VoltageSparklineView(Context context) {
        super(context);
        init();
    }

    public VoltageSparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoltageSparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        borderPaint.setColor(0xFF1E2B40);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.0f);

        refPaint.setColor(0xFF283548);
        refPaint.setStyle(Paint.Style.STROKE);
        refPaint.setStrokeWidth(1.0f);

        linePaint.setColor(0xFFA78BFA);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.4f);

        textPaint.setColor(0xFF94A3B8);
        textPaint.setTextSize(14.0f);
    }

    public void setSamples(String label, float[] samples) {
        if (label != null) {
            this.sourceLabel = label;
        }
        if (samples == null || samples.length == 0) {
            this.count = 0;
        } else {
            int n = Math.min(MAX_SAMPLES, samples.length);
            int srcOff = samples.length - n;
            System.arraycopy(samples, srcOff, values, 0, n);
            this.count = n;
        }
        invalidate();
    }

    public void pushFallbackSample(String label, float volts) {
        if (Float.isNaN(volts) || volts < 5.0f || volts > 32.0f) return;
        if (label != null) {
            this.sourceLabel = label;
        }
        if (count < MAX_SAMPLES) {
            values[count++] = volts;
        } else {
            System.arraycopy(values, 1, values, 0, MAX_SAMPLES - 1);
            values[MAX_SAMPLES - 1] = volts;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 16 || h <= 16) return;

        float pad = 8.0f;
        float plotW = w - pad * 2.0f;
        float plotH = h - pad * 2.0f;
        canvas.drawRect(pad, pad, pad + plotW, pad + plotH, borderPaint);

        float vMin = 11.0f;
        float vMax = 15.2f;
        for (int i = 0; i < count; i++) {
            if (values[i] < vMin) vMin = values[i] - 0.2f;
            if (values[i] > vMax) vMax = values[i] + 0.2f;
        }

        // Reference lines at 12.0V and 14.4V
        float y12 = pad + plotH - ((12.0f - vMin) / (vMax - vMin)) * plotH;
        float y144 = pad + plotH - ((14.4f - vMin) / (vMax - vMin)) * plotH;
        canvas.drawLine(pad, y12, pad + plotW, y12, refPaint);
        canvas.drawLine(pad, y144, pad + plotW, y144, refPaint);

        if (count < 2) {
            canvas.drawText(sourceLabel + " — waiting for 12V samples...", pad + 8.0f, pad + plotH * 0.55f, textPaint);
            return;
        }

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = pad + (plotW * i) / (float) (MAX_SAMPLES - 1);
            float norm = (values[i] - vMin) / (vMax - vMin);
            if (norm < 0.0f) norm = 0.0f;
            if (norm > 1.0f) norm = 1.0f;
            float y = pad + plotH - norm * plotH;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);

        String cap = String.format(Locale.US, "%s  •  Latest %.2f V", sourceLabel, values[count - 1]);
        canvas.drawText(cap, pad + 6.0f, pad + 15.0f, textPaint);
    }
}
