package com.worklog.pointcam;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 把「點 N」、日期時間與工程名稱印在照片左下角，並把方向轉正後寫回原檔。 */
final class Stamper {
    private static final int MAX_SIDE = 4096;

    static void stamp(ContentResolver cr, Uri uri, String big, List<String> lines, String exifDateTime)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("empty image");

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("decode failed");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = cr.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
        }
        int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        if (!bmp.isMutable()) {
            Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, true);
            bmp.recycle();
            bmp = c;
        }

        draw(new Canvas(bmp), bmp.getWidth(), bmp.getHeight(), big, lines);

        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("cannot write");
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            bmp.recycle();
        }

        // 重新寫檔會遺失 EXIF，補回拍攝時間讓相簿排序正確
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
            if (pfd != null) {
                ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateTime);
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateTime);
                exif.saveAttributes();
            }
        } catch (Exception ignored) {
        }
    }

    private static void draw(Canvas c, int w, int h, String big, List<String> lines) {
        float u = Math.max(w, h) / 100f; // 長邊的 1%
        Paint bigP = new Paint(Paint.ANTI_ALIAS_FLAG);
        bigP.setTypeface(Typeface.DEFAULT_BOLD);
        bigP.setTextSize(u * 5.5f);
        bigP.setColor(Color.rgb(255, 210, 90));
        Paint smallP = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallP.setTextSize(u * 2.4f);
        smallP.setColor(Color.WHITE);

        float bw = bigP.measureText(big);
        for (String t : lines) bw = Math.max(bw, smallP.measureText(t));
        float pad = u * 1.6f;
        float lineH = u * 3.2f;
        float bh = u * 5.5f + lines.size() * lineH + pad * 2;
        float x = u * 2, y = h - bh - u * 2;

        Paint bg = new Paint();
        bg.setColor(Color.argb(200, 20, 36, 30));
        c.drawRect(x, y, x + bw + pad * 2, y + bh, bg);

        Paint.FontMetrics fm = bigP.getFontMetrics();
        c.drawText(big, x + pad, y + pad - fm.ascent * 0.95f, bigP);
        Paint.FontMetrics sm = smallP.getFontMetrics();
        float ty = y + pad + u * 6.2f;
        for (String t : lines) {
            c.drawText(t, x + pad, ty - sm.ascent * 0.9f, smallP);
            ty += lineH;
        }
    }
}
