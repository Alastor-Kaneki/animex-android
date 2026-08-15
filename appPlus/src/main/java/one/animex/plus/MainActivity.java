package one.animex.plus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://animex.one/";
    private static final String UBO_LOCATION = "resource://android/assets/ublock/";
    private static final String UBO_ID = "uBlock0@raymondhill.net";

    // Hidden hardware shortcut: 3 distinct Volume Up presses inside this window.
    private static final long VOLUME_UP_TRIGGER_WINDOW_MS = 1500L;

    private static GeckoRuntime runtime;

    private GeckoView geckoView;
    private GeckoSession session;
    private WebExtension uBlockOrigin;
    private ProgressBar progress;
    private boolean canGoBack;

    private int volumeUpPressCount;
    private long firstVolumeUpPressAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();
        hideSystemUi();

        if (runtime == null) {
            runtime = GeckoRuntime.create(this);
        }

        session = new GeckoSession();
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                hideSystemUi();
            }

            @Override
            public void onCrash(GeckoSession crashedSession) {
                Toast.makeText(MainActivity.this,
                        "Gecko content process crashed — reopening Animex",
                        Toast.LENGTH_LONG).show();
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
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
            }

            @Override
            public void onProgressChange(GeckoSession session, int value) {
                progress.setProgress(value);
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                progress.setProgress(100);
                progress.setVisibility(View.GONE);
            }
        });

        session.open(runtime);
        geckoView.setSession(session);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack);
        }

        installUBlockAndLaunch();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.BLACK);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3));
        root.addView(progress, progressParams);

        setContentView(root);
    }

    private void installUBlockAndLaunch() {
        runtime.getWebExtensionController()
                .ensureBuiltIn(UBO_LOCATION, UBO_ID)
                .accept(
                        extension -> {
                            uBlockOrigin = extension;
                            Toast.makeText(
                                    MainActivity.this,
                                    "uBlock Origin " + extension.metaData.version + " active",
                                    Toast.LENGTH_SHORT).show();
                            session.loadUri(HOME_URL);
                        },
                        error -> {
                            Toast.makeText(
                                    MainActivity.this,
                                    "uBlock Origin failed to load: " + error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            session.loadUri(HOME_URL);
                        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            registerVolumeUpPress();
        }

        // Do not consume the event: hardware volume still changes normally.
        return super.dispatchKeyEvent(event);
    }

    private void registerVolumeUpPress() {
        long now = SystemClock.elapsedRealtime();

        if (volumeUpPressCount == 0
                || now - firstVolumeUpPressAt > VOLUME_UP_TRIGGER_WINDOW_MS) {
            volumeUpPressCount = 1;
            firstVolumeUpPressAt = now;
            return;
        }

        volumeUpPressCount++;

        if (volumeUpPressCount >= 3) {
            volumeUpPressCount = 0;
            firstVolumeUpPressAt = 0L;
            openUBlockDashboard();
        }
    }

    private void openUBlockDashboard() {
        if (uBlockOrigin == null || session == null) {
            Toast.makeText(this, "uBlock Origin is still loading", Toast.LENGTH_SHORT).show();
            return;
        }

        String options = uBlockOrigin.metaData.optionsPageUrl;
        if (options == null || options.isEmpty()) {
            options = uBlockOrigin.metaData.baseUrl + "dashboard.html";
        }
        session.loadUri(options);
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
