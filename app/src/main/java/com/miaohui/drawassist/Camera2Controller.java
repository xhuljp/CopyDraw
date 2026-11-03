package com.miaohui.drawassist;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.TextureView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native Camera2 viewfinder for the tracing app.
 *
 * The Android WebView only exposes the logical rear camera through
 * navigator.mediaDevices, so the physical ultra wide lens is invisible to the
 * web layer. This controller enumerates every rear facing camera (including the
 * physical sub-cameras of a logical multi camera) and streams the selected one
 * into a TextureView placed underneath a transparent WebView.
 */
class Camera2Controller {

    interface Listener {
        void onCameraReady(JSONObject info);
        void onCameraError(String code, String detail);
        void onFrameCaptured(String value);
    }

    private static final String TAG = "Camera2Controller";
    private static final int MAX_PREVIEW_LONG_EDGE = 1920;
    private static final int MAX_JPEG_LONG_EDGE = 1600;

    private static final class Lens {
        String key;          // opaque id handed to JS ("0" or "3|0")
        String cameraId;     // logical / system camera id used with openCamera
        String physicalId;   // physical sub-camera id, null when not needed
        String label;        // "1×" / "0.6×"
        boolean wide;        // true when clearly wider than the main lens
        float ratio;         // optical zoom ratio relative to the main lens
        float focal;         // focal length in mm
        float sensorWidth;   // physical sensor width in mm
        Rect activeArray;
        Range<Float> zoomRatioRange;   // nullable
        float maxCropZoom = 1f;
        boolean system;      // listed directly by CameraManager
    }

    private final Context context;
    private final TextureView textureView;
    private final Listener listener;
    private final CameraManager cameraManager;
    private final Handler mainHandler;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private final List<Lens> lenses = new ArrayList<>();
    private String mainKey = "";
    private String wideKey = "";
    private float wideRatio = 0.5f;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private Surface previewSurface;
    private Size previewSize;
    private Size jpegSize;

    private Lens activeLens;
    private String requestedKey = "";
    private boolean open;
    private boolean opening;
    private boolean frameAsDataUrl;
    private int jpegOrientation;

    private boolean torchOn;
    private boolean focusLocked;
    private float zoomRel = 1f;
    private float baseRatio = 1f;      // ratio of the active lens relative to main
    private float matrixZoom = 1f;     // extra scale applied on the TextureView
    private Range<Float> zoomRatioRange;
    private Rect activeArray;

    Camera2Controller(Context context, TextureView textureView, Listener listener) {
        this.context = context.getApplicationContext();
        this.textureView = textureView;
        this.listener = listener;
        this.mainHandler = new Handler(context.getMainLooper());
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.cameraThread = new HandlerThread("miaohui-camera");
        this.cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());
        this.textureView.setSurfaceTextureListener(surfaceListener);
        scanLenses();
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            cameraHandler.post(() -> {
                if (!requestedKey.isEmpty() && !open && !opening) openLens(requestedKey);
            });
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            applyTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            cameraHandler.post(() -> closeInternal());
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    // ------------------------------------------------------------------
    // Lens enumeration
    // ------------------------------------------------------------------

    boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    boolean available() {
        return !lenses.isEmpty();
    }

    String lensesJson() {
        JSONArray array = new JSONArray();
        for (Lens lens : lenses) array.put(lensJson(lens));
        return array.toString();
    }

