package one.animex.plugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MainActivity extends Activity {
    private static final String CAPTURE_SCRIPT =
            "(() => {const v=document.querySelector('video');" +
            "const s=v&&(v.currentSrc||v.src||(v.querySelector('source')||{}).src);" +
            "if(s){AnimexNative.reportMedia(String(s),document.title||'Animex episode');return true;}" +
            "return false;})()";

    private final Map<String, String> mediaHeaders = new ConcurrentHashMap<>();
    private AnimePlugin plugin;
    private FrameLayout root;
    private LinearLayout browserShell;
    private WebView webView;
    private TextView status;
    private Button download;
    private volatile String mediaUrl;
    private volatile String mediaTitle = "Animex episode";
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean initialized;
    private String lastDiagnostic = "No diagnostic is available.";

    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.Theme_AnimexPlugins);
        super.onCreate(state);
        installCrashLogger();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        showStartingView();
        safeImmersive();

        root.post(() -> initializeApp(state));
    }

    private void initializeApp(Bundle state) {
        if (initialized || isFinishing()) return;
        try {
            plugin = new AnimexPlugin();
            browserShell = buildBrowserShell();
            root.removeAllViews();
            root.addView(browserShell, new FrameLayout.LayoutParams(-1, -1));
            initialized = true;
            safeImmersive();

            if (state == null || webView.restoreState(state) == null) {
                webView.loadUrl(plugin.homeUrl());
            }
        } catch (Throwable error) {
            initialized = false;
            writeDiagnostic("launch", error);
            showInitializationFailure(error);
        }
    }

    private void showStartingView() {
        TextView loading = new TextView(this);
        loading.setText("Starting Animex Plugins…");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(18f);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(Color.BLACK);
        root.removeAllViews();
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showInitializationFailure(Throwable error) {
        root.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        panel.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Animex Plugins could not start");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView message = new TextView(this);
        String detail = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
            detail += ": " + error.getMessage().trim();
        }
        message.setText("The failure was contained instead of closing the app.\n\n" + detail);
        message.setTextColor(Color.LTGRAY);
        message.setTextSize(14f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(18), 0, dp(18));
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));

        Button retry = button("Retry", v -> {
            showStartingView();
            root.post(() -> initializeApp(null));
        });
        panel.addView(retry, centeredButtonParams());

        Button browser = button("Open AnimeX in browser", v -> openExternal(Uri.parse(AnimexPlugin.BASE)));
        panel.addView(browser, centeredButtonParams());

        Button copy = button("Copy diagnostic", v -> copyDiagnostic());
        panel.addView(copy, centeredButtonParams());

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        safeImmersive();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            try { webView.saveState(outState); }
            catch (Throwable error) { writeDiagnostic("save-state", error); }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        safeImmersive();
        if (webView != null) {
            try { webView.onResume(); }
            catch (Throwable error) { writeDiagnostic("resume", error); }
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            try { webView.onPause(); }
            catch (Throwable error) { writeDiagnostic("pause", error); }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AnimexNative");
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable error) {
                writeDiagnostic("destroy", error);
            }
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void safeImmersive() {
        try {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable error) {
            writeDiagnostic("immersive", error);
        }
    }

    private LinearLayout buildBrowserShell() {
        WebView created = createWebView();
        webView = created;

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.BLACK);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackgroundColor(Color.BLACK);
        bar.addView(button("Home", v -> webView.loadUrl(plugin.homeUrl())));
        bar.addView(button("Back", v -> {
            if (webView.canGoBack()) webView.goBack();
        }));
        bar.addView(button("Reload", v -> webView.reload()));
        bar.addView(button("Search", v -> searchDialog()));
        download = button("Download", v -> downloadEpisode());
        download.setEnabled(false);
        bar.addView(download);

        TextView source = new TextView(this);
        source.setText(plugin.displayName());
        source.setTextColor(Color.WHITE);
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(source, new LinearLayout.LayoutParams(-2, dp(44)));

        scroll.addView(bar, new FrameLayout.LayoutParams(-2, -1));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, dp(56)));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setBackgroundColor(Color.rgb(8, 8, 8));
        status.setSingleLine(true);
        status.setTextSize(12f);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10), 0, dp(10), 0);
        status.setText("Loading AnimeX…");
        shell.addView(status, new LinearLayout.LayoutParams(-1, dp(28)));

        shell.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        return shell;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(24, 24, 24));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.rgb(62, 62, 62));
        b.setBackground(bg);
        b.setOnClickListener(listener);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        p.setMarginEnd(dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout.LayoutParams centeredButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(8);
        return params;
    }

    private WebView createWebView() {
        if (Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() == null) {
            throw new IllegalStateException("No Android System WebView provider is enabled");
        }

        WebView w = new WebView(getApplicationContext());
        w.setBackgroundColor(Color.BLACK);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " AnimexPlugins/0.1.1");
        if (Build.VERSION.SDK_INT >= 26) {
            try { s.setSafeBrowsingEnabled(true); }
            catch (Throwable error) { writeDiagnostic("safe-browsing", error); }
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new MediaBridge(), "AnimexNative");
        w.setDownloadListener(new MediaDownloadListener());
        w.setWebViewClient(new SourceClient());
        w.setWebChromeClient(new ChromeClient());
        return w;
    }

    private void searchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Anime title");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setBackgroundColor(Color.rgb(20, 20, 20));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Search AnimeX")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = input.getText().toString().trim();
                    if (!query.isEmpty()) webView.loadUrl(plugin.searchUrl(query));
                })
                .show();
    }

    private void probeMedia() {
        long[] delays = {400, 1200, 3000, 7000};
        for (long delay : delays) {
            webView.postDelayed(() -> {
                try { webView.evaluateJavascript(CAPTURE_SCRIPT, ignored -> { }); }
                catch (Throwable error) { writeDiagnostic("media-probe", error); }
            }, delay);
        }
    }

    private void capture(String url, String title, Map<String, String> headers) {
        if (!looksLikeMedia(url)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Map<String, String> copy = headers == null
                    ? Collections.emptyMap() : new HashMap<>(headers);
            runOnUiThread(() -> capture(url, title, copy));
            return;
        }
        if (webView == null || download == null || status == null) return;

        mediaUrl = url;
        if (title != null && !title.trim().isEmpty()) mediaTitle = title;
        mediaHeaders.clear();
        if (headers != null) mediaHeaders.putAll(headers);

        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) mediaHeaders.put("Cookie", cookie);
        } catch (Throwable error) {
            writeDiagnostic("cookies", error);
        }

        if (webView.getUrl() != null) mediaHeaders.put("Referer", webView.getUrl());
        mediaHeaders.put("User-Agent", webView.getSettings().getUserAgentString());
        download.setEnabled(true);
        status.setText("Episode stream ready for download");
    }

    private boolean looksLikeMedia(String value) {
        if (value == null) return false;
        String u = value.toLowerCase(Locale.ROOT);
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return false;
        return u.contains(".m3u8") || u.contains(".mp4") ||
                u.contains(".m4v") || u.contains(".webm") ||
                u.contains("mime=video") || u.contains("type=video");
    }

    private void downloadEpisode() {
        if (webView == null) return;
        status.setText("Looking for the active episode stream…");
        try {
            webView.evaluateJavascript(CAPTURE_SCRIPT, ignored ->
                    webView.postDelayed(this::startCapturedDownload, 650));
        } catch (Throwable error) {
            writeDiagnostic("download-probe", error);
            startCapturedDownload();
        }
    }

    private void startCapturedDownload() {
        if (mediaUrl == null) {
            status.setText("Start playback, then tap Download again.");
            Toast.makeText(this, "No direct episode stream captured yet", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent intent = new Intent(this, EpisodeDownloadService.class);
            intent.putExtra(EpisodeDownloadService.EXTRA_URL, mediaUrl);
            intent.putExtra(EpisodeDownloadService.EXTRA_TITLE, mediaTitle);
            intent.putExtra(EpisodeDownloadService.EXTRA_REFERER,
                    mediaHeaders.getOrDefault("Referer", webView.getUrl()));
            intent.putExtra(EpisodeDownloadService.EXTRA_USER_AGENT,
                    mediaHeaders.getOrDefault("User-Agent",
                            webView.getSettings().getUserAgentString()));
            intent.putExtra(EpisodeDownloadService.EXTRA_COOKIE,
                    mediaHeaders.getOrDefault("Cookie", ""));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            Toast.makeText(this, "Episode download started", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            writeDiagnostic("start-download", error);
            status.setText("Download could not start: " + error.getClass().getSimpleName());
            Toast.makeText(this, "Download could not start", Toast.LENGTH_LONG).show();
        }
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        browserShell.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        safeImmersive();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (browserShell != null) browserShell.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        safeImmersive();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable error) {
            writeDiagnostic("external-browser", error);
            Toast.makeText(this, "No browser could open this link", Toast.LENGTH_LONG).show();
        }
    }

    private void copyDiagnostic() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Animex Plugins diagnostic", lastDiagnostic));
            Toast.makeText(this, "Diagnostic copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void installCrashLogger() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeDiagnostic("uncaught-" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private synchronized void writeDiagnostic(String stage, Throwable error) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("Animex Plugins 0.1.1-alpha");
        writer.println("Stage: " + stage);
        writer.println("Android SDK: " + Build.VERSION.SDK_INT);
        writer.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        error.printStackTrace(writer);
        writer.flush();
        lastDiagnostic = buffer.toString();

        try {
            File base = getExternalFilesDir(null);
            if (base == null) base = getFilesDir();
            File file = new File(base, "last-launch-crash.txt");
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(lastDiagnostic.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
            // Diagnostic writing must never cause another crash.
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class SourceClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            if (plugin.allowsTopLevelNavigation(request.getUrl())) return false;
            openExternal(request.getUrl());
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (looksLikeMedia(url)) capture(url, mediaTitle, request.getRequestHeaders());
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (status != null) {
                status.setText("AnimeX · " + String.valueOf(Uri.parse(url).getHost()));
            }
            probeMedia();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            if (request.isForMainFrame() && status != null) {
                status.setText("AnimeX failed to load. Tap Reload.");
            }
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (status != null && progress < 100) status.setText("Loading " + progress + "%");
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            showCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    private final class MediaBridge {
        @JavascriptInterface
        public void reportMedia(String url, String title) {
            capture(url, title, Collections.emptyMap());
        }
    }

    private final class MediaDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent,
                                    String contentDisposition, String mimeType,
                                    long contentLength) {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", userAgent == null
                    ? webView.getSettings().getUserAgentString() : userAgent);
            capture(url, webView.getTitle(), headers);
            downloadEpisode();
        }
    }

    interface AnimePlugin {
        String displayName();
        String homeUrl();
        String searchUrl(String query);
        boolean allowsTopLevelNavigation(Uri uri);
    }

    static final class AnimexPlugin implements AnimePlugin {
        static final String BASE = "https://animex.one";

        @Override
        public String displayName() {
            return "AnimeX";
        }

        @Override
        public String homeUrl() {
            return BASE + "/";
        }

        @Override
        public String searchUrl(String query) {
            try {
                return BASE + "/catalog?search=" + URLEncoder.encode(query, "UTF-8");
            } catch (Exception ignored) {
                return BASE + "/catalog";
            }
        }

        @Override
        public boolean allowsTopLevelNavigation(Uri uri) {
            String host = uri.getHost();
            return host != null &&
                    (host.equals("animex.one") || host.endsWith(".animex.one"));
        }
    }
}
