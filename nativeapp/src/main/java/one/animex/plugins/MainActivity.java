package one.animex.plugins;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 3107;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        requestNotificationPermission();
        plugin = new AnimexPlugin();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        browserShell = buildBrowserShell();
        root.addView(browserShell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        webView.loadUrl(plugin.homeUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AnimexNative");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void immersive() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private LinearLayout buildBrowserShell() {
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
        bar.addView(button("Back", v -> { if (webView.canGoBack()) webView.goBack(); }));
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
        scroll.addView(bar, new HorizontalScrollView.LayoutParams(-2, -1));
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

        webView = createWebView();
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

    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
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
                .setPositiveButton("Search", (d, which) -> {
                    String q = input.getText().toString().trim();
                    if (!q.isEmpty()) webView.loadUrl(plugin.searchUrl(q));
                }).show();
    }

    private void probeMedia() {
        long[] delays = {400, 1200, 3000, 7000};
        for (long delay : delays) {
            webView.postDelayed(() ->
                    webView.evaluateJavascript(CAPTURE_SCRIPT, ignored -> {}), delay);
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
        mediaUrl = url;
        if (title != null && !title.isBlank()) mediaTitle = title;
        mediaHeaders.clear();
        if (headers != null) mediaHeaders.putAll(headers);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) mediaHeaders.put("Cookie", cookie);
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
        status.setText("Looking for the active episode stream…");
        webView.evaluateJavascript(CAPTURE_SCRIPT, ignored ->
                webView.postDelayed(() -> {
                    if (mediaUrl == null) {
                        status.setText("Start playback, then tap Download again.");
                        Toast.makeText(this, "No direct episode stream captured yet",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Intent i = new Intent(this, EpisodeDownloadService.class);
                        i.putExtra(EpisodeDownloadService.EXTRA_URL, mediaUrl);
                        i.putExtra(EpisodeDownloadService.EXTRA_TITLE, mediaTitle);
                        i.putExtra(EpisodeDownloadService.EXTRA_REFERER,
                                mediaHeaders.getOrDefault("Referer", webView.getUrl()));
                        i.putExtra(EpisodeDownloadService.EXTRA_USER_AGENT,
                                mediaHeaders.getOrDefault("User-Agent",
                                        webView.getSettings().getUserAgentString()));
                        i.putExtra(EpisodeDownloadService.EXTRA_COOKIE,
                                mediaHeaders.getOrDefault("Cookie", ""));
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                        else startService(i);
                        Toast.makeText(this, "Episode download started",
                                Toast.LENGTH_SHORT).show();
                    }
                }, 650));
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
        immersive();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        browserShell.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        immersive();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class SourceClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            if (plugin.allowsTopLevelNavigation(request.getUrl())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); }
            catch (Exception ignored) {
                Toast.makeText(MainActivity.this, "External link could not be opened",
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
            String u = request.getUrl().toString();
            if (looksLikeMedia(u)) capture(u, mediaTitle, request.getRequestHeaders());
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            status.setText("AnimeX · " + String.valueOf(Uri.parse(url).getHost()));
            probeMedia();
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (progress < 100) status.setText("Loading " + progress + "%");
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
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", userAgent == null
                    ? webView.getSettings().getUserAgentString() : userAgent);
            capture(url, webView.getTitle(), h);
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
        private static final String BASE = "https://animex.one";
        public String displayName() { return "AnimeX"; }
        public String homeUrl() { return BASE + "/home"; }
        public String searchUrl(String query) {
            return BASE + "/catalog?search=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        public boolean allowsTopLevelNavigation(Uri uri) {
            String host = uri.getHost();
            return host != null &&
                    (host.equals("animex.one") || host.endsWith(".animex.one"));
        }
    }
}
