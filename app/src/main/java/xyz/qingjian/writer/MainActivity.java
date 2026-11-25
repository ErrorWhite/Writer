package xyz.qingjian.writer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.OutputStream;
import java.util.Collections;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int SAVE = 2;
    private final Uri studio = Uri.parse(BuildConfig.STUDIO_URL);
    private WebView web;
    private ValueCallback<Uri[]> picker;
    private byte[] export;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        web.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            }
            return insets;
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (insideStudio(url)) return false;
                if (request.isForMainFrame() && "https".equals(url.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, url)); }
                    catch (ActivityNotFoundException ignored) { show("没有可打开授权链接的浏览器。"); }
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (picker != null) picker.onReceiveValue(null);
                picker = callback;
                try { startActivityForResult(params.createIntent(), PICK); }
                catch (ActivityNotFoundException e) { picker = null; callback.onReceiveValue(null); }
                return true;
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            String origin = studio.getScheme() + "://" + studio.getEncodedAuthority();
            WebViewCompat.addWebMessageListener(web, "QingjianFiles", Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && insideStudio(Uri.parse(view.getUrl()))) saveExport(message.getData());
                });
        }
        if (state == null || web.restoreState(state) == null) web.loadUrl(BuildConfig.STUDIO_URL);
    }

    private boolean insideStudio(Uri url) {
        return "https".equals(url.getScheme()) && studio.getEncodedAuthority().equals(url.getEncodedAuthority())
            && url.getPath() != null && url.getPath().startsWith(studio.getPath());
    }

    private void saveExport(String data) {
        if (export != null) { show("请先完成当前导出。"); return; }
        try {
            if (data == null || data.length() > 28 * 1024 * 1024) throw new IllegalArgumentException();
            JSONObject request = new JSONObject(data);
            String name = request.getString("name").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (name.isEmpty() || name.length() > 180) throw new IllegalArgumentException();
            export = Base64.decode(request.getString("base64"), Base64.DEFAULT);
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(create, SAVE);
        } catch (Exception error) { export = null; show("无法打开文件保存器。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            picker = null;
        }
        if (request == SAVE) {
            byte[] bytes = export;
            export = null;
            if (result != RESULT_OK || bytes == null || data == null || data.getData() == null) return;
            Uri destination = data.getData();
            new Thread(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IllegalStateException();
                    output.write(bytes);
                    runOnUiThread(() -> show("已导出"));
                } catch (Exception error) { runOnUiThread(() -> show("导出失败")); }
            }).start();
        }
    }

    private void show(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        web.saveState(state);
        super.onSaveInstanceState(state);
    }
}
