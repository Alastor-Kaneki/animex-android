package one.animex;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://animex.one/";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int STORAGE_PERMISSION_REQUEST = 2002;

    private static final int MENU_HOME = 1;
    private static final int MENU_REFRESH = 2;
    private static final int MENU_ADBLOCK = 3;
    private static final int MENU_POPUPS = 4;
    private static final int MENU_BROWSER = 5;
    private static final int MENU_CLEAR = 6;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private ValueCallback<Uri[]> fileChooserCallback;
    private SharedPreferences preferences;
    private final Set<String> blockedHosts = Collections.synchronizedSet(new HashSet<>());
    private String protectionTemplate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Animex);
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }

        preferences = getSharedPreferences("animex_settings", MODE_PRIVATE);
        loadBlockList();
        createUi();
        configureWebView(webView, true);
        hideStatusBar();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    private void createUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF000000);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3));
        progressParams.gravity = Gravity.TOP;
        root.addView(progress, progressParams);

        setContentView(root);
    }

    private void configureWebView(WebView target, boolean primary) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " AnimexApp/0.5");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(target, true);

        target.setBackgroundColor(0xFF000000);

        // Remove Android's elastic/stretch overscroll effect.
        target.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Hide browser-like scrollbars.
        target.setVerticalScrollBarEnabled(false);
        target.setHorizontalScrollBarEnabled(false);
        target.setScrollbarFadingEnabled(true);
        target.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // Make the WebView receive focus immediately instead of requiring
        // an initial focus tap followed by a second activation tap.
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.requestFocus(View.FOCUS_DOWN);

        target.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN &&
                    !view.hasFocus()) {
                view.requestFocus();
            }

            // Never consume the event. The website still receives the tap.
            return false;
        });

        if (primary) {
            target.setWebViewClient(new AnimexWebViewClient());
            target.setWebChromeClient(new AnimexChromeClient());
            target.setDownloadListener(new AnimexDownloadListener());
            target.setOnLongClickListener(v -> false);
        }
    }

    private void refreshPopupSettings() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
    }

    private boolean adBlockEnabled() {
        return true;
    }

    private boolean popupBlockEnabled() {
        return true;
    }

    private void loadBlockList() {
        blockedHosts.clear();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("ad_hosts.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.US);
                if (!line.isEmpty() && !line.startsWith("#")) {
                    blockedHosts.add(line);
                }
            }
        } catch (Exception ignored) {
            // The app still works if the optional host list cannot be loaded.
        }
    }

    private boolean hostMatches(String host, String base) {
        return host.equals(base) || host.endsWith("." + base);
    }

    private boolean isLikelyAdvertisingHost(String host) {
        if (host == null) {
            return false;
        }

        String normalized = host.toLowerCase(Locale.US).replace("-", "");

        String[] tokens = {
                "adserver",
                "adservice",
                "adnetwork",
                "adsyndication",
                "adsystem",
                "adsterra",
                "propellerads",
                "popunder",
                "popcash",
                "popads",
                "monetag",
                "exoclick",
                "clickadu",
                "clickaine",
                "onclicka",
                "trafficjunky",
                "revenuecpm",
                "effectivecpm",
                "highperformanceformat",
                "highperformancedisplayformat",
                "analytics",
                "telemetry",
                "tracking"
        };

        for (String token : tokens) {
            if (normalized.contains(token)) {
                return true;
            }
        }

        return false;
    }

    private boolean isLikelyAdvertisingPath(String value) {
        if (value == null) {
            return false;
        }

        String lower = value.toLowerCase(Locale.US);

        String[] patterns = {
                "/popunder",
                "/pop-up",
                "/popup",
                "/clickunder",
                "/adserver",
                "/adserve",
                "/ad-delivery",
                "/ads/",
                "/advert/",
                "/advertisement/",
                "/interstitial",
                "/preroll",
                "/pre-roll",
                "/vast.xml",
                "/vast?",
                "vast-ad",
                "video-ad",
                "banner-ad",
                "onclickad"
        };

        for (String pattern : patterns) {
            if (lower.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    private boolean isBlockedUrl(String value) {
        if (!adBlockEnabled() || value == null) {
            return false;
        }

        try {
            URI uri = new URI(value);
            String host = uri.getHost();

            if (host != null) {
                host = host.toLowerCase(Locale.US);

                if (isLikelyAdvertisingHost(host)) {
                    return true;
                }

                synchronized (blockedHosts) {
                    for (String blocked : blockedHosts) {
                        if (hostMatches(host, blocked)) {
                            return true;
                        }
                    }
                }
            }

            return isLikelyAdvertisingPath(value);
        } catch (Exception ignored) {
            return isLikelyAdvertisingPath(value);
        }
    }

    private boolean isTrustedMainFrameUrl(Uri uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();

        if (scheme == null) {
            return false;
        }

        scheme = scheme.toLowerCase(Locale.US);

        if (scheme.equals("about") ||
                scheme.equals("data") ||
                scheme.equals("blob")) {
            return true;
        }

        if (!scheme.equals("http") && !scheme.equals("https")) {
            return true;
        }

        String host = uri.getHost();

        if (host == null) {
            return false;
        }

        host = host.toLowerCase(Locale.US);

        String[] trustedHosts = {
                "animex.one",

                // Common account and OAuth providers.
                "accounts.google.com",
                "google.com",
                "discord.com",
                "anilist.co",
                "myanimelist.net",
                "github.com",
                "appleid.apple.com",
                "microsoftonline.com",

                // Common hosted authentication services.
                "auth0.com",
                "supabase.co",
                "supabase.com",
                "clerk.com",
                "clerk.accounts.dev",

                // Human-verification pages used during sign-in.
                "cloudflare.com",
                "hcaptcha.com",
                "recaptcha.net"
        };

        for (String trusted : trustedHosts) {
            if (hostMatches(host, trusted)) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldBlockMainFrame(Uri uri) {
        return popupBlockEnabled()
                && uri != null
                && isBlockedUrl(uri.toString());
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private String readAssetText(String name) {
        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        getAssets().open(name),
                        StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        } catch (Exception ignored) {
            return "";
        }

        return result.toString();
    }

    private void injectPageProtection() {
        if (!adBlockEnabled() && !popupBlockEnabled()) {
            return;
        }

        if (protectionTemplate == null) {
            protectionTemplate = readAssetText("protection.js");
        }

        if (protectionTemplate == null || protectionTemplate.isEmpty()) {
            return;
        }

        String script = protectionTemplate
                .replace("__ADBLOCK__", adBlockEnabled() ? "true" : "false")
                .replace("__POPUPBLOCK__", popupBlockEnabled() ? "true" : "false");

        webView.evaluateJavascript(script, null);
    }

    private void schedulePageProtection() {
        injectPageProtection();

        webView.postDelayed(
                this::injectPageProtection,
                150);

        webView.postDelayed(
                this::injectPageProtection,
                500);

        webView.postDelayed(
                this::injectPageProtection,
                1200);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleSpecialScheme(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about") ||
                scheme.equals("data") || scheme.equals("blob")) {
            return false;
        }

        try {
            Intent intent;
            if (scheme.equals("intent")) {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) {
                        webView.loadUrl(fallback);
                    }
                    return true;
                }
            } else {
                intent = new Intent(Intent.ACTION_VIEW, uri);
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Link blocked or unsupported", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean isOnline() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return true;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_HOME, 0, "Home").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_REFRESH, 1, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_ADBLOCK, 2, adBlockEnabled() ? "Disable ad blocker" : "Enable ad blocker")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_POPUPS, 3, popupBlockEnabled() ? "Disable popup blocker" : "Enable popup blocker")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_BROWSER, 4, "Open in browser").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_CLEAR, 5, "Clear site data").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem ad = menu.findItem(MENU_ADBLOCK);
        MenuItem popups = menu.findItem(MENU_POPUPS);
        if (ad != null) {
            ad.setTitle(adBlockEnabled() ? "Disable ad blocker" : "Enable ad blocker");
        }
        if (popups != null) {
            popups.setTitle(popupBlockEnabled() ? "Disable popup blocker" : "Enable popup blocker");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_HOME:
                webView.loadUrl(HOME_URL);
                return true;
            case MENU_REFRESH:
                webView.reload();
                return true;
            case MENU_ADBLOCK:
                preferences.edit().putBoolean("adblock", !adBlockEnabled()).apply();
                invalidateOptionsMenu();
                webView.reload();
                return true;
            case MENU_POPUPS:
                preferences.edit().putBoolean("popup_block", !popupBlockEnabled()).apply();
                refreshPopupSettings();
                invalidateOptionsMenu();
                webView.reload();
                return true;
            case MENU_BROWSER:
                openExternal(webView.getUrl() != null ? webView.getUrl() : HOME_URL);
                return true;
            case MENU_CLEAR:
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                webView.clearCache(true);
                webView.clearHistory();
                Toast.makeText(this, "Site data cleared", Toast.LENGTH_SHORT).show();
                webView.loadUrl(HOME_URL);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullScreenView != null) {
            exitFullScreen();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideStatusBar();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && fullScreenView == null) {
            hideStatusBar();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    private void enterFullScreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullScreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullScreenView = view;
        fullScreenCallback = callback;
        webView.setVisibility(View.GONE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        hideSystemBars();
    }

    private void exitFullScreen() {
        if (fullScreenView == null) {
            return;
        }
        root.removeView(fullScreenView);
        fullScreenView = null;
        webView.setVisibility(View.VISIBLE);
        if (getActionBar() != null) {
            getActionBar().show();
        }
        showSystemBars();
        hideStatusBar();
        if (fullScreenCallback != null) {
            fullScreenCallback.onCustomViewHidden();
            fullScreenCallback = null;
        }
    }

    private void hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller =
                    getWindow().getInsetsController();

            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController
                                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AnimexWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
            if (!isOnline()) {
                Toast.makeText(MainActivity.this, "No internet connection", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            schedulePageProtection();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            schedulePageProtection();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();

            if (request.isForMainFrame() && shouldBlockMainFrame(uri)) {
                return true;
            }

            if (isBlockedUrl(uri.toString())) {
                return true;
            }

            return handleSpecialScheme(uri);
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);

            if (shouldBlockMainFrame(uri)) {
                return true;
            }

            if (isBlockedUrl(url)) {
                return true;
            }

            return handleSpecialScheme(uri);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return isBlockedUrl(request.getUrl().toString()) ? emptyResponse() : null;
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return isBlockedUrl(url) ? emptyResponse() : null;
        }
    }

    private final class AnimexChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (!isUserGesture) {
                return false;
            }

            final WebView popup = new WebView(MainActivity.this);
            configureWebView(popup, false);
            popup.setWebViewClient(new WebViewClient() {
                private boolean forward(String url) {
                    if (url == null || url.isEmpty()) {
                        return false;
                    }
                    if (isBlockedUrl(url)) {
                        popup.destroy();
                        return true;
                    }
                    Uri uri = Uri.parse(url);

                    if (!isTrustedMainFrameUrl(uri)) {
                        popup.destroy();
                        return true;
                    }

                    if (!handleSpecialScheme(uri)) {
                        webView.loadUrl(url);
                    }

                    popup.destroy();
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return forward(request.getUrl().toString());
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return forward(url);
                }
            });

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            enterFullScreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullScreen();
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = filePathCallback;
            try {
                startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST);
            } catch (ActivityNotFoundException e) {
                fileChooserCallback = null;
                Toast.makeText(MainActivity.this, "No file picker found", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Web pages cannot silently access the camera or microphone through this wrapper.
            runOnUiThread(request::deny);
        }
    }

    private final class AnimexDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST);
                Toast.makeText(
                        MainActivity.this,
                        "Allow storage, then tap download again",
                        Toast.LENGTH_LONG).show();
                return;
            }

            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(filename);
                request.setDescription("Downloading from Animex");
                String resolvedMime = mimeType;
                if (resolvedMime == null || resolvedMime.isEmpty()) {
                    resolvedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(url));
                }
                if (resolvedMime != null) {
                    request.setMimeType(resolvedMime);
                }
                if (userAgent != null) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) {
                    request.addRequestHeader("Cookie", cookies);
                }
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager manager =
                        (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                openExternal(url);
            }
        }
    }
}
