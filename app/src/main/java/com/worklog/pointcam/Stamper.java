package com.worklog.pointcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 把相機拍到的 JPEG 縮小到指定長邊、轉正方向，依需要在左下角印上
 * 「點號:N」、日期時間與工程名稱，再重新壓縮。
 */
final class Stamper {

    /**
     * @param big     點號文字；null 表示不印
     * @param lines   點號下方的小字；可為空
     * @param maxSide 長邊像素上限；0 表示維持原尺寸
     */
    static byte[] encode(byte[] jpeg, String big, List<String> lines, int maxSide, int quality)
            throws IOException {
        boolean stamp = big != null || !lines.isEmpty();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int srcLong = Math.max(bounds.outWidth, bounds.outHeight);
        if (srcLong <= 0) throw new IOException("empty image");
        int target = maxSide > 0 ? Math.min(maxSide, srcLong) : srcLong;
        if (!stamp && target == srcLong && maxSide == 0) return jpeg; // 原始且不印字：原檔保存

        // 先用 inSampleSize 粗縮（省記憶體），再精確縮到目標長邊
        int sample = 1;
        while (srcLong / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) throw new IOException("decode failed");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(new ByteArrayInputStream(jpeg)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
        }
        int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;

        float scale = (float) target / Math.max(bmp.getWidth(), bmp.getHeight());
        Matrix m = new Matrix();
        if (scale < 0.999f) m.postScale(scale, scale);
        if (deg != 0) m.postRotate(deg);
        if (!m.isIdentity()) {
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        if (stamp) {
            if (!bmp.isMutable()) {
                Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, true);
                bmp.recycle();
                bmp = c;
            }
            draw(new Canvas(bmp), bmp.getWidth(), bmp.getHeight(), big, lines);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length / 3);
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /** big 為 null 時不印點號，只印其他文字。 */
    private static void draw(Canvas c, int w, int h, String big, List<String> lines) {
        float u = Math.max(w, h) / 100f; // 長邊的 1%
        Paint bigP = new Paint(Paint.ANTI_ALIAS_FLAG);
        bigP.setTypeface(Typeface.DEFAULT_BOLD);
        bigP.setTextSize(u * 5.5f);
        bigP.setColor(Color.rgb(255, 210, 90));
        Paint smallP = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallP.setTextSize(u * 2.4f);
        smallP.setColor(Color.WHITE);

        float bigH = big != null ? u * 6.2f : 0;
        float bw = big != null ? bigP.measureText(big) : 0;
        for (String t : lines) bw = Math.max(bw, smallP.measureText(t));
        float pad = u * 1.6f;
        float lineH = u * 3.2f;
        float bh = bigH + lines.size() * lineH + pad * 2 - (lines.isEmpty() ? u * 0.7f : u * 0.6f);
        float x = u * 2, y = h - bh - u * 2;

        Paint bg = new Paint();
        bg.setColor(Color.argb(200, 20, 36, 30));
        c.drawRect(x, y, x + bw + pad * 2, y + bh, bg);

        if (big != null) {
            Paint.FontMetrics fm = bigP.getFontMetrics();
            c.drawText(big, x + pad, y + pad - fm.ascent * 0.95f, bigP);
        }
        Paint.FontMetrics sm = smallP.getFontMetrics();
        float ty = y + pad + bigH;
        for (String t : lines) {
            c.drawText(t, x + pad, ty - sm.ascent * 0.9f, smallP);
            ty += lineH;
        }
    }
}
