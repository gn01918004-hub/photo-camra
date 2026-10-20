package com.worklog.pointcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final String ALBUM = "工作照片";

    private SharedPreferences sp;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private TextView nextNum, todayText, modeText, dayCount, emptyText;
    private Button shootBtn, shareBtn;
    private LinearLayout settingsPanel, photoList;
    private Spinner daySpinner;

    private List<Record> records = new ArrayList<>();
    private final List<String> days = new ArrayList<>();
    private String selectedDay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("pointcam", MODE_PRIVATE);
        records = Record.parseList(sp.getString("records", "[]"));

        nextNum = findViewById(R.id.nextNum);
        todayText = findViewById(R.id.todayText);
        modeText = findViewById(R.id.modeText);
        dayCount = findViewById(R.id.dayCount);
        emptyText = findViewById(R.id.emptyText);
        shootBtn = findViewById(R.id.shootBtn);
        shareBtn = findViewById(R.id.shareBtn);
        settingsPanel = findViewById(R.id.settingsPanel);
        photoList = findViewById(R.id.photoList);
        daySpinner = findViewById(R.id.daySpinner);

        shootBtn.setOnClickListener(v -> startCapture(null));
        findViewById(R.id.plusBtn).setOnClickListener(v -> setNext(next() + 1));
        findViewById(R.id.minusBtn).setOnClickListener(v -> { if (next() > 1) setNext(next() - 1); });
        findViewById(R.id.resetBtn).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("確定要把點號重置為 1 嗎？已拍的照片會保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", (d, w) -> { setNext(1); toast("點號已重置為 1"); })
                .show());
        findViewById(R.id.settingsBtn).setOnClickListener(v -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        shareBtn.setOnClickListener(v -> shareDay(selectedDay));

        RadioGroup modeGroup = findViewById(R.id.modeGroup);
        modeGroup.check(isDaily() ? R.id.modeDaily : R.id.modeManual);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            sp.edit().putString("mode", id == R.id.modeDaily ? "daily" : "manual").apply();
            paintBoard();
        });
        bindText(findViewById(R.id.projName), "proj");
        bindText(findViewById(R.id.prefix), "prefix");
        CheckBox stampOn = findViewById(R.id.stampOn);
        stampOn.setChecked(sp.getBoolean("stamp", true));
        stampOn.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean("stamp", c).apply());

        daySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < days.size() && !days.get(pos).equals(selectedDay)) {
                    selectedDay = days.get(pos);
                    renderList();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rollDay();
        paintBoard();
        pruneMissingThenRender();
    }

    // ---------- 點號與重置 ----------

    private int next() { return sp.getInt("next", 1); }

    private void setNext(int n) {
        sp.edit().putInt("next", Math.max(1, n)).apply();
        paintBoard();
    }

    private boolean isDaily() { return "daily".equals(sp.getString("mode", "daily")); }

    /** 隔日自動重置：日期換了就把點號歸 1。 */
    private void rollDay() {
        String today = dayKey(new Date());
        String last = sp.getString("lastDay", null);
        if (!today.equals(last)) {
            SharedPreferences.Editor e = sp.edit().putString("lastDay", today);
            if (last != null && isDaily() && next() != 1) {
                e.putInt("next", 1);
                toast("新的一天，點號已重置為 1");
            }
            e.apply();
            selectedDay = today;
        }
    }

    private void paintBoard() {
        int n = next();
        nextNum.setText(String.valueOf(n));
        shootBtn.setText("拍照 · 點 " + n);
        todayText.setText(new SimpleDateFormat("M/d（E）", Locale.TAIWAN).format(new Date()));
        modeText.setText(isDaily() ? "隔日自動重置" : "僅手動重置");
    }

    // ---------- 拍照 ----------

    /** retake 為 null 時拍新的一點，否則重拍該點。 */
    private void startCapture(Record retake) {
        rollDay();
        Date now = new Date();
        String day = retake != null ? retake.day : dayKey(now);
        int point = retake != null ? retake.point : next();
        Uri uri = insertImage(fileName(day, point) , day);
        if (uri == null) { toast("無法在相簿建立照片，請確認手機儲存空間"); return; }

        sp.edit()
                .putString("pendingUri", uri.toString())
                .putString("pendingDay", day)
                .putInt("pendingPoint", point)
                .putString("pendingRetake", retake != null ? retake.uri : null)
                .apply();

        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            deleteUri(uri);
            clearPending();
            toast("找不到相機 App");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        // 從 SharedPreferences 讀回，拍照時 App 被系統關掉也不會遺失
        String us = sp.getString("pendingUri", null);
        if (us == null) return;
        Uri uri = Uri.parse(us);
        String day = sp.getString("pendingDay", dayKey(new Date()));
        int point = sp.getInt("pendingPoint", 1);
        String retakeUri = sp.getString("pendingRetake", null);
        clearPending();

        if (resultCode != RESULT_OK || !hasContent(uri)) {
            deleteUri(uri);
            return;
        }

        Date now = new Date();
        boolean stamp = sp.getBoolean("stamp", true);
        String proj = sp.getString("proj", "").trim();
        toast("點 " + point + " 處理中…");
        shootBtn.setEnabled(false);
        bg.execute(() -> {
            String err = null;
            if (stamp) {
                List<String> lines = new ArrayList<>();
                if (!proj.isEmpty()) lines.add(proj);
                lines.add(day + "  " + timeStr(now));
                try {
                    Stamper.stamp(getContentResolver(), uri, "點 " + point, lines,
                            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now));
                } catch (Throwable t) {
                    err = "照片已存，但印字失敗";
                }
            }
            if (retakeUri != null) {
                deleteUri(Uri.parse(retakeUri));
                // 刪掉舊檔後把新檔改回原本的檔名（避免出現「(1)」）
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName(day, point));
                try { getContentResolver().update(uri, v, null, null); } catch (Exception ignored) { }
            }
            String msg = err;
            runOnUiThread(() -> {
                shootBtn.setEnabled(true);
                Record r = new Record();
                r.uri = uri.toString();
                r.day = day;
                r.point = point;
                r.time = timeStr(now);
                r.ts = now.getTime();
                if (retakeUri != null) {
                    for (int k = 0; k < records.size(); k++) {
                        if (records.get(k).uri.equals(retakeUri)) { records.remove(k); break; }
                    }
                } else {
                    sp.edit().putInt("next", point + 1).putString("lastDay", dayKey(now)).apply();
                }
                records.add(r);
                saveRecords();
                selectedDay = day;
                paintBoard();
                renderAll();
                toast(msg != null ? msg : (retakeUri != null ? "點 " + point + " 已重拍" : "點 " + point + " 已存入相簿"));
            });
        });
    }

    private void clearPending() {
        sp.edit().remove("pendingUri").remove("pendingDay").remove("pendingPoint").remove("pendingRetake").apply();
    }

    private Uri insertImage(String name, String day) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM + "/" + day.replace("-", ""));
        try {
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasContent(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in != null && in.read() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteUri(Uri uri) {
        try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    private String fileName(String day, int point) {
        String pre = sp.getString("prefix", "").trim().replaceAll("[\\\\/:*?\"<>|]", "");
        return (pre.isEmpty() ? "" : pre + "_") + day.replace("-", "")
                + "_點" + String.format(Locale.US, "%02d", point) + ".jpg";
    }

    // ---------- 分享 ----------

    private void shareDay(String day) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Record r : sortedFor(day)) uris.add(Uri.parse(r.uri));
        if (uris.isEmpty()) { toast("這天還沒有照片"); return; }
        Intent s = new Intent(Intent.ACTION_SEND_MULTIPLE);
        s.setType("image/jpeg");
        s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "分享 " + day + " 的 " + uris.size() + " 張照片"));
    }

    // ---------- 清單 ----------

    private void saveRecords() { sp.edit().putString("records", Record.toJsonList(records)).apply(); }

    /** 在相簿裡已被刪掉的照片，從紀錄中移除。 */
    private void pruneMissingThenRender() {
        List<Record> snapshot = new ArrayList<>(records);
        bg.execute(() -> {
            List<String> missing = new ArrayList<>();
            ContentResolver cr = getContentResolver();
            for (Record r : snapshot) {
                try (Cursor c = cr.query(Uri.parse(r.uri), new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
                    if (c == null || !c.moveToFirst()) missing.add(r.uri);
                } catch (Exception e) {
                    missing.add(r.uri);
                }
            }
            runOnUiThread(() -> {
                if (!missing.isEmpty()) {
                    List<Record> keep = new ArrayList<>();
                    for (Record r : records) if (!missing.contains(r.uri)) keep.add(r);
                    records = keep;
                    saveRecords();
                }
                renderAll();
            });
        });
    }

    private void renderAll() {
        String today = dayKey(new Date());
        TreeSet<String> set = new TreeSet<>(Collections.reverseOrder());
        set.add(today);
        for (Record r : records) set.add(r.day);
        days.clear();
        days.addAll(set);
        if (selectedDay == null || !days.contains(selectedDay)) selectedDay = today;

        List<String> labels = new ArrayList<>();
        for (String d : days) labels.add(d.equals(today) ? "今天 " + d : d);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        daySpinner.setAdapter(ad);
        daySpinner.setSelection(days.indexOf(selectedDay), false);
        renderList();
    }

    private List<Record> sortedFor(String day) {
        List<Record> out = new ArrayList<>();
        for (Record r : records) if (r.day.equals(day)) out.add(r);
        out.sort((a, b) -> a.point != b.point ? Integer.compare(a.point, b.point) : Long.compare(a.ts, b.ts));
        return out;
    }

    private void renderList() {
        List<Record> list = sortedFor(selectedDay);
        dayCount.setText(list.size() + " 張");
        shareBtn.setEnabled(!list.isEmpty());
        shareBtn.setAlpha(list.isEmpty() ? 0.5f : 1f);
        emptyText.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        photoList.removeAllViews();
        for (Record r : list) photoList.addView(row(r));
    }

    private View row(Record r) {
        int pad = dp(10);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_panel);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(getColor(R.color.line));
        row.addView(img, new LinearLayout.LayoutParams(dp(96), dp(72)));
        Uri uri = Uri.parse(r.uri);
        bg.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(dp(192), dp(144)), null);
                runOnUiThread(() -> img.setImageBitmap(b));
            } catch (Exception ignored) {
            }
        });
        img.setOnClickListener(v -> {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "image/jpeg");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(view); } catch (ActivityNotFoundException ignored) { }
        });

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(4), 0);
        TextView pt = new TextView(this);
        pt.setText("點 " + r.point);
        pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        pt.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        pt.setTextColor(getColor(R.color.fg));
        TextView tm = new TextView(this);
        tm.setText(r.time);
        tm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tm.setTextColor(getColor(R.color.muted));
        info.addView(pt);
        info.addView(tm);
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.addView(smallBtn("重拍", R.color.fg, v -> startCapture(r)));
        row.addView(smallBtn("刪除", R.color.danger, v -> new AlertDialog.Builder(this)
                .setMessage("刪除 " + r.day + " 點 " + r.point + "？照片也會從手機相簿刪除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (d, w) -> {
                    deleteUri(uri);
                    records.remove(r);
                    saveRecords();
                    renderAll();
                })
                .show()));
        return row;
    }

    private Button smallBtn(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(getColor(color));
        b.setBackgroundResource(R.drawable.bg_btn);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 小工具 ----------

    private void bindText(EditText e, String key) {
        e.setText(sp.getString(key, ""));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { sp.edit().putString(key, s.toString()).apply(); }
        });
    }

    private static String dayKey(Date d) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d); }

    private static String timeStr(Date d) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(d); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
