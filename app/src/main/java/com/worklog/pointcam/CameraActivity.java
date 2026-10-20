package com.worklog.pointcam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App 內的拍照畫面：連續拍照，右上角顯示目前點號，按返回才回主畫面。 */
public class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final String EXTRA_RETAKE_URI = "retakeUri";
    static final String EXTRA_RETAKE_POINT = "retakePoint";
    private static final int REQ_PERM = 10;

    private Store store;
    private TextureView preview;
    private TextView badge, countText;
    private ImageView lastThumb;
    private Button shutter, editPointBtn;
    private View flash;

    private HandlerThread camThread;
    private Handler camHandler;
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size previewSize, jpegSize;
    private int sensorOrientation = 90;
    private boolean continuousAf;

    private OrientationEventListener orientationListener;
    private volatile int deviceRotation = 0;
    private MediaActionSound sound;
    private boolean askedPermission;

    private String retakeUri;
    private int retakePoint;
    private boolean capturing;
    private int pendingPoint;
    private Date pendingWhen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        store = new Store(this);
        retakeUri = getIntent().getStringExtra(EXTRA_RETAKE_URI);
        retakePoint = getIntent().getIntExtra(EXTRA_RETAKE_POINT, 1);

        preview = findViewById(R.id.preview);
        badge = findViewById(R.id.pointBadge);
        countText = findViewById(R.id.countText);
        lastThumb = findViewById(R.id.lastThumb);
        shutter = findViewById(R.id.shutterBtn);
        editPointBtn = findViewById(R.id.editPointBtn);
        flash = findViewById(R.id.flash);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        shutter.setOnClickListener(v -> takePicture());
        View.OnClickListener edit = v -> {
            if (retakeUri != null) { toast("重拍時沿用原本的點號"); return; }
            store.editPointDialog(this, this::paint);
        };
        editPointBtn.setOnClickListener(edit);
        badge.setOnClickListener(edit);
        if (retakeUri != null) editPointBtn.setVisibility(View.GONE);

        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) deviceRotation = ((o + 45) / 90 * 90) % 360;
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (retakeUri == null && store.rollDay()) toast("新的一天，點號已重置為 1");
        paint();
        orientationListener.enable();
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
            }
            return;
        }
        if (preview.isAvailable()) openCamera();
        else preview.setSurfaceTextureListener(this);
    }

    @Override
    protected void onPause() {
        closeCamera();
        orientationListener.disable();
        if (camThread != null) {
            camThread.quitSafely();
            try { camThread.join(); } catch (InterruptedException ignored) { }
            camThread = null;
            camHandler = null;
        }
        if (capturing && retakeUri == null && store.next() == pendingPoint + 1) {
            // 快門按下後還沒拿到照片就離開：點號退回
            store.setNext(pendingPoint);
        }
        capturing = false;
        shutter.setEnabled(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saver.shutdown(); // 已排隊的照片會存完
        sound.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERM) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            toast("需要相機權限才能拍照");
            finish();
        }
        // 允許後 onResume 會再執行一次並開啟相機
    }

    private void paint() {
        int p = retakeUri != null ? retakePoint : store.next();
        badge.setText(Store.label(p));
        int n = store.countFor(Store.dayKey(new Date()));
        countText.setText(retakeUri != null ? "重拍" : "今天\n" + n + " 張");
    }

    // ---------- 相機 ----------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void openCamera() {
        if (camera != null || camHandler == null) return;
        CameraManager mgr = getSystemService(CameraManager.class);
        try {
            String id = null;
            for (String c : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null && mgr.getCameraIdList().length > 0) id = mgr.getCameraIdList()[0];
            if (id == null) { toast("找不到相機"); return; }

            CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so != null ? so : 90;
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            continuousAf = false;
            if (afModes != null) {
                for (int m : afModes) {
                    if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) continuousAf = true;
                }
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { toast("相機無法使用"); return; }
            jpegSize = pickJpeg(map.getOutputSizes(ImageFormat.JPEG));
            previewSize = pickPreview(map.getOutputSizes(SurfaceTexture.class), jpegSize);
            layoutPreview();

            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImage, camHandler);

            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; startPreview(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int error) {
                    d.close();
                    camera = null;
                    runOnUiThread(() -> toast("相機無法開啟，可能被其他 App 使用中"));
                }
            }, camHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            toast("相機無法開啟");
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            if (continuousAf) {
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (camera == null) return;
                            session = s;
                            try {
                                s.setRepeatingRequest(req.build(), null, camHandler);
                            } catch (CameraAccessException | IllegalStateException ignored) {
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> toast("相機預覽失敗"));
                        }
                    }, camHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> toast("相機預覽失敗"));
        }
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    private void takePicture() {
        if (session == null || camera == null || capturing) return;
        capturing = true;
        shutter.setEnabled(false);
        pendingPoint = retakeUri != null ? retakePoint : store.next();
        pendingWhen = new Date();
        if (retakeUri == null) store.setNext(pendingPoint + 1);
        paint();
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            if (continuousAf) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + deviceRotation) % 360);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    runOnUiThread(() -> captureFailed());
                }
            }, camHandler);
            sound.play(MediaActionSound.SHUTTER_CLICK);
            flash.setAlpha(0.8f);
            flash.animate().alpha(0f).setDuration(220).start();
        } catch (CameraAccessException | IllegalStateException e) {
            captureFailed();
        }
    }

    private void captureFailed() {
        if (retakeUri == null && store.next() == pendingPoint + 1) store.setNext(pendingPoint);
        capturing = false;
        shutter.setEnabled(true);
        paint();
        toast("拍照失敗，請再試一次");
    }

    /** 相機執行緒：拿到 JPEG 後交給存檔執行緒，快門立刻可以再按。 */
    private void onImage(ImageReader r) {
        byte[] bytes;
        try (Image img = r.acquireNextImage()) {
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } catch (IllegalStateException e) {
            return;
        }
        int point = pendingPoint;
        Date when = pendingWhen;
        String replace = retakeUri;
        runOnUiThread(() -> {
            if (replace == null) {
                capturing = false;
                shutter.setEnabled(true);
            }
        });
        saver.execute(() -> {
            try {
                Record rec = store.savePhoto(bytes, point, when, replace);
                Bitmap thumb = null;
                try {
                    thumb = getContentResolver().loadThumbnail(Uri.parse(rec.uri), new Size(240, 240), null);
                } catch (Exception ignored) {
                }
                Bitmap t = thumb;
                runOnUiThread(() -> {
                    if (t != null) lastThumb.setImageBitmap(t);
                    paint();
                    if (replace != null) {
                        toast(Store.label(point) + " 已重拍");
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(Store.label(point) + " 存檔失敗：" + e.getMessage()));
            }
        });
    }

    // ---------- 尺寸 ----------

    private static boolean is43(Size s) {
        return Math.abs((float) s.getWidth() / s.getHeight() - 4f / 3f) < 0.02f;
    }

    /** 照片：4:3 中長邊不超過 4096 的最大尺寸。 */
    private static Size pickJpeg(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (!is43(s) || Math.max(s.getWidth(), s.getHeight()) > 4096) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) if (best == null || area(s) > area(best)) best = s;
        return best;
    }

    /** 預覽：與照片同比例、不超過 1920x1440 的最大尺寸。 */
    private static Size pickPreview(Size[] sizes, Size jpeg) {
        float ratio = (float) jpeg.getWidth() / jpeg.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (Math.abs((float) s.getWidth() / s.getHeight() - ratio) > 0.02f) continue;
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private static long area(Size s) { return (long) s.getWidth() * s.getHeight(); }

    /** 直式畫面：預覽寬度填滿，高度依比例，置中。 */
    private void layoutPreview() {
        runOnUiThread(() -> {
            View parent = (View) preview.getParent();
            int W = parent.getWidth(), H = parent.getHeight();
            if (W == 0 || H == 0 || previewSize == null) return;
            float ratio = (float) previewSize.getWidth() / previewSize.getHeight(); // 橫向寬/高
            int w = W, h = Math.round(W * ratio);
            if (h > H) { h = H; w = Math.round(H / ratio); }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
            lp.width = w;
            lp.height = h;
            preview.setLayoutParams(lp);
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
