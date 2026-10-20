package com.worklog.pointcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
    private Store store;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private TextView nextNum, todayText, modeText, dayCount, emptyText;
    private Button shootBtn, shareBtn, deleteDayBtn;
    private LinearLayout settingsPanel, photoList;
    private Spinner daySpinner;

    private final List<String> days = new ArrayList<>();
    private String selectedDay;

    // 拍照畫面在背景存好照片時，主畫面跟著更新
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if ("records".equals(key)) renderAll();
        else if ("next".equals(key)) paintBoard();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);
        migrateSettings();

        nextNum = findViewById(R.id.nextNum);
        todayText = findViewById(R.id.todayText);
        modeText = findViewById(R.id.modeText);
        dayCount = findViewById(R.id.dayCount);
        emptyText = findViewById(R.id.emptyText);
        shootBtn = findViewById(R.id.shootBtn);
        shareBtn = findViewById(R.id.shareBtn);
        deleteDayBtn = findViewById(R.id.deleteDayBtn);
        settingsPanel = findViewById(R.id.settingsPanel);
        photoList = findViewById(R.id.photoList);
        daySpinner = findViewById(R.id.daySpinner);

        shootBtn.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        findViewById(R.id.pointBox).setOnClickListener(v -> store.editPointDialog(this, this::paintBoard));
        findViewById(R.id.plusBtn).setOnClickListener(v -> { store.setNext(store.next() + 1); paintBoard(); });
        findViewById(R.id.minusBtn).setOnClickListener(v -> { store.setNext(store.next() - 1); paintBoard(); });
        findViewById(R.id.resetBtn).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("確定要把點號重置為 1 嗎？已拍的照片會保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", (d, w) -> { store.setNext(1); paintBoard(); toast("點號已重置為 1"); })
                .show());
        findViewById(R.id.settingsBtn).setOnClickListener(v -> settingsPanel.setVisibility(
                settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        shareBtn.setOnClickListener(v -> shareDay(selectedDay));
        deleteDayBtn.setOnClickListener(v -> confirmDeleteDay(selectedDay));

        RadioGroup modeGroup = findViewById(R.id.modeGroup);
        modeGroup.check(store.isDaily() ? R.id.modeDaily : R.id.modeManual);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            store.sp.edit().putString("mode", id == R.id.modeDaily ? "daily" : "manual").apply();
            paintBoard();
        });
        bindText(findViewById(R.id.projName), "proj");
        bindText(findViewById(R.id.prefix), "prefix");
        bindCheck(findViewById(R.id.stampPoint), "stampPoint");
        bindCheck(findViewById(R.id.stampTime), "stampTime");

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

    /** 舊版只有一個「印字」開關，拆成點號與時間兩個。 */
    private void migrateSettings() {
        SharedPreferences sp = store.sp;
        if (sp.contains("stamp")) {
            boolean on = sp.getBoolean("stamp", true);
            sp.edit().putBoolean("stampPoint", on).putBoolean("stampTime", on).remove("stamp").apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store.rollDay()) {
            selectedDay = Store.dayKey(new Date());
            toast("新的一天，點號已重置為 1");
        }
        store.sp.registerOnSharedPreferenceChangeListener(prefListener);
        paintBoard();
        pruneMissingThenRender();
    }

    @Override
    protected void onPause() {
        store.sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onPause();
    }

    private void paintBoard() {
        int n = store.next();
        nextNum.setText(String.valueOf(n));
        shootBtn.setText("拍照 · " + Store.label(n));
        todayText.setText(new SimpleDateFormat("M/d（E）", Locale.TAIWAN).format(new Date()));
        modeText.setText(store.isDaily() ? "隔日自動重置" : "僅手動重置");
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

    // ---------- 刪除整天 ----------

    private void confirmDeleteDay(String day) {
        List<Record> list = sortedFor(day);
        if (list.isEmpty()) { toast("這天沒有照片"); return; }
        new AlertDialog.Builder(this)
                .setTitle("刪除 " + day + " 的全部照片？")
                .setMessage("共 " + list.size() + " 張，會一併從手機相簿刪除，無法復原。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部刪除", (d, w) -> deleteDay(day, list))
                .show();
    }

    private void deleteDay(String day, List<Record> list) {
        deleteDayBtn.setEnabled(false);
        bg.execute(() -> {
            List<String> gone = new ArrayList<>();
            for (Record r : list) {
                store.deleteUri(Uri.parse(r.uri));
                gone.add(r.uri);
            }
            runOnUiThread(() -> {
                List<Record> keep = new ArrayList<>();
                for (Record x : store.records()) if (!gone.contains(x.uri)) keep.add(x);
                store.saveRecords(keep);
                deleteDayBtn.setEnabled(true);
                toast("已刪除 " + day + " 的 " + gone.size() + " 張照片");
            });
        });
    }

    // ---------- 清單 ----------

    /** 在相簿裡已被刪掉的照片，從紀錄中移除。 */
    private void pruneMissingThenRender() {
        List<Record> snapshot = store.records();
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
                    for (Record r : store.records()) if (!missing.contains(r.uri)) keep.add(r);
                    store.saveRecords(keep); // 觸發 prefListener 重畫
                }
                renderAll();
            });
        });
    }

    private void renderAll() {
        String today = Store.dayKey(new Date());
        TreeSet<String> set = new TreeSet<>(Collections.reverseOrder());
        set.add(today);
        for (Record r : store.records()) set.add(r.day);
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
        for (Record r : store.records()) if (r.day.equals(day)) out.add(r);
        out.sort((a, b) -> a.point != b.point ? Integer.compare(a.point, b.point) : Long.compare(a.ts, b.ts));
        return out;
    }

    private void renderList() {
        List<Record> list = sortedFor(selectedDay);
        dayCount.setText(list.size() + " 張");
        shareBtn.setEnabled(!list.isEmpty());
        shareBtn.setAlpha(list.isEmpty() ? 0.5f : 1f);
        deleteDayBtn.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
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
        pt.setText(Store.label(r.point));
        pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        pt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        pt.setTextColor(getColor(R.color.fg));
        TextView tm = new TextView(this);
        tm.setText(r.time);
        tm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tm.setTextColor(getColor(R.color.muted));
        info.addView(pt);
        info.addView(tm);
        row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.addView(smallBtn("重拍", R.color.fg, v -> startActivity(new Intent(this, CameraActivity.class)
                .putExtra(CameraActivity.EXTRA_RETAKE_URI, r.uri)
                .putExtra(CameraActivity.EXTRA_RETAKE_POINT, r.point))));
        row.addView(smallBtn("刪除", R.color.danger, v -> new AlertDialog.Builder(this)
                .setMessage("刪除 " + r.day + " " + Store.label(r.point) + "？照片也會從手機相簿刪除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (d, w) -> {
                    store.deleteUri(uri);
                    List<Record> keep = new ArrayList<>();
                    for (Record x : store.records()) if (!x.uri.equals(r.uri)) keep.add(x);
                    store.saveRecords(keep);
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
        e.setText(store.sp.getString(key, ""));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { store.sp.edit().putString(key, s.toString()).apply(); }
        });
    }

    private void bindCheck(CheckBox c, String key) {
        c.setChecked(store.sp.getBoolean(key, true));
        c.setOnCheckedChangeListener((b, on) -> store.sp.edit().putBoolean(key, on).apply());
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
