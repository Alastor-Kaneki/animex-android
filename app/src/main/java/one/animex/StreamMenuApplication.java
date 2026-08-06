package one.animex;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class StreamMenuApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity, StreamController> controllers = new WeakHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        attach(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        attach(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        StreamController controller = controllers.get(activity);
        if (controller != null) controller.cancelHold();
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        StreamController controller = controllers.remove(activity);
        if (controller != null) controller.destroy();
    }

    private void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        StreamController controller = controllers.get(activity);
        if (controller == null) {
            controller = new StreamController(activity);
            controllers.put(activity, controller);
        }
        controller.attach();
    }

    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

    static final class StreamController implements StreamTouchHandler {
        private static final String HOME_URL = "https://animex.one/";
        private static final int STORAGE_PERMISSION_REQUEST = 2012;
        private static final long HOLD_DURATION_MS = 5_000L;
        private static final long NAVIGATION_POLL_MS = 650L;
        private static final long CANDIDATE_TTL_MS = 30L * 60L * 1_000L;
        private static final int MAX_CANDIDATES = 32;

        /*
         * Scan only actual video elements. Resource Timing entries survive client-side
         * navigation and caused the home-page preview to be selected on episode pages.
         */
        private static final String MEDIA_PROBE_SCRIPT =
                "(function(){"
                        + "var out=[],seen={};"
                        + "function add(v,u,primary){"
                        + "if(typeof u!=='string'||!u||seen[u])return;seen[u]=1;"
                        + "var r=v.getBoundingClientRect(),s=getComputedStyle(v);"
                        + "var visible=r.width>8&&r.height>8&&r.bottom>0&&r.right>0&&"
                        + "r.top<(innerHeight||document.documentElement.clientHeight)&&"
                        + "r.left<(innerWidth||document.documentElement.clientWidth)&&"
                        + "s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity||1)>0.02;"
                        + "var playing=!v.paused&&!v.ended&&v.readyState>=2;"
                        + "out.push({url:u,playing:playing,muted:!!v.muted,autoplay:!!v.autoplay,"
                        + "loop:!!v.loop,visible:visible,area:Math.round(r.width*r.height),"
                        + "currentTime:Number(v.currentTime)||0,"
                        + "duration:isFinite(v.duration)?Number(v.duration):0,primary:!!primary});"
                        + "}"
                        + "Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){"
                        + "add(v,v.currentSrc,true);add(v,v.src,false);"
                        + "Array.prototype.forEach.call(v.querySelectorAll('source'),function(s){add(v,s.src,false);});"
                        + "});return out.slice(0,24);"
                        + "})()";

        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final LinkedHashMap<String, MediaCandidate> candidates = new LinkedHashMap<>();

        private WebView webView;
        private float holdStartX;
        private float holdStartY;
        private final int touchSlop;
        private boolean attachedToWindow;
        private boolean navigationPollStarted;
        private String currentPageKey = "";

        private final Runnable holdRunnable = new Runnable() {
            @Override
            public void run() {
                activity.getWindow().getDecorView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS);
                openMenu();
            }
        };

        private final Runnable navigationPoll = new Runnable() {
            @Override
            public void run() {
                syncPageContext();
                handler.postDelayed(this, NAVIGATION_POLL_MS);
            }
        };

        StreamController(Activity activity) {
            this.activity = activity;
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        void attach() {
            View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                WebView found = findWebView(decor);
                if (found != null) {
                    webView = found;
                    syncPageContext();
                    installCaptureClient(found);
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        installLegacyTouchListener(found);
                    }
                    scheduleProbe();
                    if (!navigationPollStarted) {
                        navigationPollStarted = true;
                        handler.post(navigationPoll);
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !attachedToWindow) {
                    StreamWindowCallbackApi26.install(activity, this);
                    attachedToWindow = true;
                }
            });
        }

        void destroy() {
            cancelHold();
            handler.removeCallbacksAndMessages(null);
            candidates.clear();
            webView = null;
        }

        void cancelHold() {
            handler.removeCallbacks(holdRunnable);
        }

        @Override
        public void onWindowTouch(MotionEvent event) {
            handleTouch(event);
        }

        private void installLegacyTouchListener(WebView target) {
            target.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !view.hasFocus()) {
                    view.requestFocus();
                }
                handleTouch(event);
                return false;
            });
        }

        private void handleTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdStartX = event.getX();
                    holdStartY = event.getY();
                    handler.removeCallbacks(holdRunnable);
                    handler.postDelayed(holdRunnable, HOLD_DURATION_MS);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - holdStartX) > touchSlop
                            || Math.abs(event.getY() - holdStartY) > touchSlop) {
                        handler.removeCallbacks(holdRunnable);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(holdRunnable);
                    break;
                default:
                    break;
            }
        }

        private WebView findWebView(View view) {
            if (view instanceof WebView) return (WebView) view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    WebView result = findWebView(group.getChildAt(i));
                    if (result != null) return result;
                }
            }
            return null;
        }

        private void installCaptureClient(WebView target) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            WebViewClient current = target.getWebViewClient();
            if (current instanceof CapturingWebViewClient) return;
            target.setWebViewClient(new CapturingWebViewClient(current));
        }

        private void scheduleProbe() {
            handler.postDelayed(() -> probeDom(null), 500);
            handler.postDelayed(() -> probeDom(null), 1_600);
            handler.postDelayed(() -> probeDom(null), 4_000);
        }

        private void resetPageContext(String url) {
            String key = normalizePageKey(url);
            if (!key.equals(currentPageKey)) {
                currentPageKey = key;
                candidates.clear();
            }
        }

        private void syncPageContext() {
            WebView target = webView;
            if (target != null) resetPageContext(target.getUrl());
        }

        private String normalizePageKey(String value) {
            if (value == null || value.trim().isEmpty()) return "about:blank";
            String key = value.trim();
            while (key.length() > 1 && key.endsWith("/")) {
                key = key.substring(0, key.length() - 1);
            }
            return key;
        }

        private void probeDom(Runnable after) {
            WebView target = webView;
            if (target == null) {
                if (after != null) after.run();
                return;
            }

            syncPageContext();
            final String probePageKey = currentPageKey;
            target.evaluateJavascript(MEDIA_PROBE_SCRIPT, value -> {
                if (!probePageKey.equals(currentPageKey)) {
                    if (after != null) after.run();
                    return;
                }
                try {
                    JSONArray array = new JSONArray(value == null ? "[]" : value);
                    int viewportArea = Math.max(1, target.getWidth() * target.getHeight());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject object = array.optJSONObject(i);
                        if (object == null) continue;
                        String url = object.optString("url", "");
                        boolean playing = object.optBoolean("playing", false);
                        boolean muted = object.optBoolean("muted", false);
                        boolean autoplay = object.optBoolean("autoplay", false);
                        boolean loop = object.optBoolean("loop", false);
                        boolean visible = object.optBoolean("visible", false);
                        boolean primary = object.optBoolean("primary", false);
                        int area = Math.max(0, object.optInt("area", 0));
                        double currentTime = Math.max(0, object.optDouble("currentTime", 0));
                        double duration = Math.max(0, object.optDouble("duration", 0));

                        boolean likelyPreview = muted && (autoplay || loop)
                                && (duration == 0 || duration < 600
                                || area < (int) (viewportArea * 0.70));
                        if (likelyPreview) continue;

                        int score = 100;
                        if (playing) score += 1_000;
                        if (visible) score += 300;
                        if (primary) score += 120;
                        if (currentTime > 1) score += 120;
                        if (duration > 600) score += 160;
                        score += Math.min(300, (area * 300) / viewportArea);
                        if (muted) score -= 80;
                        if (autoplay) score -= 100;
                        if (loop) score -= 120;

                        String discovery = playing
                                ? "currently playing video"
                                : visible ? "visible page player" : "page player";
                        capture(url, Collections.emptyMap(), discovery, score, probePageKey);
                    }
                } catch (Exception ignored) {
                    // Passive network capture can still supply the provider stream.
                }
                if (after != null) after.run();
            });
        }

        private void captureFromRequest(String url, Map<String, String> headers, String discovery) {
            Map<String, String> copied = headers == null
                    ? Collections.emptyMap() : new HashMap<>(headers);
            handler.post(() -> {
                syncPageContext();
                int score = 600;
                if (mediaKind(url).equals("HLS")) score += 160;
                String referer = header(copied, "Referer");
                if (referer != null && !referer.contains("animex.one")) score += 80;
                capture(url, copied, discovery, score, currentPageKey);
            });
        }

        private void capture(String url, Map<String, String> requestHeaders,
                             String discovery, int score, String pageKey) {
            if (!looksLikeMedia(url) || pageKey == null || !pageKey.equals(currentPageKey)) return;

            WebView target = webView;
            String title = target == null ? null : target.getTitle();
            if (title == null || title.trim().isEmpty()) title = "Animex episode";

            HashMap<String, String> headers = new HashMap<>();
            MediaCandidate existing = candidates.get(url);
            if (existing != null) headers.putAll(existing.headers);
            if (requestHeaders != null) headers.putAll(requestHeaders);

            if (target != null) {
                String userAgent = target.getSettings().getUserAgentString();
                if (userAgent != null && !userAgent.isEmpty()) {
                    putHeader(headers, "User-Agent", userAgent);
                }
                String referer = header(headers, "Referer");
                if (referer == null || referer.trim().isEmpty()) {
                    referer = target.getUrl();
                    if (referer != null && !referer.isEmpty()) {
                        putHeader(headers, "Referer", referer);
                    }
                }
            }

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) putHeader(headers, "Cookie", cookies);

            long now = System.currentTimeMillis();
            int mergedScore = existing == null ? score : Math.max(score, existing.score);
            String mergedDiscovery = existing != null && existing.score > score
                    ? existing.discovery : discovery;
            MediaCandidate candidate = new MediaCandidate(
                    url, title, mediaKind(url), sourceHost(url), mergedDiscovery,
                    headers, pageKey, mergedScore, now);

            candidates.remove(url);
            candidates.put(url, candidate);
            while (candidates.size() > MAX_CANDIDATES) {
                String oldest = candidates.keySet().iterator().next();
                candidates.remove(oldest);
            }
        }

        private void putHeader(Map<String, String> headers, String name, String value) {
            String existing = null;
            for (String key : headers.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    existing = key;
                    break;
                }
            }
            if (existing != null) headers.remove(existing);
            headers.put(name, value);
        }

        private boolean looksLikeMedia(String value) {
            if (value == null) return false;
            String lower = value.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
            if (looksLikeAd(lower)) return false;
            return lower.contains(".m3u8")
                    || lower.contains(".mp4")
                    || lower.contains(".m4v")
                    || lower.contains(".webm")
                    || lower.contains("mime=video")
                    || lower.contains("type=video");
        }

        private boolean looksLikeAd(String lower) {
            return lower.contains("/ads/")
                    || lower.contains("/advert/")
                    || lower.contains("vast.xml")
                    || lower.contains("vast-ad")
                    || lower.contains("video-ad")
                    || lower.contains("preroll")
                    || lower.contains("pre-roll");
        }

        private String mediaKind(String url) {
            String lower = url == null ? "" : url.toLowerCase(Locale.US);
            if (lower.contains(".m3u8")) return "HLS";
            if (lower.contains(".webm")) return "WebM";
            if (lower.contains(".m4v")) return "M4V";
            return "MP4";
        }

        private String sourceHost(String url) {
            try {
                String host = Uri.parse(url).getHost();
                return host == null ? "unknown source" : host;
            } catch (Exception ignored) {
                return "unknown source";
            }
        }

        private List<MediaCandidate> snapshot() {
            syncPageContext();
            long now = System.currentTimeMillis();
            ArrayList<MediaCandidate> result = new ArrayList<>();
            for (MediaCandidate candidate : candidates.values()) {
                if (candidate.pageKey.equals(currentPageKey)
                        && now - candidate.capturedAt <= CANDIDATE_TTL_MS) {
                    result.add(candidate);
                }
            }
            result.sort((left, right) -> {
                int score = Integer.compare(right.score, left.score);
                return score != 0 ? score : Long.compare(right.capturedAt, left.capturedAt);
            });
            return result;
        }

        private void openMenu() {
            syncPageContext();
            probeDom(() -> handler.postDelayed(this::showMenu, 120));
        }

        private void showMenu() {
            List<MediaCandidate> media = snapshot();
            if (media.isEmpty()) {
                new AlertDialog.Builder(activity)
                        .setTitle("Episode stream")
                        .setMessage("No episode stream has been detected yet.\n\n"
                                + "Start the episode, let it play for a few seconds, then hold the screen again.")
                        .setPositiveButton("Scan again", (dialog, which) -> openMenu())
                        .setNeutralButton("Open in browser", (dialog, which) -> openCurrentPage())
                        .setNegativeButton("Close", null)
                        .show();
                return;
            }

            int count = Math.min(media.size(), 12);
            String[] labels = new String[count];
            for (int i = 0; i < count; i++) {
                MediaCandidate candidate = media.get(i);
                labels[i] = candidate.kind + "  •  " + candidate.host
                        + "\n" + candidate.discovery;
            }

            int[] selected = {0};
            new AlertDialog.Builder(activity)
                    .setTitle("Download episode")
                    .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                    .setPositiveButton("Download",
                            (dialog, which) -> beginDownload(media.get(selected[0])))
                    .setNeutralButton("Copy URL",
                            (dialog, which) -> copyUrl(media.get(selected[0])))
                    .setNegativeButton("Close", null)
                    .show();
        }

        private void openCurrentPage() {
            String url = webView != null && webView.getUrl() != null ? webView.getUrl() : HOME_URL;
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception error) {
                Toast.makeText(activity, "No browser could open this page", Toast.LENGTH_SHORT).show();
            }
        }

        private void copyUrl(MediaCandidate candidate) {
            ClipboardManager clipboard = (ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Animex stream", candidate.url));
                Toast.makeText(activity, "Stream URL copied", Toast.LENGTH_SHORT).show();
            }
        }

        private void beginDownload(MediaCandidate candidate) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST);
                Toast.makeText(activity,
                        "Allow storage, then hold for five seconds and download again",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if ("HLS".equals(candidate.kind)) startHls(candidate);
            else startDirect(candidate);
        }

        private void startHls(MediaCandidate candidate) {
            Intent intent = new Intent(activity, EpisodeDownloadService.class);
            intent.putExtra(EpisodeDownloadService.EXTRA_URL, candidate.url);
            intent.putExtra(EpisodeDownloadService.EXTRA_TITLE, candidate.title);
            intent.putExtra(EpisodeDownloadService.EXTRA_REFERER,
                    header(candidate.headers, "Referer"));
            intent.putExtra(EpisodeDownloadService.EXTRA_USER_AGENT,
                    header(candidate.headers, "User-Agent"));
            intent.putExtra(EpisodeDownloadService.EXTRA_COOKIE,
                    header(candidate.headers, "Cookie"));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent);
                } else {
                    activity.startService(intent);
                }
                Toast.makeText(activity, "Episode download started", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(activity,
                        "Could not start download: " + safeMessage(error),
                        Toast.LENGTH_LONG).show();
            }
        }

        private void startDirect(MediaCandidate candidate) {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(candidate.url));
                String fallback = URLUtil.guessFileName(candidate.url, null, mime(candidate.kind));
                String filename = fileName(candidate.title, fallback);
                request.setTitle(filename);
                request.setDescription("Downloading episode from " + candidate.host);
                request.setMimeType(mime(candidate.kind));
                addRequestHeader(request, "User-Agent", header(candidate.headers, "User-Agent"));
                addRequestHeader(request, "Referer", header(candidate.headers, "Referer"));
                addRequestHeader(request, "Cookie", header(candidate.headers, "Cookie"));
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_MOVIES, "Animex/" + filename);
                DownloadManager manager = (DownloadManager)
                        activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    throw new IllegalStateException("Android Download Manager is unavailable");
                }
                manager.enqueue(request);
                Toast.makeText(activity, "Episode download started", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(activity, "Download failed: " + safeMessage(error),
                        Toast.LENGTH_LONG).show();
            }
        }

        private void addRequestHeader(DownloadManager.Request request, String name, String value) {
            if (value != null && !value.trim().isEmpty()) request.addRequestHeader(name, value);
        }

        private String header(Map<String, String> headers, String name) {
            if (headers == null) return null;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
            return null;
        }

        private String mime(String kind) {
            if ("WebM".equals(kind)) return "video/webm";
            if ("M4V".equals(kind)) return "video/x-m4v";
            return "video/mp4";
        }

        private String fileName(String title, String fallback) {
            String extension = ".mp4";
            if (fallback != null) {
                String lower = fallback.toLowerCase(Locale.US);
                if (lower.endsWith(".webm")) extension = ".webm";
                else if (lower.endsWith(".m4v")) extension = ".m4v";
            }
            String base = title == null ? "Animex episode" : title;
            base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                    .replaceAll("\\s+", " ").trim();
            if (base.isEmpty()) base = "Animex episode";
            if (base.length() > 96) base = base.substring(0, 96).trim();
            return base + extension;
        }

        private String safeMessage(Exception error) {
            String message = error.getMessage();
            return message == null || message.trim().isEmpty()
                    ? error.getClass().getSimpleName() : message;
        }

        private final class CapturingWebViewClient extends WebViewClient {
            private final WebViewClient delegate;

            CapturingWebViewClient(WebViewClient delegate) {
                this.delegate = delegate == null ? new WebViewClient() : delegate;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                resetPageContext(url);
                delegate.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                resetPageContext(url);
                delegate.onPageCommitVisible(view, url);
                scheduleProbe();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                resetPageContext(url);
                delegate.onPageFinished(view, url);
                scheduleProbe();
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (looksLikeMedia(url)) {
                    captureFromRequest(url, Collections.emptyMap(), "player network load");
                }
                delegate.onLoadResource(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return delegate.shouldOverrideUrlLoading(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return delegate.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (looksLikeMedia(url)) {
                    captureFromRequest(url, request.getRequestHeaders(), "provider request");
                }
                return delegate.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (looksLikeMedia(url)) {
                    captureFromRequest(url, Collections.emptyMap(), "provider request");
                }
                return delegate.shouldInterceptRequest(view, url);
            }
        }
    }

    private static final class MediaCandidate {
        final String url;
        final String title;
        final String kind;
        final String host;
        final String discovery;
        final Map<String, String> headers;
        final String pageKey;
        final int score;
        final long capturedAt;

        MediaCandidate(String url, String title, String kind, String host,
                       String discovery, Map<String, String> headers,
                       String pageKey, int score, long capturedAt) {
            this.url = url;
            this.title = title;
            this.kind = kind;
            this.host = host;
            this.discovery = discovery;
            this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
            this.pageKey = pageKey;
            this.score = score;
            this.capturedAt = capturedAt;
        }
    }
}
