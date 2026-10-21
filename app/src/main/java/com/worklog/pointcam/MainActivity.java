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
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioGroup;
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

    private TextView nextNum, todayText, modeText, shootSub, dayCount, shareText, sizeNote;
    private View settingsPanel, dayActions, emptyBox, deleteDayBtn;
    private LinearLayout dayChips, photoGrid;
    private ImageButton settingsBtn;

    private final List<String> days = new ArrayList<>();
    private String selectedDay;
    private String animatedDay; // 換日期時卡片才做進場動畫
    private int shownNext = -1;

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
        shootSub = findViewById(R.id.shootSub);
        dayCount = findViewById(R.id.dayCount);
        shareText = findViewById(R.id.shareText);
        sizeNote = findViewById(R.id.sizeNote);
        settingsPanel = findViewById(R.id.settingsPanel);
        settingsBtn = findViewById(R.id.settingsBtn);
        dayActions = findViewById(R.id.dayActions);
        emptyBox = findViewById(R.id.emptyBox);
        deleteDayBtn = findViewById(R.id.deleteDayBtn);
        dayChips = findViewById(R.id.dayChips);
        photoGrid = findViewById(R.id.photoGrid);

        findViewById(R.id.shootBtn).setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        findViewById(R.id.resultBtn).setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)
                .putExtra(CameraActivity.EXTRA_MODE, "result")));
        findViewById(R.id.pointBox).setOnClickListener(v -> store.editPointDialog(this, this::paintBoard));
        findViewById(R.id.plusBtn).setOnClickListener(v -> { store.setNext(store.next() + 1); paintBoard(); });
        findViewById(R.id.minusBtn).setOnClickListener(v -> { store.setNext(store.next() - 1); paintBoard(); });
        findViewById(R.id.resetBtn).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("確定要把點號重置為 1 嗎？已拍的照片會保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", (d, w) -> { store.setNext(1); paintBoard(); toast("點號已重置為 1"); })
                .show());
        settingsBtn.setOnClickListener(v -> {
            boolean open = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(open ? View.VISIBLE : View.GONE);
            settingsBtn.animate().rotation(open ? 90 : 0).setDuration(220).start();
        });
        findViewById(R.id.shareBtn).setOnClickListener(v -> shareDay(selectedDay));
        deleteDayBtn.setOnClickListener(v -> confirmDeleteDay(selectedDay));

        RadioGroup modeGroup = findViewById(R.id.modeGroup);
        modeGroup.check(store.isDaily() ? R.id.modeDaily : R.id.modeManual);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            store.sp.edit().putString("mode", id == R.id.modeDaily ? "daily" : "manual").apply();
            paintBoard();
        });
        RadioGroup sizeGroup = findViewById(R.id.sizeGroup);
        String size = store.sp.getString("size", "small");
        sizeGroup.check("medium".equals(size) ? R.id.sizeMedium
                : "original".equals(size) ? R.id.sizeOriginal : R.id.sizeSmall);
        paintSizeNote();
        sizeGroup.setOnCheckedChangeListener((g, id) -> {
            store.sp.edit().putString("size", id == R.id.sizeMedium ? "medium"
                    : id == R.id.sizeOriginal ? "original" : "small").apply();
            paintSizeNote();
        });
        bindText(findViewById(R.id.projName), "proj");
        bindText(findViewById(R.id.prefix), "prefix");
        bindCheck(findViewById(R.id.stampPoint), "stampPoint");
        bindCheck(findViewById(R.id.stampTime), "stampTime");
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
        if (shownNext != -1 && shownNext != n) {
            nextNum.setScaleX(1.12f);
            nextNum.setScaleY(1.12f);
            nextNum.animate().scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        shownNext = n;
        shootSub.setText(Store.label(n));
        todayText.setText(new SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN).format(new Date()));
        modeText.setText(store.isDaily() ? "隔日自動重置" : "僅手動重置");
    }

    private void paintSizeNote() {
        switch (store.sp.getString("size", "small")) {
            case "medium":
                sizeNote.setText("長邊 2400 像素，每張約 0.7–1.2 MB，放大看細節也清楚。");
                break;
            case "original":
                sizeNote.setText("相機原始解析度，每張約 3–6 MB，上傳較慢。");
                break;
            default:
                sizeNote.setText("長邊 1600 像素，每張約 0.3–0.5 MB，文字與現場細節仍清楚可辨，上傳最快。");
        }
    }

    // ---------- 分享與刪除 ----------

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

    private void deleteOne(Record r) {
        new AlertDialog.Builder(this)
                .setMessage("刪除 " + r.day + " " + Store.labelFor(r) + "？照片也會從手機相簿刪除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (d, w) -> {
                    store.deleteUri(Uri.parse(r.uri));
                    List<Record> keep = new ArrayList<>();
                    for (Record x : store.records()) if (!x.uri.equals(r.uri)) keep.add(x);
                    store.saveRecords(keep);
                })
                .show();
    }

    private void retake(Record r) {
        Intent i = new Intent(this, CameraActivity.class)
                .putExtra(CameraActivity.EXTRA_RETAKE_URI, r.uri)
                .putExtra(CameraActivity.EXTRA_RETAKE_POINT, r.point);
        if (r.isResult()) i.putExtra(CameraActivity.EXTRA_MODE, "result");
        startActivity(i);
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
        renderChips();
        renderList();
    }

    private void renderChips() {
        String today = Store.dayKey(new Date());
        dayChips.removeAllViews();
        for (String d : days) {
            int n = store.countFor(d);
            TextView chip = new TextView(this);
            String name = d.equals(today) ? "今天" : d.substring(5).replace("-", "/").replaceFirst("^0", "");
            chip.setText(n > 0 ? name + "  ·  " + n : name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(getColorStateList(R.color.chip_text));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setSelected(d.equals(selectedDay));
            chip.setOnClickListener(v -> {
                if (d.equals(selectedDay)) return;
                selectedDay = d;
                for (int i = 0; i < dayChips.getChildCount(); i++) {
                    dayChips.getChildAt(i).setSelected(days.get(i).equals(d));
                }
                renderList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.rightMargin = dp(8);
            dayChips.addView(chip, lp);
        }
    }

    private List<Record> sortedFor(String day) {
        List<Record> out = new ArrayList<>();
        for (Record r : store.records()) if (r.day.equals(day)) out.add(r);
        // 點號照片依點號排，成果圖排在最後
        out.sort((a, b) -> {
            if (a.isResult() != b.isResult()) return a.isResult() ? 1 : -1;
            return a.point != b.point ? Integer.compare(a.point, b.point) : Long.compare(a.ts, b.ts);
        });
        return out;
    }

    private void renderList() {
        List<Record> list = sortedFor(selectedDay);
        int results = 0;
        for (Record r : list) if (r.isResult()) results++;
        int points = list.size() - results;
        dayCount.setText(list.isEmpty() ? "" : "點號 " + points + " 張 · 成果圖 " + results + " 張");
        shareText.setText("分享全部 " + list.size() + " 張");
        dayActions.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        emptyBox.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

        boolean animate = !selectedDay.equals(animatedDay);
        animatedDay = selectedDay;
        photoGrid.removeAllViews();
        int cardW = (getResources().getDisplayMetrics().widthPixels - dp(36) - dp(12)) / 2;
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(12);
                photoGrid.addView(row, rp);
            }
            View card = card(list.get(i), cardW);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cardW, cardW);
            if (i % 2 == 1) cp.leftMargin = dp(12);
            row.addView(card, cp);
            if (animate) {
                card.setAlpha(0f);
                card.setTranslationY(dp(18));
                card.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 12) * 35L)
                        .setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
    }

    private View card(Record r, int size) {
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.bg_photo);
        card.setClipToOutline(true);
        card.setElevation(dp(2));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Uri uri = Uri.parse(r.uri);
        bg.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(size, size), null);
                runOnUiThread(() -> {
                    img.setImageBitmap(b);
                    img.setAlpha(0f);
                    img.animate().alpha(1f).setDuration(200).start();
                });
            } catch (Exception ignored) {
            }
        });
        card.setOnClickListener(v -> {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "image/jpeg");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(view); } catch (ActivityNotFoundException ignored) { }
        });

        // 左下角：點號或成果圖 + 時間
        LinearLayout tag = new LinearLayout(this);
        tag.setOrientation(LinearLayout.VERTICAL);
        tag.setBackgroundResource(r.isResult() ? R.drawable.bg_overlay_result : R.drawable.bg_overlay_chip);
        tag.setPadding(dp(10), dp(4), dp(10), dp(5));
        TextView label = new TextView(this);
        label.setText(Store.labelFor(r));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, r.isResult() ? 15 : 18);
        label.setTypeface(getResources().getFont(R.font.barlow_bold));
        label.setTextColor(r.isResult() ? getColor(R.color.amber_fg) : getColor(R.color.amber));
        TextView time = new TextView(this);
        time.setText(r.time.substring(0, 5));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        time.setTextColor(r.isResult() ? getColor(R.color.amber_fg) : 0xE6FFFFFF);
        tag.addView(label);
        tag.addView(time);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        tp.setMargins(dp(8), 0, 0, dp(8));
        card.addView(tag, tp);

        // 右上角：更多（重拍、刪除）
        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_more);
        more.setColorFilter(0xFFFFFFFF);
        more.setBackgroundResource(R.drawable.bg_overlay_btn);
        more.setContentDescription("更多");
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add(0, 1, 0, "重拍");
            pm.getMenu().add(0, 2, 1, "刪除");
            pm.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) retake(r);
                else deleteOne(r);
                return true;
            });
            pm.show();
        });
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
        mp.setMargins(0, dp(6), dp(6), 0);
        card.addView(more, mp);
        return card;
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

    private void bindCheck(CompoundButton c, String key) {
        c.setChecked(store.sp.getBoolean(key, true));
        c.setOnCheckedChangeListener((b, on) -> store.sp.edit().putBoolean(key, on).apply());
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
