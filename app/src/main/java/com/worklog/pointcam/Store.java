package com.worklog.pointcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 兩個畫面共用的設定、點號與照片紀錄。 */
final class Store {
    static final String ALBUM = "工作照片";

    final SharedPreferences sp;
    private final Context ctx;

    Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences("pointcam", Context.MODE_PRIVATE);
    }

    // ---------- 點號 ----------

    int next() { return sp.getInt("next", 1); }

    void setNext(int n) { sp.edit().putInt("next", Math.max(1, n)).apply(); }

    boolean isDaily() { return "daily".equals(sp.getString("mode", "daily")); }

    /** 隔日自動重置：日期換了就把點號歸 1。回傳是否有重置。 */
    boolean rollDay() {
        String today = dayKey(new Date());
        String last = sp.getString("lastDay", null);
        if (today.equals(last)) return false;
        boolean reset = last != null && isDaily() && next() != 1;
        SharedPreferences.Editor e = sp.edit().putString("lastDay", today);
        if (reset) e.putInt("next", 1);
        e.apply();
        return reset;
    }

    static String label(int point) { return "點號:" + point; }

    /** 跳出輸入框直接修改目前點號。 */
    void editPointDialog(Activity a, Runnable onChanged) {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(next()));
        input.setSelectAllOnFocus(true);
        input.setTextSize(28);
        input.setGravity(Gravity.CENTER);
        FrameLayout box = new FrameLayout(a);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("修改點號")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", (dlg, w) -> {
                    try {
                        int n = Integer.parseInt(input.getText().toString().trim());
                        if (n < 1) throw new NumberFormatException();
                        setNext(n);
                        onChanged.run();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(a, "請輸入 1 以上的數字", Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        input.requestFocus();
    }

    // ---------- 照片紀錄 ----------

    List<Record> records() { return Record.parseList(sp.getString("records", "[]")); }

    synchronized void saveRecords(List<Record> list) { sp.edit().putString("records", Record.toJsonList(list)).apply(); }

    /** 新增一筆紀錄；replaceUri 不為 null 時取代那一筆（重拍）。 */
    synchronized void addRecord(Record r, String replaceUri) {
        List<Record> list = records();
        if (replaceUri != null) {
            for (int k = 0; k < list.size(); k++) {
                if (list.get(k).uri.equals(replaceUri)) { list.remove(k); break; }
            }
        }
        list.add(r);
        saveRecords(list);
    }

    int countFor(String day) {
        int n = 0;
        for (Record r : records()) if (r.day.equals(day)) n++;
        return n;
    }

    // ---------- 檔案 ----------

    /** 檔名預設就是點號，例如 3.jpg；有前綴時為 A區_3.jpg。 */
    String fileName(int point) {
        String pre = sp.getString("prefix", "").trim().replaceAll("[\\\\/:*?\"<>|]", "");
        return (pre.isEmpty() ? "" : pre + "_") + point + ".jpg";
    }

    Uri insertImage(String name, String day) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM + "/" + day.replace("-", ""));
        try {
            return ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 存一張剛拍好的照片：寫入相簿、依設定印字。
     * replaceUri 不為 null 時會刪掉舊檔並沿用原檔名（重拍）。
     */
    Record savePhoto(byte[] jpeg, int point, Date when, String replaceUri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String day = dayKey(when);
        if (replaceUri != null) {
            // 重拍沿用原本那一天
            for (Record r : records()) if (r.uri.equals(replaceUri)) { day = r.day; break; }
        }
        Uri uri = insertImage(fileName(point), day);
        if (uri == null) throw new IOException("無法在相簿建立照片");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("無法寫入照片");
            out.write(jpeg);
        }

        boolean stampPoint = sp.getBoolean("stampPoint", true);
        boolean stampTime = sp.getBoolean("stampTime", true);
        String proj = sp.getString("proj", "").trim();
        List<String> lines = new ArrayList<>();
        if (!proj.isEmpty()) lines.add(proj);
        if (stampTime) lines.add(dayKey(when) + "  " + timeStr(when));
        if (stampPoint || !lines.isEmpty()) {
            try {
                Stamper.stamp(cr, uri, stampPoint ? label(point) : null, lines,
                        new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(when));
            } catch (Throwable t) {
                // 印字失敗時保留原始照片
            }
        }

        if (replaceUri != null) {
            deleteUri(Uri.parse(replaceUri));
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName(point));
            try { cr.update(uri, v, null, null); } catch (Exception ignored) { }
        }

        Record r = new Record();
        r.uri = uri.toString();
        r.day = day;
        r.point = point;
        r.time = timeStr(when);
        r.ts = when.getTime();
        addRecord(r, replaceUri);
        return r;
    }

    void deleteUri(Uri uri) {
        try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    static String dayKey(Date d) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d); }

    static String timeStr(Date d) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(d); }
}
