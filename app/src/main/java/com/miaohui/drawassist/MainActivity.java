package com.miaohui.drawassist;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.TextureView;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.WebChromeClient;
import androidx.webkit.WebViewAssetLoader;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 101;
    private static final int PICK_IMAGE = 102;
    private WebView webView;
    private TextureView previewView;
    private Camera2Controller cameraController;
    private PermissionRequest pendingPermissionRequest;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingNativeLensKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF101210);
        getWindow().setNavigationBarColor(0xFF101210);
        if (!getPreferences(MODE_PRIVATE).getBoolean("privacyAccepted", false)) {
            TextView policySummary = new TextView(this);
            policySummary.setPadding(48, 8, 48, 12);
            policySummary.setText(Html.fromHtml(
                    "<b>处理目的与方式</b><br>将参考图片叠加到相机画面，供您在本机描摹。<br><br>"
                    + "<b>处理信息与范围</b><br><b>相机实时画面（可能包含敏感个人信息）</b>：仅在您使用相机功能时获取并在本机实时显示；您主动点击拍摄后，画面保存到本机。"
                    + "<br><b>您主动从系统照片选择器选取的图片（可能包含敏感个人信息）</b>：仅用于本机显示、线稿和校正处理。上述图像不会上传。应用不收集账号、位置、通讯录或设备标识信息，也未集成第三方统计 SDK。应用仅在本机记录相机权限拒绝状态，以避免自动重复请求。<br><br>"
                    + "图像仅在功能使用期间处理；拍摄文件由您选择保存位置并自行管理。相机权限可在系统设置中撤回；图片访问由您每次选择。<br><br>"
                    + "<a href=\"https://swsdl.vivo.com.cn/appstore/developer/privacy-policy/87fdf93112ec4e6ea7b007d59ec89dca.html\">阅读完整隐私政策</a>",
                    Html.FROM_HTML_MODE_LEGACY));
            policySummary.setMovementMethod(LinkMovementMethod.getInstance());
            new AlertDialog.Builder(this)
                    .setTitle("隐私政策与个人信息保护")
                    .setView(policySummary)
                    .setCancelable(false)
                    .setPositiveButton("同意并继续", (dialog, which) -> {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("privacyAccepted", true).apply();
                        loadApp();
                    })
                    .setNegativeButton("不同意并退出", (dialog, which) -> finishAffinity())
                    .show();
            return;
        }
        loadApp();
    }

    private void loadApp() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101210);
        previewView = new TextureView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "swsdl.vivo.com.cn".equals(uri.getHost())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else if (getPreferences(MODE_PRIVATE).getBoolean("cameraPermissionDenied", false)) {
                        request.deny();
                    } else {
                        pendingPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*"});
                try {
                    startActivityForResult(intent, PICK_IMAGE);
                    return true;
                } catch (ActivityNotFoundException exception) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "未找到可用的图片选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        cameraController = new Camera2Controller(this, previewView, cameraListener);
        webView.addJavascriptInterface(new NativeCameraBridge(), "NativeCam");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    // ------------------------------------------------------------------
    // Native Camera2 bridge (enumerates every physical rear lens)
    // ------------------------------------------------------------------

    private final Camera2Controller.Listener cameraListener = new Camera2Controller.Listener() {
        @Override
        public void onCameraReady(JSONObject info) {
            emitJs("onNativeCameraReady", info == null ? "{}" : info.toString());
        }

        @Override
        public void onCameraError(String code, String detail) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("code", code);
                payload.put("detail", detail);
            } catch (Exception ignored) {
            }
            emitJs("onNativeCameraError", payload.toString());
        }

        @Override
        public void onFrameCaptured(String value) {
            if (value == null) value = "";
            emitJs("onNativeCapture", JSONObject.quote(value));
        }
    };

    private void emitJs(String function, String argument) {
        if (webView == null) return;
        String script = "window." + function + " && window." + function + "(" + argument + ")";
        webView.evaluateJavascript(script, null);
    }

    private void startNativeCamera(String lensKey) {
        if (cameraController == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraController.start(lensKey);
        } else if (getPreferences(MODE_PRIVATE).getBoolean("cameraPermissionDenied", false)) {
            cameraController.notifyPermissionDenied();
        } else {
            pendingNativeLensKey = lensKey == null ? "" : lensKey;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    public class NativeCameraBridge {
        @JavascriptInterface
        public boolean available() {
            return cameraController != null && cameraController.available();
        }

        @JavascriptInterface
        public String lenses() {
            return cameraController == null ? "[]" : cameraController.lensesJson();
        }

        @JavascriptInterface
        public void start(String lensKey) {
            runOnUiThread(() -> startNativeCamera(lensKey));
        }

        @JavascriptInterface
        public void selectLens(String lensKey) {
            runOnUiThread(() -> cameraController.selectLens(lensKey));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> cameraController.stop());
        }

        @JavascriptInterface
        public void setZoom(float rel) {
            runOnUiThread(() -> cameraController.setZoom(rel));
        }

        @JavascriptInterface
        public void setTorch(boolean on) {
            runOnUiThread(() -> cameraController.setTorch(on));
        }

        @JavascriptInterface
        public void setFocusLock(boolean lock) {
            runOnUiThread(() -> cameraController.setFocusLock(lock));
        }

        @JavascriptInterface
        public void capture() {
            runOnUiThread(() -> cameraController.captureFrame(false));
        }

        @JavascriptInterface
        public void grabFrame() {
            runOnUiThread(() -> cameraController.captureFrame(true));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingPermissionRequest != null) {
            if (granted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("cameraPermissionDenied", true).apply();
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
        if (pendingNativeLensKey != null) {
            String lensKey = pendingNativeLensKey;
            pendingNativeLensKey = null;
            if (granted) {
                if (cameraController != null) cameraController.start(lensKey);
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("cameraPermissionDenied", true).apply();
                if (cameraController != null) cameraController.notifyPermissionDenied();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && fileCallback != null) {
            Uri[] results = resultCode == RESULT_OK && data != null && data.getData() != null
                    ? new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (cameraController != null) cameraController.stop();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.pauseCamera && window.pauseCamera()", null);
            webView.onPause();
        }
        if (cameraController != null) cameraController.stop();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.resumeCamera && window.resumeCamera()", null);
        }
    }
}
