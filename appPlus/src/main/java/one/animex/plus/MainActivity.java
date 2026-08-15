package one.animex.plus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.List;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://animex.one/";
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String UBO_XPI = "resource://android/assets/ublock/uBlockOrigin.xpi";
    private static final int UBO_TRIGGER_PRESSES = 3;
    private static final long UBO_TRIGGER_WINDOW_MS = 1500L;

    private static GeckoRuntime runtime;

    private NoZoomGeckoView geckoView;
    private GeckoSession session;
    private WebExtension uBlockOrigin;
    private ProgressBar pageProgress;
    private View launchOverlay;
    private TextView launchStatus;
    private boolean canGoBack;
    private boolean firstPagePainted;
    private int volumeUpPressCount;
    private long firstVolumeUpPressAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_AnimexPlus);
        super.onCreate(savedInstanceState);

        buildNativeShell();
        hideSystemUi();

        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .extensionsProcessEnabled(true)
                    .forceUserScalableEnabled(false)
                    .doubleTapZoomingEnabled(false)
                    .inputAutoZoomEnabled(false)
                    .build();
            runtime = GeckoRuntime.create(getApplicationContext(), settings);
            runtime.warmUp();
        }

        configureExtensionInstaller();

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
                .build();

        session = new GeckoSession(sessionSettings);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                hideSystemUi();
            }

            @Override
            public void onCrash(GeckoSession crashedSession) {
                showNativeStatus("Restoring Animex…");
                Toast.makeText(MainActivity.this,
                        "Animex engine restarted",
                        Toast.LENGTH_SHORT).show();
                crashedSession.open(runtime);
                crashedSession.loadUri(HOME_URL);
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean value) {
                canGoBack = value;
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                pageProgress.setVisibility(View.VISIBLE);
                pageProgress.setProgress(2);
            }

            @Override
            public void onProgressChange(GeckoSession session, int value) {
                pageProgress.setProgress(value);
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                pageProgress.setProgress(100);
                pageProgress.animate()
                        .alpha(0f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            pageProgress.setVisibility(View.GONE);
                            pageProgress.setAlpha(1f);
                        })
                        .start();

                if (!firstPagePainted && success) {
                    firstPagePainted = true;
                    hideLaunchOverlay();
                } else if (!firstPagePainted) {
                    showNativeStatus("Couldn't load Animex");
                }
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        geckoView.coverUntilFirstPaint(Color.BLACK);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack);
        }

        loadUBlockThenLaunch();
    }

    private void buildNativeShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        geckoView = new NoZoomGeckoView(this);
        geckoView.setBackgroundColor(Color.BLACK);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2));
        progressParams.gravity = Gravity.TOP;
        root.addView(pageProgress, progressParams);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(dp(240), dp(120));
        logoParams.gravity = Gravity.CENTER;
        logoParams.bottomMargin = dp(24);
        overlay.addView(logo, logoParams);

        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(dp(28), dp(28));
        spinnerParams.gravity = Gravity.CENTER;
        spinnerParams.topMargin = dp(112);
        overlay.addView(spinner, spinnerParams);

        launchStatus = new TextView(this);
        launchStatus.setText("Starting Animex…");
        launchStatus.setTextColor(0xFFBDBDBD);
        launchStatus.setTextSize(13f);
        launchStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.CENTER;
        statusParams.topMargin = dp(172);
        overlay.addView(launchStatus, statusParams);

        launchOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void configureExtensionInstaller() {
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension,
                    String[] permissions,
                    String[] origins,
                    String[] dataCollectionPermissions) {
                if (UBO_ID.equals(extension.id)) {
                    return GeckoResult.fromValue(
                            new WebExtension.PermissionPromptResponse(true, false, false));
                }
                return GeckoResult.fromValue(
                        new WebExtension.PermissionPromptResponse(false, false, false));
            }

            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(
                    WebExtension extension,
                    String[] permissions,
                    String[] origins,
                    String[] dataCollectionPermissions) {
                return UBO_ID.equals(extension.id) ? GeckoResult.allow() : GeckoResult.deny();
            }

            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(
                    WebExtension extension,
                    String[] newPermissions,
                    String[] newOrigins,
                    String[] newDataCollectionPermissions) {
                return UBO_ID.equals(extension.id) ? GeckoResult.allow() : GeckoResult.deny();
            }
        });
    }

    private void loadUBlockThenLaunch() {
        showNativeStatus("Starting privacy engine…");
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.list().accept(
                extensions -> {
                    WebExtension installed = findUBlock(extensions);
                    if (installed != null) {
                        if (installed.metaData.enabled) {
                            onUBlockReady(installed);
                        } else {
                            controller.enable(installed, WebExtensionController.EnableSource.USER)
                                    .accept(this::onUBlockReady, error -> installSignedUBlock(controller));
                        }
                    } else {
                        installSignedUBlock(controller);
                    }
                },
                error -> installSignedUBlock(controller));
    }

    private WebExtension findUBlock(List<WebExtension> extensions) {
        if (extensions == null) {
            return null;
        }
        for (WebExtension extension : extensions) {
            if (extension != null && UBO_ID.equals(extension.id)) {
                return extension;
            }
        }
        return null;
    }

    private void installSignedUBlock(WebExtensionController controller) {
        showNativeStatus("Enabling uBlock Origin…");
        controller.install(UBO_XPI, WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
                .accept(
                        this::onUBlockReady,
                        error -> {
                            String detail = error.getMessage();
                            if (error instanceof WebExtension.InstallException) {
                                WebExtension.InstallException installError =
                                        (WebExtension.InstallException) error;
                                detail = "code " + installError.code;
                            }
                            Toast.makeText(
                                    MainActivity.this,
                                    "uBlock Origin couldn't start (" + detail + ")",
                                    Toast.LENGTH_LONG).show();
                            showNativeStatus("Opening Animex…");
                            session.loadUri(HOME_URL);
                        });
    }

    private void onUBlockReady(WebExtension extension) {
        uBlockOrigin = extension;
        showNativeStatus("Opening Animex…");
        session.loadUri(HOME_URL);
    }

    private void openUBlockDashboard() {
        if (uBlockOrigin == null) {
            Toast.makeText(this, "uBlock Origin isn't ready", Toast.LENGTH_SHORT).show();
            return;
        }

        String options = uBlockOrigin.metaData.optionsPageUrl;
        if (options == null || options.isEmpty()) {
            options = uBlockOrigin.metaData.baseUrl + "dashboard.html";
        }
        session.loadUri(options);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            registerVolumeUpPress();
        }
        return super.dispatchKeyEvent(event);
    }

    private void registerVolumeUpPress() {
        long now = SystemClock.elapsedRealtime();
        if (volumeUpPressCount == 0 || now - firstVolumeUpPressAt > UBO_TRIGGER_WINDOW_MS) {
            volumeUpPressCount = 1;
            firstVolumeUpPressAt = now;
            return;
        }

        volumeUpPressCount++;
        if (volumeUpPressCount >= UBO_TRIGGER_PRESSES) {
            volumeUpPressCount = 0;
            firstVolumeUpPressAt = 0L;
            openUBlockDashboard();
        }
    }

    private void showNativeStatus(String text) {
        if (launchStatus != null) {
            launchStatus.setText(text);
        }
    }

    private void hideLaunchOverlay() {
        if (launchOverlay == null || launchOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        launchOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> launchOverlay.setVisibility(View.GONE))
                .start();
    }

    private void handleBack() {
        if (canGoBack && session != null) {
            session.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) {
            handleBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
        }
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        if (session != null) {
            session.setActive(false);
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
