package obd.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Allocation-free API-19 144 / 180 / 192-cell HV pack balance bar/heatmap visualizer.
 * Color-codes each cell by millivolt deviation from the pack mean.
 */
public final class CellHeatmapView extends View {

    private final float[] cells = new float[EgmpTelemetry.MAX_CELLS];
    private int expectedCells = 192;
    private int populatedCount = 0;
    private float minV = Float.NaN;
    private float maxV = Float.NaN;
    private float meanV = Float.NaN;

    private final Paint barOkPaint = new Paint();
    private final Paint barWarnPaint = new Paint();
    private final Paint barAlertPaint = new Paint();
    private final Paint slotBgPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CellHeatmapView(Context context) {
        super(context);
        init();
    }

    public CellHeatmapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CellHeatmapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = Float.NaN;
        }
        barOkPaint.setColor(0xFF00E5FF);
        barWarnPaint.setColor(0xFFF59E0B);
        barAlertPaint.setColor(0xFFEF4444);
        slotBgPaint.setColor(0xFF0B111B);
        borderPaint.setColor(0xFF1E2B40);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.0f);
        textPaint.setColor(0xFF94A3B8);
        textPaint.setTextSize(15.0f);
    }

    public void updateCells(float[] sourceCells, int expectedCount) {
        this.expectedCells = expectedCount > 0 ? Math.min(EgmpTelemetry.MAX_CELLS, expectedCount) : 192;
        int pop = 0;
        float lo = 99.0f;
        float hi = 0.0f;
        float sum = 0.0f;
        if (sourceCells != null) {
            int limit = Math.min(cells.length, sourceCells.length);
            for (int i = 0; i < limit; i++) {
                float v = sourceCells[i];
                cells[i] = v;
                if (i < this.expectedCells && !Float.isNaN(v) && v > 1.5f) {
                    pop++;
                    sum += v;
                    if (v < lo) lo = v;
                    if (v > hi) hi = v;
                }
            }
        }
        this.populatedCount = pop;
        if (pop > 0) {
            this.minV = lo;
            this.maxV = hi;
            this.meanV = sum / (float) pop;
        } else {
            this.minV = Float.NaN;
            this.maxV = Float.NaN;
            this.meanV = Float.NaN;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 16 || h <= 16) return;

        float pad = 10.0f;
        canvas.drawRect(1, 1, w - 1, h - 1, borderPaint);

        if (populatedCount == 0) {
            canvas.drawText(
                    "Min/Max cell voltages update continuously from BMS 220101. Tap 'Scan All Cells' above for full 192-cell balance map.",
                    pad + 8.0f,
                    h * 0.52f,
                    textPaint);
            return;
        }

        // Render in 2 horizontal banks (e.g. Cells 1..96 top bank, 97..192 bottom bank)
        int cols = (expectedCells + 1) / 2;
        if (cols < 1) cols = 1;
        float rowGap = 14.0f;
        float availH = h - pad * 2.0f - rowGap;
        float bankH = availH * 0.5f;
        float cellStep = (w - pad * 2.0f) / (float) cols;
        float barW = Math.max(1.5f, cellStep - 1.5f);

        float vFloor = Math.max(2.8f, minV - 0.03f);
        float vCeil = Math.max(vFloor + 0.06f, maxV + 0.02f);

        for (int idx = 0; idx < expectedCells; idx++) {
            int bank = idx / cols;
            int col = idx % cols;
            float left = pad + col * cellStep;
            float topBank = pad + bank * (bankH + rowGap);
            float bottomBank = topBank + bankH;

            canvas.drawRect(left, topBank, left + barW, bottomBank, slotBgPaint);

            float v = cells[idx];
            if (Float.isNaN(v) || v <= 1.5f) continue;

            float norm = (v - vFloor) / (vCeil - vFloor);
            if (norm < 0.08f) norm = 0.08f;
            if (norm > 1.0f) norm = 1.0f;
            float barTop = bottomBank - norm * bankH;

            float devMv = Math.abs(v - meanV) * 1000.0f;
            Paint p = barOkPaint;
            if (devMv > 25.0f) {
                p = barAlertPaint;
            } else if (devMv > 12.0f) {
                p = barWarnPaint;
            }
            canvas.drawRect(left, barTop, left + barW, bottomBank, p);
        }
    }
}
