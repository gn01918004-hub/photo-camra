package com.worklog.pointcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 主卡背景：測量藍圖格線，加上右下角的測點符號。 */
public class BlueprintView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public BlueprintView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        grid.setColor(0x14FFFFFF);
        grid.setStrokeWidth(d);
        mark.setColor(0x24FFFFFF);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(2 * d);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float step = 22 * d;
        for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, grid);
        for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, grid);

        float cx = w - 54 * d, cy = h - 50 * d, r = 34 * d;
        c.drawCircle(cx, cy, r, mark);
        c.drawCircle(cx, cy, r * 0.45f, mark);
        c.drawLine(cx - r * 1.5f, cy, cx + r * 1.5f, cy, mark);
        c.drawLine(cx, cy - r * 1.5f, cx, cy + r * 1.5f, mark);
    }
}
