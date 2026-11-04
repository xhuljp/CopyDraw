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
import android.hardware.camera2.params.MeteringRectangle;
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
 * navigator.mediaDevices, so the ultra wide lens is invisible to the web layer.
 * This controller enumerates the rear facing cameras and, when the logical
 * camera can zoom below 1x, adds an ultra wide entry that is streamed through
 * the logical camera itself (so autofocus keeps working) into a TextureView
 * placed underneath a transparent WebView.
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
    private static final long FREEZE_TIMEOUT_MS = 1500;

    private static final class Lens {
        String key;          // opaque id handed to JS ("0" or "0@0.60")
        String cameraId;     // camera id used with openCamera
        String physicalId;   // hidden physical sub camera to stream, null when unused
        String label;        // "1×" / "0.6×"
        boolean wide;        // true when clearly wider than the main lens
        float ratio;         // zoom ratio relative to the main lens
        float focal;         // focal length in mm
        float sensorWidth;   // physical sensor width in mm
        Rect activeArray;
        Range<Float> zoomRatioRange;   // nullable
        float maxCropZoom = 1f;
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
    private boolean afSupported;
    private int[] afAvailableModes;
    private int maxAfRegions;
    private int maxAeRegions;
    private Rect focusBounds;
    private MeteringRectangle[] afRegions;
    private MeteringRectangle[] aeRegions;
    private Float pinnedFocusDistance;   // diopters the lens is frozen at while locked
    private Float lastFocusDistance;     // latest value reported by the sensor
    private float minimumFocusDistance;  // largest diopter the lens can reach
    private boolean freezePending;       // waiting for the lock sweep to converge
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
        String[] systemIds;
        try {
            systemIds = cameraManager.getCameraIdList();
        } catch (Exception error) {
            Log.w(TAG, "getCameraIdList failed", error);
            return;
        }
        for (String id : systemIds) {
            CameraCharacteristics characteristics;
            try {
                characteristics = cameraManager.getCameraCharacteristics(id);
            } catch (Exception error) {
                Log.w(TAG, "characteristics " + id + " failed: " + error);
                continue;
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            if (mainCameraId == null) mainCameraId = id;
            lenses.add(buildLens(id, characteristics));
            if (mainCameraId.equals(id)) {
                // A logical rear camera that can zoom below 1x exposes its ultra
                // wide sensor through CONTROL_ZOOM_RATIO. Streaming it through
                // the logical camera keeps autofocus working, which a physical
                // sub camera stream cannot provide.
                Range<Float> zoomRange = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
                float wide = zoomRange == null ? 1f : zoomRange.getLower();
                if (wide > 0.2f && wide < 0.95f) {
                    Lens lens = buildLens(id, characteristics);
                    lens.key = id + "@" + String.format(Locale.US, "%.2f", wide);
                    lens.ratio = wide;
                    lenses.add(lens);
                }
            }
        }
        if (mainCameraId == null) {
            Log.w(TAG, "no rear facing camera found");
            return;
        }

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
            if (lens.ratio <= 0f) {
                float fov = focalLengthToFov(lens);
                // fov is in degrees, Math.tan works in radians.
                lens.ratio = fov > 0f && mainFov > 0f
                        ? (float) (Math.tan(Math.toRadians(mainFov) / 2d) / Math.tan(Math.toRadians(fov) / 2d))
                        : 1f;
            }
            if (lens.ratio <= 0f || Float.isNaN(lens.ratio)) lens.ratio = 1f;
            boolean duplicate = false;
            for (Lens existing : keep) {
                if (Math.abs(Math.log(existing.ratio / lens.ratio)) < 0.06d) {
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
        if (wideKey.isEmpty()) addPhysicalWide(mainCameraId, mainFov);
    }

    /**
     * Some devices (HyperOS on the Redmi K series, for example) keep the ultra
     * wide out of getCameraIdList() and report a logical zoom range that starts
     * at 1.0, so the only way to reach it is to stream the hidden physical sub
     * camera that owns the wider sensor.
     */
    private void addPhysicalWide(String mainCameraId, float mainFov) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || mainFov <= 0f) return;
        try {
            Set<String> physicalIds = cameraManager.getCameraCharacteristics(mainCameraId).getPhysicalCameraIds();
            Lens widest = null;
            for (String physicalId : physicalIds) {
                if (physicalId.equals(mainCameraId)) continue;
                CameraCharacteristics characteristics;
                try {
                    characteristics = cameraManager.getCameraCharacteristics(physicalId);
                } catch (Exception error) {
                    Log.w(TAG, "physical characteristics " + physicalId + " failed: " + error);
                    continue;
                }
                Lens lens = buildLens(mainCameraId, characteristics);
                lens.physicalId = physicalId;
                lens.key = mainCameraId + "#" + physicalId;
                float fov = focalLengthToFov(lens);
                if (fov <= 0f) continue;
                // fov is in degrees, Math.tan works in radians.
                lens.ratio = (float) (Math.tan(Math.toRadians(mainFov) / 2d) / Math.tan(Math.toRadians(fov) / 2d));
                if (lens.ratio > 0.05f && lens.ratio < 0.95f && (widest == null || lens.ratio < widest.ratio)) {
                    widest = lens;
                }
            }
            if (widest == null) return;
            // The wider sensor is streamed directly, so there is no zoom ratio to
            // ask for: zoom is applied by cropping the incoming frame instead.
            widest.zoomRatioRange = null;
            widest.wide = true;
            widest.label = String.format(Locale.US, "%.1f×", widest.ratio);
            lenses.add(widest);
            wideKey = widest.key;
            wideRatio = Math.max(0.2f, widest.ratio);
        } catch (Exception error) {
            Log.w(TAG, "physical ultra wide scan failed", error);
        }
    }

    private Lens buildLens(String cameraId, CameraCharacteristics characteristics) {
        Lens lens = new Lens();
        lens.cameraId = cameraId;
        lens.key = cameraId;
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
        return lens;
    }

    private float focalLengthToFov(Lens lens) {
        if (lens.focal <= 0f || lens.sensorWidth <= 0f) return 0f;
        return (float) (2d * Math.toDegrees(Math.atan(lens.sensorWidth / (2d * lens.focal))));
    }

    // ------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------

    void start(String key) {
        // Rescan before every fresh start: several vendors only expose the
        // auxiliary rear lenses once the camera permission has been granted.
        if (!open && !opening) scanLenses();
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
        configureFocus(lens);

        try {
            StreamConfigurationMap map = characteristicsFor(lens)
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

            jpegOrientation = computeJpegOrientation(characteristicsFor(lens));
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
            String physicalId = activeLens == null ? null : activeLens.physicalId;
            if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // The ultra wide lives behind the logical camera as a hidden sub
                // camera, so both outputs have to be requested from it directly.
                OutputConfiguration previewConfig = new OutputConfiguration(previewSurface);
                previewConfig.setPhysicalCameraId(physicalId);
                OutputConfiguration jpegConfig = new OutputConfiguration(jpegReader.getSurface());
                jpegConfig.setPhysicalCameraId(physicalId);
                device.createCaptureSessionByOutputConfigurations(
                        Arrays.asList(previewConfig, jpegConfig), createSessionCallback(), cameraHandler);
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
        cameraHandler.post(() -> {
            Lens lens = findLens(requestedKey);
            if (lens != null && open && activeLens != null && activeLens.cameraId.equals(lens.cameraId)
                    && samePhysical(activeLens, lens)) {
                // The ultra wide entry is the same logical camera, only the zoom
                // ratio differs, so the session can be reused.
                float desired = baseRatio * zoomRel;
                activeLens = lens;
                baseRatio = lens.key.equals(mainKey) ? 1f : Math.max(0.2f, lens.ratio);
                zoomRel = Math.max(0.5f, Math.min(8f, desired / baseRatio));
                matrixZoom = computeMatrixZoom();
                startRepeating();
                applyTransform();
                mainHandler.post(() -> listener.onCameraReady(buildInfo()));
                return;
            }
            openLens(requestedKey);
        });
    }

    void notifyPermissionDenied() {
        notifyError("permission", "相机权限被拒绝");
    }

    /** Two entries of one logical camera are only interchangeable on the same sensor. */
    private static boolean samePhysical(Lens a, Lens b) {
        if (a.physicalId == null) return b.physicalId == null;
        return a.physicalId.equals(b.physicalId);
    }

    // ------------------------------------------------------------------
    // Capture request helpers
    // ------------------------------------------------------------------

    private void startRepeating() {
        if (device == null || session == null || previewSurface == null) return;
        try {
            session.setRepeatingRequest(buildPreviewRequest().build(), focusCallback, cameraHandler);
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
        if (afSupported) {
            if (pinnedFocusDistance != null && hasMode(afAvailableModes, CaptureRequest.CONTROL_AF_MODE_OFF)) {
                // Locked: stop the autofocus algorithm entirely and hold the lens
                // at the distance it converged on, so a later scene change cannot
                // pull it back out of focus.
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, pinnedFocusDistance);
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, focusMode());
                if (afRegions != null) builder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            }
            if (aeRegions != null) builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
        }
        float ratio = baseRatio * zoomRel;
        boolean useZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && zoomRatioRange != null && activeLens != null;
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
        matrixZoom = computeMatrixZoom();
        cameraHandler.post(() -> {
            if (!open) return;
            startRepeating();
            applyTransform();
        });
    }

    /** Extra scale the viewfinder needs on top of what the sensor can deliver. */
    private float computeMatrixZoom() {
        return Math.max(1f, zoomRel / effectiveZoom());
    }

    private float effectiveZoom() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioRange != null && activeLens != null) {
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
        cameraHandler.post(() -> {
            if (!afSupported) return;
            focusLocked = lock;
            if (!lock) {
                clearFocusLock();
                if (open) startRepeating();
                return;
            }
            pinnedFocusDistance = null;
            if (lastFocusDistance != null) {
                // The lens is already settled: pin it where it stands.
                pinnedFocusDistance = clampFocusDistance(lastFocusDistance);
                if (open) startRepeating();
            } else {
                triggerFocusSweep();
            }
        });
    }

    private void clearFocusLock() {
        pinnedFocusDistance = null;
        freezePending = false;
        cameraHandler.removeCallbacks(freezeFallback);
        resetFocusRegions();
    }

    private float clampFocusDistance(float distance) {
        if (minimumFocusDistance <= 0f) return Math.max(0f, distance);
        return Math.max(0f, Math.min(minimumFocusDistance, distance));
    }

    /**
     * Focuses on a point of the viewfinder. Coordinates are normalised to the
     * view ([0,1] with the origin at its top left corner); they are mapped back
     * through the TextureView transform onto the sensor active array.
     * A single tap keeps continuous autofocus around the point, a long press
     * locks the lens after one autofocus sweep.
     */
    void focusAt(float nx, float ny, boolean lock) {
        mainHandler.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            float[] point = toBufferNormalized(nx, ny, viewWidth, viewHeight);
            cameraHandler.post(() -> applyFocus(point[0], point[1], lock));
        });
    }

    private void applyFocus(float u, float v, boolean lock) {
        if (!afSupported) return;
        focusLocked = lock;
        pinnedFocusDistance = null;
        freezePending = false;
        cameraHandler.removeCallbacks(freezeFallback);
        float crop = cropFactor();
        float arrayX = Math.max(0f, Math.min(1f, 0.5f + (u - 0.5f) / crop));
        float arrayY = Math.max(0f, Math.min(1f, 0.5f + (v - 0.5f) / crop));
        afRegions = buildRegion(arrayX, arrayY, maxAfRegions);
        aeRegions = buildRegion(arrayX, arrayY, maxAeRegions);
        triggerFocusSweep();
    }

    /**
     * Runs one autofocus sweep around the current regions. When the request is a
     * long press the lens is frozen at the distance the sweep converged on,
     * otherwise autofocus stays continuous so a tap keeps tracking the scene.
     */
    private void triggerFocusSweep() {
        if (session == null || device == null) return;
        try {
            CaptureRequest.Builder start = buildPreviewRequest();
            start.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            freezePending = focusLocked;
            cameraHandler.removeCallbacks(freezeFallback);
            if (freezePending) cameraHandler.postDelayed(freezeFallback, FREEZE_TIMEOUT_MS);
            session.capture(start.build(), focusCallback, cameraHandler);
            CaptureRequest.Builder repeat = buildPreviewRequest();
            repeat.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(repeat.build(), focusCallback, cameraHandler);
        } catch (Exception error) {
            Log.w(TAG, "focus sweep failed", error);
        }
    }

    private final CameraCaptureSession.CaptureCallback focusCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession captureSession, CaptureRequest request,
                                       TotalCaptureResult result) {
            Float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (distance != null) lastFocusDistance = distance;
            if (!freezePending) return;
            Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
            if (state != null && (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                pinFocusDistance(distance);
            }
        }
    };

    private final Runnable freezeFallback = new Runnable() {
        @Override
        public void run() {
            pinFocusDistance(null);
        }
    };

    private void pinFocusDistance(Float distance) {
        if (!freezePending) return;
        freezePending = false;
        cameraHandler.removeCallbacks(freezeFallback);
        Float value = distance != null ? distance : lastFocusDistance;
        if (value == null || !hasMode(afAvailableModes, CaptureRequest.CONTROL_AF_MODE_OFF)) return;
        pinnedFocusDistance = clampFocusDistance(value);
        if (open) startRepeating();
    }

    private void configureFocus(Lens lens) {
        afSupported = false;
        afAvailableModes = null;
        maxAfRegions = 0;
        maxAeRegions = 0;
        focusBounds = null;
        afRegions = null;
        aeRegions = null;
        pinnedFocusDistance = null;
        lastFocusDistance = null;
        minimumFocusDistance = 0f;
        freezePending = false;
        cameraHandler.removeCallbacks(freezeFallback);
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(lens.cameraId);
            Integer maxAf = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAe = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAfRegions = maxAf == null ? 0 : maxAf;
            maxAeRegions = maxAe == null ? 0 : maxAe;
            afAvailableModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minimumFocusDistance = minFocus == null ? 0f : minFocus;
            focusBounds = lens.activeArray != null ? lens.activeArray
                    : characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            afSupported = focusBounds != null && focusMode() != null;
            resetFocusRegions();
        } catch (Exception error) {
            Log.w(TAG, "configureFocus failed", error);
        }
        if (!afSupported) focusLocked = false;
    }

    private static boolean hasMode(int[] modes, int mode) {
        if (modes == null) return false;
        for (int value : modes) {
            if (value == mode) return true;
        }
        return false;
    }

    /** First mode of [candidates] supported by the active camera, or null. */
    private Integer firstMode(int... candidates) {
        for (int mode : candidates) {
            if (hasMode(afAvailableModes, mode)) return mode;
        }
        return null;
    }

    private Integer focusMode() {
        if (focusLocked) {
            // A single autofocus sweep parks the lens, which is what a long
            // press should do; macro lenses are fixed at close range so they are
            // only used as a last resort.
            return firstMode(CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureRequest.CONTROL_AF_MODE_MACRO);
        }
        return firstMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_MACRO);
    }

    private void resetFocusRegions() {
        afRegions = fullRegion(maxAfRegions);
        aeRegions = fullRegion(maxAeRegions);
    }

    private MeteringRectangle[] fullRegion(int maxRegions) {
        if (maxRegions <= 0 || focusBounds == null) return null;
        return new MeteringRectangle[]{
                new MeteringRectangle(focusBounds, MeteringRectangle.METERING_WEIGHT_MAX)};
    }

    private MeteringRectangle[] buildRegion(float arrayX, float arrayY, int maxRegions) {
        if (maxRegions <= 0 || focusBounds == null) return null;
        int side = Math.max(2, Math.round(Math.min(focusBounds.width(), focusBounds.height()) * 0.16f));
        int centerX = focusBounds.left + Math.round(arrayX * focusBounds.width());
        int centerY = focusBounds.top + Math.round(arrayY * focusBounds.height());
        int left = Math.max(focusBounds.left, Math.min(focusBounds.right - 1, centerX - side / 2));
        int top = Math.max(focusBounds.top, Math.min(focusBounds.bottom - 1, centerY - side / 2));
        int right = Math.max(left + 1, Math.min(focusBounds.right, left + side));
        int bottom = Math.max(top + 1, Math.min(focusBounds.bottom, top + side));
        return new MeteringRectangle[]{
                new MeteringRectangle(left, top, right - left, bottom - top,
                        MeteringRectangle.METERING_WEIGHT_MAX)};
    }

    /**
     * How much the streamed buffer is cropped into the active array, so buffer
     * normalised coordinates can be projected back onto the sensor.
     */
    private float cropFactor() {
        if (activeLens == null) return 1f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioRange != null) {
            float ratio = baseRatio * zoomRel;
            return Math.max(zoomRatioRange.getLower(), Math.min(zoomRatioRange.getUpper(), ratio));
        }
        return Math.max(1f, Math.min(activeLens.maxCropZoom, zoomRel));
    }

    /**
     * Inverts the TextureView transform so a point of the view can be expressed
     * in normalised buffer coordinates ([0,1] with the origin at the top left of
     * the camera frame).
     */
    private float[] toBufferNormalized(float nx, float ny, int viewWidth, int viewHeight) {
        Matrix matrix = displayMatrix(viewWidth, viewHeight);
        Matrix inverse = new Matrix();
        if (matrix == null || viewWidth <= 0 || viewHeight <= 0 || !matrix.invert(inverse)) {
            return new float[]{0.5f, 0.5f};
        }
        float[] point = new float[]{nx * viewWidth, ny * viewHeight};
        inverse.mapPoints(point);
        return new float[]{point[0] / viewWidth, point[1] / viewHeight};
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
            info.put("focus", afSupported);
            info.put("maxZoom", activeLens.maxCropZoom);
            JSONArray array = new JSONArray();
            for (Lens lens : lenses) array.put(lensJson(lens));
            info.put("lenses", array);
        } catch (Exception ignored) {
        }
        return info;
    }

    private void notifyError(String code, String detail) {
        Log.w(TAG, "notifyError " + code + " " + detail);
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

    private int computeJpegOrientation(CameraCharacteristics characteristics) {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensor == null) sensor = 90;
        int rotation = displayRotationDegrees();
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensor + rotation) % 360;
        }
        return (sensor - rotation + 360) % 360;
    }

    private CameraCharacteristics characteristicsFor(Lens lens) throws CameraAccessException {
        if (lens.physicalId != null) {
            try {
                return cameraManager.getCameraCharacteristics(lens.physicalId);
            } catch (Exception error) {
                Log.w(TAG, "physical characteristics " + lens.physicalId + " failed: " + error);
            }
        }
        return cameraManager.getCameraCharacteristics(lens.cameraId);
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

    /**
     * Clockwise angle the camera buffer needs to be rotated so it appears
     * upright on the current screen orientation.
     */
    private int previewRotation() {
        if (activeLens == null) return 0;
        try {
            CameraCharacteristics characteristics = characteristicsFor(activeLens);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensor == null) sensor = 90;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            int rotation = displayRotationDegrees();
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return (sensor + rotation) % 360;
            }
            return 0;
        } catch (Exception error) {
            return 0;
        }
    }

    /**
     * Fits the (rotated) camera buffer inside the view with a centred contain
     * scale, so the web layer can keep mapping frame pixels to screen pixels
     * with the same contain maths.
     */
    void applyTransform() {
        mainHandler.post(() -> {
            if (previewSize == null) return;
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;
            Matrix matrix = displayMatrix(viewWidth, viewHeight);
            if (matrix != null) textureView.setTransform(matrix);
        });
    }

    /**
     * Transform applied on top of the TextureView stretch (view space). Shared
     * with the focus maths so a tap can be mapped back to the camera buffer.
     */
    private Matrix displayMatrix(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth <= 0 || viewHeight <= 0) return null;
        int rotation = previewRotation();
        Matrix matrix = new Matrix();
        if (rotation == 0) {
            float heightScale = (viewWidth * previewSize.getWidth())
                    / (viewHeight * (float) previewSize.getHeight());
            matrix.setScale(matrixZoom, matrixZoom * heightScale, viewWidth / 2f, viewHeight / 2f);
            return matrix;
        }
        boolean swap = rotation == 90 || rotation == 270;
        float shownWidth = swap ? previewSize.getHeight() : previewSize.getWidth();
        float shownHeight = swap ? previewSize.getWidth() : previewSize.getHeight();
        float fit = Math.min(viewWidth / shownWidth, viewHeight / shownHeight) * matrixZoom;
        float scaleX = previewSize.getWidth() * fit / viewWidth;
        float scaleY = previewSize.getHeight() * fit / viewHeight;
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        matrix.postRotate(rotation, viewWidth / 2f, viewHeight / 2f);
        return matrix;
    }
}