    private JSONObject lensJson(Lens lens) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", lens.key);
            json.put("label", lens.label);
            json.put("wide", lens.wide);
            json.put("ratio", lens.ratio);
            json.put("main", lens.key.equals(mainKey));
        } catch (Exception ignored) {
        }
        return json;
    }

    private void scanLenses() {
        lenses.clear();
        mainKey = "";
        wideKey = "";
        String mainCameraId = null;
        try {
            List<String> systemIds = new ArrayList<>(Arrays.asList(cameraManager.getCameraIdList()));
            for (String id : systemIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                if (mainCameraId == null) mainCameraId = id;
                addLens(id, null, characteristics, true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (String id : systemIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                    Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                    for (String physicalId : physicalIds) {
                        try {
                            CameraCharacteristics physical = cameraManager.getCameraCharacteristics(physicalId);
                            Integer physicalFacing = physical.get(CameraCharacteristics.LENS_FACING);
                            if (physicalFacing != null && physicalFacing != CameraCharacteristics.LENS_FACING_BACK) continue;
                            addLens(id, physicalId, physical, false);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "scanLenses failed", error);
        }
        if (mainCameraId == null) return;

        float mainFov = 0f;
        for (Lens lens : lenses) {
            if (lens.key.equals(mainCameraId)) {
                mainFov = focalLengthToFov(lens);
                break;
            }
        }
        if (mainFov <= 0f) {
            for (Lens lens : lenses) {
                mainFov = focalLengthToFov(lens);
                break;
            }
        }

        List<Lens> keep = new ArrayList<>();
        for (Lens lens : lenses) {
            float fov = focalLengthToFov(lens);
            lens.ratio = fov > 0f && mainFov > 0f
                    ? (float) (Math.tan(mainFov / 2d) / Math.tan(fov / 2d))
                    : 1f;
            if (lens.ratio <= 0f || Float.isNaN(lens.ratio)) lens.ratio = 1f;
            boolean duplicate = false;
            for (Lens existing : keep) {
                if (Math.abs(Math.log(existing.ratio / lens.ratio)) < 0.06d) {
                    // Prefer the system camera over a physical alias of the same lens.
                    if (!existing.system && lens.system) {
                        keep.remove(existing);
                        break;
                    }
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) keep.add(lens);
        }

        final String primaryCameraId = mainCameraId;
        Collections.sort(keep, new Comparator<Lens>() {
            @Override
            public int compare(Lens a, Lens b) {
                boolean aMain = a.key.equals(primaryCameraId);
                boolean bMain = b.key.equals(primaryCameraId);
                if (aMain != bMain) return aMain ? -1 : 1;
                return Float.compare(a.ratio, b.ratio);
            }
        });

        lenses.clear();
        for (Lens lens : keep) {
            if (lens.key.equals(mainCameraId)) {
                lens.ratio = 1f;
                lens.label = "1×";
                mainKey = lens.key;
            } else {
                lens.wide = lens.ratio < 0.95f;
                lens.label = String.format(Locale.US, "%.1f×", lens.ratio);
            }
            lenses.add(lens);
            if (wideKey.isEmpty() && !lens.key.equals(mainKey) && lens.ratio < 0.95f) wideKey = lens.key;
        }
        for (Lens lens : lenses) {
            if (lens.key.equals(wideKey)) wideRatio = Math.max(0.2f, lens.ratio);
        }
        Log.i(TAG, "lenses=" + lensesJson() + " main=" + mainKey + " wide=" + wideKey + " ratio=" + wideRatio);
    }

    private void addLens(String cameraId, String physicalId, CameraCharacteristics characteristics, boolean system) {
        Lens lens = new Lens();
        lens.cameraId = cameraId;
        lens.physicalId = physicalId;
        lens.system = system;
        lens.key = physicalId == null ? cameraId : cameraId + "|" + physicalId;
        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        lens.focal = focalLengths != null && focalLengths.length > 0 ? focalLengths[0] : 0f;
        SizeF physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        lens.sensorWidth = physicalSize == null ? 0f : Math.max(physicalSize.getWidth(), physicalSize.getHeight());
        lens.activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lens.zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        }
        int width = lens.activeArray == null ? 0 : lens.activeArray.width();
        if (width > 0) lens.maxCropZoom = Math.max(1f, Math.min(8f, width / 640f));
        lenses.add(lens);
    }

    private float focalLengthToFov(Lens lens) {
        if (lens.focal <= 0f || lens.sensorWidth <= 0f) return 0f;
        return (float) (2d * Math.toDegrees(Math.atan(lens.sensorWidth / (2d * lens.focal))));
    }

    // ------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------

    void start(String key) {
        requestedKey = key == null || key.isEmpty() ? mainKey : key;
        cameraHandler.post(() -> {
            if (textureView.isAvailable()) openLens(requestedKey);
        });
    }

    void stop() {
        requestedKey = "";
        cameraHandler.post(this::closeInternal);
    }

    boolean isOpen() {
        return open;
    }

    String mainKey() {
        return mainKey;
    }

    private Lens findLens(String key) {
        for (Lens lens : lenses) if (lens.key.equals(key)) return lens;
        return null;
    }

    private void openLens(String key) {
        if (opening) {
            requestedKey = key;
            return;
        }
        Lens lens = findLens(key);
        if (lens == null) lens = findLens(mainKey);
        if (lens == null) {
            notifyError("no-camera", "未找到可用后置镜头");
            return;
        }
        if (!textureView.isAvailable() || textureView.getSurfaceTexture() == null) {
            requestedKey = key;
            return;
        }
        closeInternal();
        opening = true;
        activeLens = lens;
        baseRatio = lens.key.equals(mainKey) ? 1f : Math.max(0.2f, lens.ratio);
        zoomRatioRange = lens.zoomRatioRange;
        activeArray = lens.activeArray;

        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = pickSize(map.getOutputSizes(SurfaceTexture.class), MAX_PREVIEW_LONG_EDGE);
            jpegSize = pickSize(map.getOutputSizes(ImageReader.class), MAX_JPEG_LONG_EDGE);
            if (previewSize == null) {
                opening = false;
                notifyError("no-preview", "镜头不支持预览输出");
                return;
            }
            if (jpegSize == null) jpegSize = previewSize;

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(texture);
            jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            jpegReader.setOnImageAvailableListener(this::onJpegAvailable, cameraHandler);

            jpegOrientation = computeJpegOrientation(lens.cameraId);
            applyTransform();
            cameraManager.openCamera(lens.cameraId, deviceCallback, cameraHandler);
        } catch (Exception error) {
            opening = false;
            Log.w(TAG, "openLens failed " + key, error);
            notifyError("open-failed", String.valueOf(error.getMessage()));
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            device = camera;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            device = null;
            opening = false;
            notifyError("disconnected", "相机被其他应用占用");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            device = null;
            opening = false;
            Log.w(TAG, "camera error " + error);
            notifyError("device-error", "相机打开失败(" + error + ")");
        }
    };

    private CameraCaptureSession.StateCallback createSessionCallback() {
        return new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession configured) {
                session = configured;
                opening = false;
                open = true;
                startRepeating();
                mainHandler.post(() -> {
                    applyTransform();
                    listener.onCameraReady(buildInfo());
                });
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession failed) {
                opening = false;
                session = null;
                Log.w(TAG, "session configure failed for " + activeLens.key);
                notifyError("session-failed", "该镜头不支持预览，已保持原镜头");
            }
        };
    }

    private void createSession() {
        try {
            List<OutputConfiguration> configurations = new ArrayList<>();
            OutputConfiguration previewConfig = new OutputConfiguration(previewSurface);
            OutputConfiguration jpegConfig = new OutputConfiguration(jpegReader.getSurface());
            if (activeLens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                previewConfig.setPhysicalCameraId(activeLens.physicalId);
                jpegConfig.setPhysicalCameraId(activeLens.physicalId);
            }
            configurations.add(previewConfig);
            configurations.add(jpegConfig);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSessionByOutputConfigurations(configurations, createSessionCallback(), cameraHandler);
            } else {
                device.createCaptureSession(Arrays.asList(previewSurface, jpegReader.getSurface()),
                        createSessionCallback(), cameraHandler);
            }
        } catch (Exception error) {
            Log.w(TAG, "createSession failed", error);
            opening = false;
            notifyError("session-failed", String.valueOf(error.getMessage()));
        }
    }

    private void closeInternal() {
        open = false;
        opening = false;
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
            }
            device = null;
        }
        if (jpegReader != null) {
            try {
                jpegReader.close();
            } catch (Exception ignored) {
            }
            jpegReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    void selectLens(String key) {
        requestedKey = key == null || key.isEmpty() ? mainKey : key;
        cameraHandler.post(() -> openLens(requestedKey));
    }

    void notifyPermissionDenied() {
        notifyError("permission", "相机权限被拒绝");
    }

    // ------------------------------------------------------------------
    // Capture request helpers
    // ------------------------------------------------------------------

    private void startRepeating() {
        if (device == null || session == null || previewSurface == null) return;
        try {
            session.setRepeatingRequest(buildPreviewRequest().build(), null, cameraHandler);
        } catch (Exception error) {
            Log.w(TAG, "setRepeatingRequest failed", error);
        }
    }

    private CaptureRequest.Builder buildPreviewRequest() throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        applyCommonSettings(builder);
        return builder;
    }

    private void applyCommonSettings(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE,
                torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (focusLocked) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        float ratio = baseRatio * zoomRel;
        boolean useZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && zoomRatioRange != null && activeLens.physicalId == null;
        if (useZoomRatio) {
            float target = Math.max(zoomRatioRange.getLower(), Math.min(zoomRatioRange.getUpper(), ratio));
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, target);
        } else if (activeArray != null) {
            float crop = Math.max(1f, Math.min(activeLens.maxCropZoom, zoomRel));
            int width = Math.max(2, Math.round(activeArray.width() / crop));
            int height = Math.max(2, Math.round(activeArray.height() / crop));
            int left = activeArray.left + (activeArray.width() - width) / 2;
            int top = activeArray.top + (activeArray.height() - height) / 2;
            builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + width, top + height));
        }
    }

    void setZoom(float rel) {
        zoomRel = Math.max(0.5f, Math.min(8f, rel));
        float effective = effectiveZoom();
        matrixZoom = Math.max(1f, zoomRel / effective);
        cameraHandler.post(() -> {
            if (!open) return;
            startRepeating();
            applyTransform();
        });
    }

    private float effectiveZoom() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && zoomRatioRange != null && activeLens != null && activeLens.physicalId == null) {
            float ratio = baseRatio * zoomRel;
            return Math.max(zoomRatioRange.getLower(), Math.min(zoomRatioRange.getUpper(), ratio)) / baseRatio;
        }
        if (activeLens != null) return Math.max(1f, Math.min(activeLens.maxCropZoom, zoomRel));
        return 1f;
    }

    void setTorch(boolean on) {
        torchOn = on;
        cameraHandler.post(() -> {
            if (open) startRepeating();
        });
    }

    void setFocusLock(boolean lock) {
        focusLocked = lock;
        cameraHandler.post(() -> {
            if (open) startRepeating();
        });
    }

    boolean supportsTorch() {
        if (activeLens == null) return false;
        try {
            Boolean flash = cameraManager.getCameraCharacteristics(activeLens.cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return flash != null && flash;
        } catch (Exception error) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Still capture / frame grab
    // ------------------------------------------------------------------

    void captureFrame(boolean asDataUrl) {
        frameAsDataUrl = asDataUrl;
        cameraHandler.post(this::takePicture);
    }

    private void takePicture() {
        if (!open || session == null || device == null || jpegReader == null) {
            frameAsDataUrl = false;
            notifyError("not-open", "相机尚未就绪");
            return;
        }
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(jpegReader.getSurface());
            applyCommonSettings(builder);
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            session.capture(builder.build(), captureCallback, cameraHandler);
        } catch (Exception error) {
            frameAsDataUrl = false;
            Log.w(TAG, "takePicture failed", error);
            notifyError("capture-failed", String.valueOf(error.getMessage()));
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            frameAsDataUrl = false;
            notifyError("capture-failed", "拍照失败");
        }
    };

    private void onJpegAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            if (frameAsDataUrl) {
                frameAsDataUrl = false;
                String dataUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                mainHandler.post(() -> listener.onFrameCaptured(dataUrl));
            } else {
                String path = saveJpeg(data);
                final String result = path == null ? "" : path;
                mainHandler.post(() -> listener.onFrameCaptured(result));
            }
        } catch (Exception error) {
            Log.w(TAG, "onJpegAvailable failed", error);
            frameAsDataUrl = false;
        } finally {
            if (image != null) image.close();
        }
    }

    private String saveJpeg(byte[] data) {
        String name = "描绘_" + System.currentTimeMillis() + ".jpg";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/描绘");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                        if (stream != null) stream.write(data);
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                    return uri.toString();
                }
            } catch (Exception error) {
                Log.w(TAG, "save via MediaStore failed", error);
            }
        }
        try {
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (directory == null) directory = context.getFilesDir();
            File file = new File(directory, name);
            try (OutputStream stream = new FileOutputStream(file)) {
                stream.write(data);
            }
            return file.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "saveJpeg failed", error);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JSONObject buildInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("lensId", activeLens.key);
            info.put("lensLabel", activeLens.label);
            info.put("wide", activeLens.key.equals(wideKey));
            info.put("mainId", mainKey);
            info.put("wideId", wideKey);
            info.put("wideRatio", wideRatio);
            info.put("width", previewSize == null ? 0 : previewSize.getWidth());
            info.put("height", previewSize == null ? 0 : previewSize.getHeight());
            info.put("torch", supportsTorch());
            info.put("maxZoom", activeLens.maxCropZoom);
            JSONArray array = new JSONArray();
            for (Lens lens : lenses) array.put(lensJson(lens));
            info.put("lenses", array);
        } catch (Exception ignored) {
        }
        return info;
    }

    private void notifyError(String code, String detail) {
        mainHandler.post(() -> listener.onCameraError(code, detail));
    }

    private Size pickSize(Size[] sizes, int maxLongEdge) {
        if (sizes == null || sizes.length == 0) return null;
        Size bestSmall = null;
        Size bestLarge = null;
        for (Size size : sizes) {
            int longEdge = Math.max(size.getWidth(), size.getHeight());
            if (longEdge <= maxLongEdge) {
                if (bestSmall == null
                        || (long) size.getWidth() * size.getHeight() > (long) bestSmall.getWidth() * bestSmall.getHeight()) {
                    bestSmall = size;
                }
            } else if (bestLarge == null || longEdge < Math.max(bestLarge.getWidth(), bestLarge.getHeight())) {
                bestLarge = size;
            }
        }
        return bestSmall != null ? bestSmall : bestLarge;
    }

    private int computeJpegOrientation(String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensor == null) sensor = 90;
            int rotation = displayRotationDegrees();
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return (sensor + rotation) % 360;
            }
            return (sensor - rotation + 360) % 360;
        } catch (Exception error) {
            return 90;
        }
    }

    private int displayRotationDegrees() {
        try {
            android.view.Display display = ((android.hardware.display.DisplayManager)
                    context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (display == null) return 0;
            switch (display.getRotation()) {
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception error) {
            return 0;
        }
    }

    void applyTransform() {
        mainHandler.post(() -> {
            if (previewSize == null) return;
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;
            float fit = Math.min((float) viewWidth / previewSize.getWidth(),
                    (float) viewHeight / previewSize.getHeight()) * matrixZoom;
            float scaleX = previewSize.getWidth() * fit / viewWidth;
            float scaleY = previewSize.getHeight() * fit / viewHeight;
            Matrix matrix = new Matrix();
            matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
            textureView.setTransform(matrix)