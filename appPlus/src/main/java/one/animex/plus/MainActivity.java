package one.animex.plus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

    private static GeckoRuntime runtime;

    private GeckoView geckoView;
    private GeckoSession session;
    private WebExtension uBlockOrigin;
    private ProgressBar progress;
    private boolean canGoBack;

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
                if (fullScreen) {
                    hideSystemUi();
                } else {
                    hideSystemUi();
                }
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
        progressParams.gravity = Gravity.TOP;
        root.addView(progress, progressParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setAlpha(0.72f);

        Button home = controlButton("⌂");
        home.setContentDescription("Animex home");
        home.setOnClickListener(v -> session.loadUri(HOME_URL));

        Button reload = controlButton("↻");
        reload.setContentDescription("Reload");
        reload.setOnClickListener(v -> session.reload());

        Button ubo = controlButton("uBO");
        ubo.setContentDescription("Open uBlock Origin dashboard");
        ubo.setOnClickListener(v -> openUBlockDashboard());
        ubo.setOnLongClickListener(v -> {
            showUBlockStatus();
            return true;
        });

        controls.addView(home);
        controls.addView(reload);
        controls.addView(ubo);

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        controlParams.gravity = Gravity.TOP | Gravity.END;
        controlParams.topMargin = dp(8);
        controlParams.rightMargin = dp(8);
        root.addView(controls, controlParams);

        setContentView(root);
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setAllCaps(false);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundColor(0xFF111111);
        return button;
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

    private void openUBlockDashboard() {
        if (uBlockOrigin == null) {
            Toast.makeText(this, "uBlock Origin is still loading", Toast.LENGTH_SHORT).show();
            return;
        }

        String options = uBlockOrigin.metaData.optionsPageUrl;
        if (options == null || options.isEmpty()) {
            options = uBlockOrigin.metaData.baseUrl + "dashboard.html";
        }
        session.loadUri(options);
    }

    private void showUBlockStatus() {
        if (uBlockOrigin == null) {
            Toast.makeText(this, "uBlock Origin not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(
                this,
                "uBO " + uBlockOrigin.metaData.version + " • "
                        + (uBlockOrigin.metaData.enabled ? "enabled" : "disabled"),
                Toast.LENGTH_LONG).show();
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
