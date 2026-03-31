package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;

    /**
     * ClawMobile launcher overlay views.
     */
    private View mLauncherOverlay;
    private View mLauncherInstallButton;
    private View mLauncherProgressSection;
    private ProgressBar mLauncherProgressBar;
    private TextView mLauncherStatusText;
    private TextView mLauncherProgressText;
    private TextView mLauncherTerminalTailText;
    private View mLauncherTabSetupButton;
    private View mLauncherTabChannelsButton;
    private View mLauncherTabOperateButton;
    private View mLauncherTabHealthButton;
    private View mLauncherTabSetupIndicator;
    private View mLauncherTabChannelsIndicator;
    private View mLauncherTabOperateIndicator;
    private View mLauncherTabHealthIndicator;
    private TextView mLauncherTabSetupLabel;
    private TextView mLauncherTabChannelsLabel;
    private TextView mLauncherTabOperateLabel;
    private TextView mLauncherTabHealthLabel;
    private TextView mLauncherTabSetupMeta;
    private TextView mLauncherTabChannelsMeta;
    private TextView mLauncherTabOperateMeta;
    private TextView mLauncherTabHealthMeta;
    private View mLauncherSetupHeroPanel;
    private View mLauncherSetupTabContent;
    private View mLauncherChannelsTabContent;
    private View mLauncherOperateTabContent;
    private View mLauncherHealthTabContent;
    private View mLauncherOnboardingSection;
    private RadioGroup mLauncherOnboardingProviderGroup;
    private View mLauncherOnboardingCustomFields;
    private EditText mLauncherOnboardingApiKeyInput;
    private EditText mLauncherOnboardingBaseUrlInput;
    private EditText mLauncherOnboardingModelIdInput;
    private EditText mLauncherOnboardingWorkspaceInput;
    private View mLauncherOnboardingSubmitButton;
    private View mLauncherPairingSection;
    private EditText mLauncherPairingHostInput;
    private EditText mLauncherPairingPortInput;
    private EditText mLauncherPairingCodeInput;
    private EditText mLauncherPairingConnectPortInput;
    private View mLauncherPairingSubmitButton;
    private View mLauncherConnectSubmitButton;
    private View mLauncherChannelPairingSection;
    private EditText mLauncherChannelPairingCodeInput;
    private View mLauncherChannelPairingSubmitButton;
    private View mLauncherRunButton;
    private View mLauncherHealthRefreshButton;
    private View mLauncherOperateStatusDot;
    private TextView mLauncherOperateStatusText;
    private TextView mLauncherOperateSummaryText;
    private TextView mLauncherOperateDetailText;
    private TextView mLauncherOperateGatewayValueText;
    private TextView mLauncherOperateDeviceValueText;
    private View mLauncherHealthRepoDot;
    private TextView mLauncherHealthRepoStateText;
    private TextView mLauncherHealthRepoDetailText;
    private View mLauncherHealthRuntimeDot;
    private TextView mLauncherHealthRuntimeStateText;
    private TextView mLauncherHealthRuntimeDetailText;
    private View mLauncherHealthSetupDot;
    private TextView mLauncherHealthSetupStateText;
    private TextView mLauncherHealthSetupDetailText;
    private View mLauncherHealthAdbDot;
    private TextView mLauncherHealthAdbStateText;
    private TextView mLauncherHealthAdbDetailText;
    private View mLauncherHealthDeviceDot;
    private TextView mLauncherHealthDeviceStateText;
    private TextView mLauncherHealthDeviceDetailText;
    private View mLauncherHealthGatewayDot;
    private TextView mLauncherHealthGatewayStateText;
    private TextView mLauncherHealthGatewayDetailText;
    private TextView mConsoleBadgeText;
    private TextView mConsoleTitleText;
    private TextView mConsoleSubtitleText;
    private TextView mConsolePrimaryValueText;
    private TextView mConsoleSecondaryValueText;
    private TextView mConsoleSurfaceStatusText;
    private TextView mConsoleFooterStatusText;
    private TextView mConsoleFooterDetailText;
    private boolean mIsLauncherVisible = true;
    private boolean mIsInstallRunning = false;
    private boolean mIsOnboardingRunning = false;
    private boolean mIsAdbPairingRunning = false;
    private boolean mIsChannelPairingRunning = false;
    private boolean mIsRuntimeLaunchRunning = false;
    private boolean mIsHealthRefreshRunning = false;
    private boolean mIsClawMobilePreparing = false;
    private boolean mIsClawMobilePrepared = false;
    private boolean mHasClawMobileRuntimeInstalled = false;
    private boolean mPendingInstallAfterPreparation = false;
    private boolean mHasScheduledClawMobilePreparation = false;
    private int mSelectedLauncherTab = CLAWMOBILE_TAB_SETUP;

    /** The terminal session temporarily used to inject the install command. */
    private TerminalSession mInstallSession;
    private boolean mInstallSessionIsDedicated = false;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_REPORT_ID = 7;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String CLAWMOBILE_REPO_ASSET_DIR = "clawmobile_repo";
    private static final String CLAWMOBILE_REPO_DIR_NAME = "ClawMobile";
    private static final String CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH = "installer/termux/install.sh";
    private static final String CLAWMOBILE_ONBOARD_SCRIPT_RELATIVE_PATH = "installer/termux/onboard.sh";
    private static final String CLAWMOBILE_RUN_SCRIPT_RELATIVE_PATH = "installer/termux/run.sh";
    private static final String CLAWMOBILE_ADB_PAIR_SCRIPT_RELATIVE_PATH = "installer/termux/adb-pair.sh";
    private static final String CLAWMOBILE_ADB_CONNECT_SCRIPT_RELATIVE_PATH = "installer/termux/adb-connect.sh";
    private static final String CLAWMOBILE_CHANNEL_PAIR_SCRIPT_RELATIVE_PATH = "installer/termux/pairing.sh";
    private static final String CLAWMOBILE_BUNDLE_VERSION_FILENAME = "__bundle_version.txt";
    private static final String CLAWMOBILE_INSTALL_SESSION_NAME = "ClawMobile Install";
    private static final String CLAWMOBILE_DEFAULT_WORKSPACE = "~/.openclaw/workspace";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_OPENAI = "openai";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_ANTHROPIC = "anthropic";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_GEMINI = "gemini";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_ZAI = "zai";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_CUSTOM = "custom";
    private static final String CLAWMOBILE_ONBOARD_PROVIDER_SKIP = "skip";
    private static final String CLAWMOBILE_ONBOARD_CUSTOM_PROVIDER_ID = "clawmobile-custom";
    private static final String CLAWMOBILE_UI_PREFS = "clawmobile_ui";
    private static final String PREF_PAIR_HOST = "pair_host";
    private static final String PREF_PAIR_PORT = "pair_port";
    private static final String PREF_PAIR_CONNECT_PORT = "pair_connect_port";
    private static final String CLAWMOBILE_PAYLOAD_EXTERNAL_DIR_NAME = "clawmobile-runtime-payload";
    private static final String CLAWMOBILE_PAYLOAD_INTERNAL_DIR_PATH = ".clawmobile/payload";
    private static final String CLAWMOBILE_TERMUX_LAYER_PAYLOAD_FILENAME = "termux-prefix-layer.tar";
    private static final String CLAWMOBILE_ROOTFS_PAYLOAD_FILENAME = "ubuntu-rootfs-harvested.tar";
    private static final String CLAWMOBILE_DEFAULT_ROOTFS_NAME = "ubuntu";
    private static final String CLAWMOBILE_FALLBACK_ROOTFS_NAME = "clawmobile-ubuntu";
    private static final int CLAWMOBILE_TAB_SETUP = 0;
    private static final int CLAWMOBILE_TAB_CHANNELS = 1;
    private static final int CLAWMOBILE_TAB_OPERATE = 2;
    private static final int CLAWMOBILE_TAB_HEALTH = 3;
    private static final int CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT = 40;
    private static final int CLAWMOBILE_RUNTIME_READY_RETRY_LIMIT = 180;
    private static final long CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS = 100L;
    private static final long CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS = 500L;

    private static final String LOG_TAG = "TermuxActivity";

    private static final class ClawMobileHealthItem {
        @NonNull final String state;
        @NonNull final String detail;
        final int colorResId;
        final boolean healthy;

        private ClawMobileHealthItem(@NonNull String state, @NonNull String detail, int colorResId,
                                     boolean healthy) {
            this.state = state;
            this.detail = detail;
            this.colorResId = colorResId;
            this.healthy = healthy;
        }
    }

    private static final class ClawMobileHealthSnapshot {
        @NonNull final ClawMobileHealthItem repo;
        @NonNull final ClawMobileHealthItem runtime;
        @NonNull final ClawMobileHealthItem setup;
        @NonNull final ClawMobileHealthItem adb;
        @NonNull final ClawMobileHealthItem device;
        @NonNull final ClawMobileHealthItem gateway;
        final boolean repoReady;
        final boolean runtimeReady;
        final boolean setupReady;
        final boolean adbReady;
        final boolean deviceLinked;
        final boolean gatewayOnline;
        final boolean browserControlOnline;

        private ClawMobileHealthSnapshot(@NonNull ClawMobileHealthItem repo,
                                         @NonNull ClawMobileHealthItem runtime,
                                         @NonNull ClawMobileHealthItem setup,
                                         @NonNull ClawMobileHealthItem adb,
                                         @NonNull ClawMobileHealthItem device,
                                         @NonNull ClawMobileHealthItem gateway,
                                         boolean repoReady,
                                         boolean runtimeReady,
                                         boolean setupReady,
                                         boolean adbReady,
                                         boolean deviceLinked,
                                         boolean gatewayOnline,
                                         boolean browserControlOnline) {
            this.repo = repo;
            this.runtime = runtime;
            this.setup = setup;
            this.adb = adb;
            this.device = device;
            this.gateway = gateway;
            this.repoReady = repoReady;
            this.runtimeReady = runtimeReady;
            this.setupReady = setupReady;
            this.adbReady = adbReady;
            this.deviceLinked = deviceLinked;
            this.gatewayOnline = gatewayOnline;
            this.browserControlOnline = browserControlOnline;
        }
    }

    private static final class ClawMobileAdbProbe {
        @NonNull final String detail;
        final boolean linked;
        final boolean available;
        final int colorResId;

        private ClawMobileAdbProbe(@NonNull String detail, boolean linked, boolean available, int colorResId) {
            this.detail = detail;
            this.linked = linked;
            this.available = available;
            this.colorResId = colorResId;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        setLauncherOverlayView();
        setConsoleChromeView();

        registerForContextMenu(mTerminalView);


        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                        scheduleAutomaticClawMobilePreparation();
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
            scheduleAutomaticClawMobilePreparation();
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            showLauncherOverlay();
            selectLauncherTab(CLAWMOBILE_TAB_SETUP);
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    private void setLauncherOverlayView() {
        mLauncherOverlay = findViewById(R.id.launcher_overlay);
        mLauncherInstallButton = findViewById(R.id.btn_clawmobile_install);
        mLauncherProgressSection = findViewById(R.id.launcher_progress_section);
        mLauncherProgressBar = findViewById(R.id.launcher_progress_bar);
        mLauncherStatusText = findViewById(R.id.launcher_status_text);
        mLauncherProgressText = findViewById(R.id.launcher_progress_text);
        mLauncherTerminalTailText = findViewById(R.id.launcher_terminal_tail_text);
        mLauncherTabSetupButton = findViewById(R.id.btn_clawmobile_tab_setup);
        mLauncherTabChannelsButton = findViewById(R.id.btn_clawmobile_tab_channels);
        mLauncherTabOperateButton = findViewById(R.id.btn_clawmobile_tab_operate);
        mLauncherTabHealthButton = findViewById(R.id.btn_clawmobile_tab_health);
        mLauncherTabSetupIndicator = findViewById(R.id.launcher_tab_indicator_setup);
        mLauncherTabChannelsIndicator = findViewById(R.id.launcher_tab_indicator_channels);
        mLauncherTabOperateIndicator = findViewById(R.id.launcher_tab_indicator_operate);
        mLauncherTabHealthIndicator = findViewById(R.id.launcher_tab_indicator_health);
        mLauncherTabSetupLabel = findViewById(R.id.launcher_tab_label_setup);
        mLauncherTabChannelsLabel = findViewById(R.id.launcher_tab_label_channels);
        mLauncherTabOperateLabel = findViewById(R.id.launcher_tab_label_operate);
        mLauncherTabHealthLabel = findViewById(R.id.launcher_tab_label_health);
        mLauncherTabSetupMeta = findViewById(R.id.launcher_tab_meta_setup);
        mLauncherTabChannelsMeta = findViewById(R.id.launcher_tab_meta_channels);
        mLauncherTabOperateMeta = findViewById(R.id.launcher_tab_meta_operate);
        mLauncherTabHealthMeta = findViewById(R.id.launcher_tab_meta_health);
        mLauncherSetupHeroPanel = findViewById(R.id.launcher_setup_hero_panel);
        mLauncherSetupTabContent = findViewById(R.id.launcher_setup_tab_content);
        mLauncherChannelsTabContent = findViewById(R.id.launcher_channels_tab_content);
        mLauncherOperateTabContent = findViewById(R.id.launcher_operate_tab_content);
        mLauncherHealthTabContent = findViewById(R.id.launcher_health_tab_content);
        mLauncherOnboardingSection = findViewById(R.id.clawmobile_onboarding_section);
        mLauncherOnboardingProviderGroup = findViewById(R.id.clawmobile_onboarding_provider_group);
        mLauncherOnboardingCustomFields = findViewById(R.id.clawmobile_onboarding_custom_fields);
        mLauncherOnboardingApiKeyInput = findViewById(R.id.clawmobile_onboarding_api_key);
        mLauncherOnboardingBaseUrlInput = findViewById(R.id.clawmobile_onboarding_base_url);
        mLauncherOnboardingModelIdInput = findViewById(R.id.clawmobile_onboarding_model_id);
        mLauncherOnboardingWorkspaceInput = findViewById(R.id.clawmobile_onboarding_workspace);
        mLauncherOnboardingSubmitButton = findViewById(R.id.btn_clawmobile_onboarding_submit);
        mLauncherPairingSection = findViewById(R.id.clawmobile_pairing_section);
        mLauncherPairingHostInput = findViewById(R.id.clawmobile_pairing_host);
        mLauncherPairingPortInput = findViewById(R.id.clawmobile_pairing_port);
        mLauncherPairingCodeInput = findViewById(R.id.clawmobile_pairing_code);
        mLauncherPairingConnectPortInput = findViewById(R.id.clawmobile_pairing_connect_port);
        mLauncherPairingSubmitButton = findViewById(R.id.btn_clawmobile_pair_device);
        mLauncherConnectSubmitButton = findViewById(R.id.btn_clawmobile_connect_device);
        mLauncherChannelPairingSection = findViewById(R.id.clawmobile_channel_pairing_section);
        mLauncherChannelPairingCodeInput = findViewById(R.id.clawmobile_channel_pairing_code);
        mLauncherChannelPairingSubmitButton = findViewById(R.id.btn_clawmobile_channel_pairing_submit);
        mLauncherRunButton = findViewById(R.id.btn_clawmobile_run);
        mLauncherHealthRefreshButton = findViewById(R.id.btn_clawmobile_refresh_health);
        mLauncherOperateStatusDot = findViewById(R.id.launcher_operate_status_dot);
        mLauncherOperateStatusText = findViewById(R.id.launcher_operate_status_text);
        mLauncherOperateSummaryText = findViewById(R.id.launcher_operate_summary_text);
        mLauncherOperateDetailText = findViewById(R.id.launcher_operate_detail_text);
        mLauncherOperateGatewayValueText = findViewById(R.id.launcher_operate_gateway_value);
        mLauncherOperateDeviceValueText = findViewById(R.id.launcher_operate_device_value);
        mLauncherHealthRepoDot = findViewById(R.id.launcher_health_repo_dot);
        mLauncherHealthRepoStateText = findViewById(R.id.launcher_health_repo_state);
        mLauncherHealthRepoDetailText = findViewById(R.id.launcher_health_repo_detail);
        mLauncherHealthRuntimeDot = findViewById(R.id.launcher_health_runtime_dot);
        mLauncherHealthRuntimeStateText = findViewById(R.id.launcher_health_runtime_state);
        mLauncherHealthRuntimeDetailText = findViewById(R.id.launcher_health_runtime_detail);
        mLauncherHealthSetupDot = findViewById(R.id.launcher_health_setup_dot);
        mLauncherHealthSetupStateText = findViewById(R.id.launcher_health_setup_state);
        mLauncherHealthSetupDetailText = findViewById(R.id.launcher_health_setup_detail);
        mLauncherHealthAdbDot = findViewById(R.id.launcher_health_adb_dot);
        mLauncherHealthAdbStateText = findViewById(R.id.launcher_health_adb_state);
        mLauncherHealthAdbDetailText = findViewById(R.id.launcher_health_adb_detail);
        mLauncherHealthDeviceDot = findViewById(R.id.launcher_health_device_dot);
        mLauncherHealthDeviceStateText = findViewById(R.id.launcher_health_device_state);
        mLauncherHealthDeviceDetailText = findViewById(R.id.launcher_health_device_detail);
        mLauncherHealthGatewayDot = findViewById(R.id.launcher_health_gateway_dot);
        mLauncherHealthGatewayStateText = findViewById(R.id.launcher_health_gateway_state);
        mLauncherHealthGatewayDetailText = findViewById(R.id.launcher_health_gateway_detail);

        // Install button click
        mLauncherInstallButton.setOnClickListener(v -> handlePrimaryLauncherAction());
        mLauncherOnboardingSubmitButton.setOnClickListener(v -> submitOpenClawSetupFromLauncher());
        mLauncherPairingSubmitButton.setOnClickListener(v -> submitAdbPairingFromLauncher());
        mLauncherConnectSubmitButton.setOnClickListener(v -> submitAdbConnectFromLauncher());
        mLauncherChannelPairingSubmitButton.setOnClickListener(v -> submitTelegramPairingFromLauncher());
        mLauncherRunButton.setOnClickListener(v -> startClawMobileRuntime());
        mLauncherHealthRefreshButton.setOnClickListener(v -> refreshLauncherHealth(true));
        mLauncherTabSetupButton.setOnClickListener(v -> selectLauncherTab(CLAWMOBILE_TAB_SETUP));
        mLauncherTabChannelsButton.setOnClickListener(v -> selectLauncherTab(CLAWMOBILE_TAB_CHANNELS));
        mLauncherTabOperateButton.setOnClickListener(v -> selectLauncherTab(CLAWMOBILE_TAB_OPERATE));
        mLauncherTabHealthButton.setOnClickListener(v -> selectLauncherTab(CLAWMOBILE_TAB_HEALTH));

        if (mLauncherOnboardingWorkspaceInput != null
            && mLauncherOnboardingWorkspaceInput.getText().toString().trim().isEmpty()) {
            mLauncherOnboardingWorkspaceInput.setText(CLAWMOBILE_DEFAULT_WORKSPACE);
        }
        loadPairingDefaults();

        if (mLauncherOnboardingProviderGroup != null) {
            mLauncherOnboardingProviderGroup.setOnCheckedChangeListener(
                (group, checkedId) -> updateOnboardingInputVisibility());
        }
        updateOnboardingInputVisibility();
        setOnboardingSectionVisible(false);
        setPairingSectionVisible(false);
        setChannelPairingSectionVisible(false);
        selectLauncherTab(CLAWMOBILE_TAB_SETUP);
        setOperateUiEnabled(false);
        setHealthUiEnabled(true);

        // Switch to terminal button
        findViewById(R.id.btn_switch_to_terminal).setOnClickListener(v -> switchToTerminalView());

        // Start with launcher visible
        showLauncherOverlay();
        mLauncherInstallButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_preparing);
        mLauncherStatusText.setText(R.string.clawmobile_preparing);
        mLauncherProgressSection.setVisibility(View.VISIBLE);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherProgressText.setText("Waiting for ClawMobile shell session...");
        if (mLauncherTerminalTailText != null) {
            mLauncherTerminalTailText.setText(R.string.clawmobile_terminal_tail_placeholder);
        }
        refreshLauncherHealth(false);
        updatePrimaryLauncherButtonState();
    }

    private void selectLauncherTab(int tab) {
        mSelectedLauncherTab = tab;

        if (mLauncherSetupHeroPanel != null) {
            mLauncherSetupHeroPanel.setVisibility(tab == CLAWMOBILE_TAB_SETUP ? View.VISIBLE : View.GONE);
        }
        if (mLauncherSetupTabContent != null) {
            mLauncherSetupTabContent.setVisibility(tab == CLAWMOBILE_TAB_SETUP ? View.VISIBLE : View.GONE);
        }
        if (mLauncherChannelsTabContent != null) {
            mLauncherChannelsTabContent.setVisibility(tab == CLAWMOBILE_TAB_CHANNELS ? View.VISIBLE : View.GONE);
        }
        if (mLauncherOperateTabContent != null) {
            mLauncherOperateTabContent.setVisibility(tab == CLAWMOBILE_TAB_OPERATE ? View.VISIBLE : View.GONE);
        }
        if (mLauncherHealthTabContent != null) {
            mLauncherHealthTabContent.setVisibility(tab == CLAWMOBILE_TAB_HEALTH ? View.VISIBLE : View.GONE);
        }

        updateLauncherTabButton(mLauncherTabSetupButton, mLauncherTabSetupLabel, mLauncherTabSetupMeta,
            mLauncherTabSetupIndicator, tab == CLAWMOBILE_TAB_SETUP);
        updateLauncherTabButton(mLauncherTabChannelsButton, mLauncherTabChannelsLabel, mLauncherTabChannelsMeta,
            mLauncherTabChannelsIndicator, tab == CLAWMOBILE_TAB_CHANNELS);
        updateLauncherTabButton(mLauncherTabOperateButton, mLauncherTabOperateLabel, mLauncherTabOperateMeta,
            mLauncherTabOperateIndicator, tab == CLAWMOBILE_TAB_OPERATE);
        updateLauncherTabButton(mLauncherTabHealthButton, mLauncherTabHealthLabel, mLauncherTabHealthMeta,
            mLauncherTabHealthIndicator, tab == CLAWMOBILE_TAB_HEALTH);

        if (tab == CLAWMOBILE_TAB_OPERATE || tab == CLAWMOBILE_TAB_HEALTH) {
            refreshLauncherHealth(false);
        }
    }

    private void updateLauncherTabButton(@Nullable View button, @Nullable TextView labelView,
                                         @Nullable TextView metaView, @Nullable View indicatorView,
                                         boolean selected) {
        if (button == null) return;

        button.setBackgroundResource(selected
            ? R.drawable.clawmobile_bottom_tab_active
            : R.drawable.clawmobile_bottom_tab_idle);
        button.setAlpha(selected ? 1f : 0.96f);
        button.setTranslationY(selected ? -6f : 0f);

        if (labelView != null) {
            labelView.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.claw_text_primary : R.color.claw_text_secondary));
        }
        if (metaView != null) {
            metaView.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.claw_amber : R.color.claw_text_muted));
            metaView.setAlpha(selected ? 1f : 0.72f);
        }
        if (indicatorView != null) {
            indicatorView.setAlpha(selected ? 1f : 0.18f);
            indicatorView.setScaleX(selected ? 1f : 0.45f);
        }
    }

    private void setOnboardingSectionVisible(boolean visible) {
        if (mLauncherOnboardingSection != null) {
            mLauncherOnboardingSection.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setPairingSectionVisible(boolean visible) {
        if (mLauncherPairingSection != null) {
            mLauncherPairingSection.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setChannelPairingSectionVisible(boolean visible) {
        if (mLauncherChannelPairingSection != null) {
            mLauncherChannelPairingSection.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @NonNull
    private String getSelectedOnboardingProvider() {
        if (mLauncherOnboardingProviderGroup == null) {
            return CLAWMOBILE_ONBOARD_PROVIDER_OPENAI;
        }

        int checkedId = mLauncherOnboardingProviderGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.clawmobile_onboarding_provider_anthropic) {
            return CLAWMOBILE_ONBOARD_PROVIDER_ANTHROPIC;
        }
        if (checkedId == R.id.clawmobile_onboarding_provider_gemini) {
            return CLAWMOBILE_ONBOARD_PROVIDER_GEMINI;
        }
        if (checkedId == R.id.clawmobile_onboarding_provider_zai) {
            return CLAWMOBILE_ONBOARD_PROVIDER_ZAI;
        }
        if (checkedId == R.id.clawmobile_onboarding_provider_custom) {
            return CLAWMOBILE_ONBOARD_PROVIDER_CUSTOM;
        }
        if (checkedId == R.id.clawmobile_onboarding_provider_skip) {
            return CLAWMOBILE_ONBOARD_PROVIDER_SKIP;
        }

        return CLAWMOBILE_ONBOARD_PROVIDER_OPENAI;
    }

    private void updateOnboardingInputVisibility() {
        String provider = getSelectedOnboardingProvider();
        boolean requiresApiKey = !CLAWMOBILE_ONBOARD_PROVIDER_SKIP.equals(provider);
        boolean requiresCustomFields = CLAWMOBILE_ONBOARD_PROVIDER_CUSTOM.equals(provider);

        if (mLauncherOnboardingApiKeyInput != null) {
            mLauncherOnboardingApiKeyInput.setVisibility(requiresApiKey ? View.VISIBLE : View.GONE);
        }
        if (mLauncherOnboardingCustomFields != null) {
            mLauncherOnboardingCustomFields.setVisibility(requiresCustomFields ? View.VISIBLE : View.GONE);
        }
    }

    private void setOnboardingUiEnabled(boolean enabled) {
        if (mLauncherInstallButton != null) {
            mLauncherInstallButton.setEnabled(enabled && !mIsInstallRunning && !mIsClawMobilePreparing
                && !mIsOnboardingRunning && !mIsAdbPairingRunning && !mIsChannelPairingRunning
                && !mIsRuntimeLaunchRunning);
        }
        if (mLauncherOnboardingProviderGroup != null) {
            mLauncherOnboardingProviderGroup.setEnabled(enabled);
            for (int i = 0; i < mLauncherOnboardingProviderGroup.getChildCount(); i++) {
                mLauncherOnboardingProviderGroup.getChildAt(i).setEnabled(enabled);
            }
        }
        if (mLauncherOnboardingApiKeyInput != null) {
            mLauncherOnboardingApiKeyInput.setEnabled(enabled);
        }
        if (mLauncherOnboardingBaseUrlInput != null) {
            mLauncherOnboardingBaseUrlInput.setEnabled(enabled);
        }
        if (mLauncherOnboardingModelIdInput != null) {
            mLauncherOnboardingModelIdInput.setEnabled(enabled);
        }
        if (mLauncherOnboardingWorkspaceInput != null) {
            mLauncherOnboardingWorkspaceInput.setEnabled(enabled);
        }
        if (mLauncherOnboardingSubmitButton != null) {
            mLauncherOnboardingSubmitButton.setEnabled(enabled);
        }
    }

    private void setPairingUiEnabled(boolean enabled) {
        if (mLauncherPairingHostInput != null) {
            mLauncherPairingHostInput.setEnabled(enabled);
        }
        if (mLauncherPairingPortInput != null) {
            mLauncherPairingPortInput.setEnabled(enabled);
        }
        if (mLauncherPairingCodeInput != null) {
            mLauncherPairingCodeInput.setEnabled(enabled);
        }
        if (mLauncherPairingConnectPortInput != null) {
            mLauncherPairingConnectPortInput.setEnabled(enabled);
        }
        if (mLauncherPairingSubmitButton != null) {
            mLauncherPairingSubmitButton.setEnabled(enabled);
        }
        if (mLauncherConnectSubmitButton != null) {
            mLauncherConnectSubmitButton.setEnabled(enabled);
        }
    }

    private void setChannelPairingUiEnabled(boolean enabled) {
        if (mLauncherChannelPairingCodeInput != null) {
            mLauncherChannelPairingCodeInput.setEnabled(enabled);
        }
        if (mLauncherChannelPairingSubmitButton != null) {
            mLauncherChannelPairingSubmitButton.setEnabled(enabled);
        }
    }

    private void setOperateUiEnabled(boolean enabled) {
        if (mLauncherRunButton != null) {
            mLauncherRunButton.setEnabled(enabled && !mIsInstallRunning && !mIsOnboardingRunning
                && !mIsAdbPairingRunning && !mIsChannelPairingRunning
                && !mIsRuntimeLaunchRunning && mHasClawMobileRuntimeInstalled);
        }
    }

    private void setHealthUiEnabled(boolean enabled) {
        if (mLauncherHealthRefreshButton != null) {
            mLauncherHealthRefreshButton.setEnabled(enabled && !mIsHealthRefreshRunning);
        }
    }

    private void loadPairingDefaults() {
        android.content.SharedPreferences prefs = getSharedPreferences(CLAWMOBILE_UI_PREFS, MODE_PRIVATE);
        if (mLauncherPairingHostInput != null && mLauncherPairingHostInput.getText().toString().trim().isEmpty()) {
            mLauncherPairingHostInput.setText(prefs.getString(PREF_PAIR_HOST, "127.0.0.1"));
        }
        if (mLauncherPairingPortInput != null && mLauncherPairingPortInput.getText().toString().trim().isEmpty()) {
            mLauncherPairingPortInput.setText(prefs.getString(PREF_PAIR_PORT, ""));
        }
        if (mLauncherPairingConnectPortInput != null && mLauncherPairingConnectPortInput.getText().toString().trim().isEmpty()) {
            mLauncherPairingConnectPortInput.setText(prefs.getString(PREF_PAIR_CONNECT_PORT, ""));
        }
    }

    private void setConsoleChromeView() {
        mConsoleBadgeText = findViewById(R.id.clawmobile_console_badge);
        mConsoleTitleText = findViewById(R.id.clawmobile_console_title);
        mConsoleSubtitleText = findViewById(R.id.clawmobile_console_subtitle);
        mConsolePrimaryValueText = findViewById(R.id.clawmobile_console_primary_value);
        mConsoleSecondaryValueText = findViewById(R.id.clawmobile_console_secondary_value);
        mConsoleSurfaceStatusText = findViewById(R.id.clawmobile_console_surface_status);
        mConsoleFooterStatusText = findViewById(R.id.clawmobile_console_footer_status);
        mConsoleFooterDetailText = findViewById(R.id.clawmobile_console_footer_detail);

        View deckButton = findViewById(R.id.btn_console_deck);
        deckButton.setOnClickListener(v -> switchToLauncherView());

        View sessionsButton = findViewById(R.id.btn_console_sessions);
        sessionsButton.setOnClickListener(v -> getDrawer().openDrawer(Gravity.LEFT));

        updateConsoleIdleState();
    }

    private void updateConsoleChrome(@NonNull String badge, @NonNull String title, @NonNull String subtitle,
                                     @NonNull String primaryValue, @NonNull String secondaryValue,
                                     @NonNull String footerStatus, @NonNull String footerDetail,
                                     @NonNull String surfaceStatus) {
        if (mConsoleBadgeText != null) mConsoleBadgeText.setText(badge);
        if (mConsoleTitleText != null) mConsoleTitleText.setText(title);
        if (mConsoleSubtitleText != null) mConsoleSubtitleText.setText(subtitle);
        if (mConsolePrimaryValueText != null) mConsolePrimaryValueText.setText(primaryValue);
        if (mConsoleSecondaryValueText != null) mConsoleSecondaryValueText.setText(secondaryValue);
        if (mConsoleFooterStatusText != null) mConsoleFooterStatusText.setText(footerStatus);
        if (mConsoleFooterDetailText != null) mConsoleFooterDetailText.setText(footerDetail);
        if (mConsoleSurfaceStatusText != null) mConsoleSurfaceStatusText.setText(surfaceStatus);
    }

    private void updateConsoleIdleState() {
        updateConsoleChrome(
            getString(R.string.clawmobile_console_header_badge),
            getString(R.string.clawmobile_console_header_title),
            getString(R.string.clawmobile_console_header_subtitle),
            getString(R.string.clawmobile_console_phase_idle),
            getString(R.string.clawmobile_console_context_idle),
            getString(R.string.clawmobile_console_footer_status),
            getString(R.string.clawmobile_console_footer_detail),
            getString(R.string.clawmobile_console_surface_status)
        );
    }

    private void updateConsolePreparingState(@NonNull String detail) {
        updateConsoleChrome(
            "PREP",
            "Staging local command deck",
            "ClawMobile is copying the bundled repo into place before the real shell takes over.",
            "Preparing repo",
            "Asset deployment",
            "Preparing runtime",
            detail,
            "Repo deployment in progress"
        );
    }

    private void updateConsoleInstallState(@NonNull String footerDetail,
                                           @NonNull String primaryValue,
                                           @NonNull String secondaryValue,
                                           @NonNull String surfaceStatus) {
        updateConsoleChrome(
            "INSTALL",
            "Installing runtime in the live shell",
            "The terminal below is the real foreground session. ClawMobile is only surfacing phase and context around it.",
            primaryValue,
            secondaryValue,
            "Installer active",
            footerDetail,
            surfaceStatus
        );
    }

    private void updateConsoleOnboardState(@NonNull String footerDetail, @NonNull String surfaceStatus) {
        updateConsoleChrome(
            "ONBOARD",
            "OpenClaw setup apply",
            "Install is complete. The launcher panel now drives a non-interactive OpenClaw setup run in the live shell.",
            "Setup apply",
            "Launcher / OpenClaw",
            "Setup apply active",
            footerDetail,
            surfaceStatus
        );
    }

    private void updateConsolePairState(@NonNull String footerDetail, @NonNull String surfaceStatus) {
        updateConsoleChrome(
            "ADB",
            "Wireless device link",
            "ClawMobile is pairing and connecting local wireless ADB in the live shell using the launcher inputs.",
            "Device link",
            "ADB / Wireless",
            "Link flow active",
            footerDetail,
            surfaceStatus
        );
    }

    private void updateConsoleChannelState(@NonNull String footerDetail, @NonNull String surfaceStatus) {
        updateConsoleChrome(
            "CHANNELS",
            "Chat access pairing",
            "ClawMobile is approving OpenClaw chat access from the launcher so Telegram pairing does not require raw shell commands.",
            "Telegram pair",
            "OpenClaw / Channels",
            "Channel pairing active",
            footerDetail,
            surfaceStatus
        );
    }

    private void updateConsoleRuntimeState(@NonNull String footerDetail, @NonNull String surfaceStatus) {
        updateConsoleChrome(
            "RUN",
            "OpenClaw daily runtime",
            "The launcher is starting or supervising the daily OpenClaw gateway path while the shell stays available underneath.",
            "Gateway launch",
            "Ubuntu / OpenClaw",
            "Runtime start active",
            footerDetail,
            surfaceStatus
        );
    }

    private void updateConsoleReadyState(@NonNull String footerDetail) {
        updateConsoleChrome(
            "READY",
            "Runtime online",
            "ClawMobile is installed. Use this shell for onboarding, gateway startup, and any direct runtime checks.",
            "Runtime ready",
            "Ubuntu / OpenClaw",
            "Shell is ready",
            footerDetail,
            "Interactive shell available"
        );
    }

    private void updateConsoleFailureState(@NonNull String footerDetail) {
        updateConsoleChrome(
            "FAULT",
            "Terminal action needs attention",
            "The shell is still available, but the last automated step failed. Inspect the transcript below and retry when ready.",
            "Attention required",
            "Foreground terminal",
            "Last automation failed",
            footerDetail,
            "Shell remains interactive"
        );
    }

    private void showLauncherOverlay() {
        mIsLauncherVisible = true;
        mLauncherOverlay.setVisibility(View.VISIBLE);
        mTerminalView.clearFocus();
        mLauncherOverlay.requestFocus();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.hideSoftKeyboardForLauncher();
    }

    /** Switch to terminal view (hide launcher overlay). */
    public void switchToTerminalView() {
        mIsLauncherVisible = false;
        mLauncherOverlay.setVisibility(View.GONE);

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient.focusTerminalAndShowSoftKeyboard();
        } else {
            mTerminalView.requestFocus();
        }
    }

    /** Switch back to launcher view. */
    public void switchToLauncherView() {
        showLauncherOverlay();
    }

    public boolean isLauncherVisible() {
        return mIsLauncherVisible;
    }

    private void handlePrimaryLauncherAction() {
        if (mIsClawMobilePreparing || mIsInstallRunning || mIsOnboardingRunning
            || mIsAdbPairingRunning || mIsChannelPairingRunning || mIsRuntimeLaunchRunning) {
            return;
        }

        if (!mIsClawMobilePrepared || !mHasClawMobileRuntimeInstalled) {
            startClawMobileInstall();
            return;
        }

        if (!isClawMobileSetupReadyOnDisk()) {
            selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
            setOnboardingSectionVisible(true);
            setPairingSectionVisible(true);
            setChannelPairingSectionVisible(true);
            setOnboardingUiEnabled(true);
            setPairingUiEnabled(true);
            setChannelPairingUiEnabled(true);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherStatusText.setText(R.string.clawmobile_install_complete);
            mLauncherProgressText.setText("OpenClaw setup is required before daily runtime can start.");
            updateLauncherTerminalTail(null, "OpenClaw setup is required before daily runtime can start.");
            switchToLauncherView();
            return;
        }

        if (isLocalPortOpen(18789)) {
            selectLauncherTab(CLAWMOBILE_TAB_OPERATE);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherStatusText.setText(R.string.clawmobile_runtime_running);
            mLauncherProgressText.setText("OpenClaw is already online. Opening daily controls.");
            updateLauncherTerminalTail(null, "OpenClaw is already online. Opening daily controls.");
            switchToLauncherView();
            refreshLauncherHealth(false);
            return;
        }

        startClawMobileRuntime();
    }

    private void updatePrimaryLauncherButtonState() {
        if (!(mLauncherInstallButton instanceof MaterialButton)) {
            return;
        }

        MaterialButton button = (MaterialButton) mLauncherInstallButton;
        if (mIsClawMobilePreparing || mIsInstallRunning || mIsOnboardingRunning
            || mIsAdbPairingRunning || mIsChannelPairingRunning || mIsRuntimeLaunchRunning) {
            return;
        }

        if (!mIsClawMobilePrepared || !mHasClawMobileRuntimeInstalled) {
            button.setText(R.string.clawmobile_install_start);
            return;
        }

        if (!isClawMobileSetupReadyOnDisk()) {
            button.setText(R.string.clawmobile_onboard_start);
            return;
        }

        if (isLocalPortOpen(18789)) {
            button.setText(R.string.clawmobile_open_operate);
            return;
        }

        button.setText(R.string.clawmobile_run_start);
    }

    private void startClawMobileInstall() {
        if (mIsClawMobilePreparing) {
            mPendingInstallAfterPreparation = true;
            mLauncherStatusText.setText(R.string.clawmobile_preparing);
            mLauncherProgressText.setText("Finishing bundled repo deployment before starting install.sh...");
            updateConsolePreparingState("Finishing bundled repo deployment before starting install.sh...");
            return;
        }

        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning || mIsChannelPairingRunning) {
            return;
        }

        if (mTermuxService == null) {
            showToast("Service not ready, please wait...", false);
            return;
        }

        if (!mIsClawMobilePrepared) {
            mPendingInstallAfterPreparation = true;
            startAutomaticClawMobilePreparation();
            return;
        }

        if (mHasClawMobileRuntimeInstalled) {
            selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
            setOnboardingSectionVisible(true);
            setPairingSectionVisible(true);
            setChannelPairingSectionVisible(true);
            setOnboardingUiEnabled(true);
            setPairingUiEnabled(true);
            setChannelPairingUiEnabled(true);
            setOperateUiEnabled(true);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherStatusText.setText(R.string.clawmobile_install_complete);
            mLauncherProgressText.setText("Runtime already installed. Review the Channels tab below.");
            updateConsoleReadyState("Runtime already detected on disk. Review the Channels tab in the launcher.");
            refreshLauncherHealth(false);
            return;
        }

        mIsInstallRunning = true;
        mPendingInstallAfterPreparation = false;

        mLauncherInstallButton.setEnabled(false);
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_installing);
        mLauncherStatusText.setText(R.string.clawmobile_installing);
        mLauncherProgressSection.setVisibility(View.VISIBLE);
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(5);
        mLauncherProgressText.setText("Preparing foreground install terminal...");
        updateConsoleInstallState("Preparing foreground install terminal...", "Install shell", "Upstream install.sh", "Waiting for prompt");
        selectLauncherTab(CLAWMOBILE_TAB_SETUP);
        launchInstallScript();
    }

    private void submitOpenClawSetupFromLauncher() {
        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning
            || mIsChannelPairingRunning || !mHasClawMobileRuntimeInstalled) {
            return;
        }

        List<String> onboardArgs = collectOnboardingArgsFromLauncher();
        if (onboardArgs == null) {
            return;
        }

        startClawMobileOnboarding(onboardArgs, null);
    }

    private void submitAdbPairingFromLauncher() {
        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning
            || mIsChannelPairingRunning || !mHasClawMobileRuntimeInstalled) {
            return;
        }

        String host = mLauncherPairingHostInput == null ? "127.0.0.1"
            : mLauncherPairingHostInput.getText().toString().trim();
        String pairPort = mLauncherPairingPortInput == null ? ""
            : mLauncherPairingPortInput.getText().toString().trim();
        String pairCode = mLauncherPairingCodeInput == null ? ""
            : mLauncherPairingCodeInput.getText().toString().trim().toUpperCase();
        String connectPort = mLauncherPairingConnectPortInput == null ? ""
            : mLauncherPairingConnectPortInput.getText().toString().trim();

        if (host.isEmpty()) host = "127.0.0.1";
        if (pairPort.isEmpty()) {
            showToast("Pairing port is required", false);
            return;
        }
        if (pairCode.isEmpty()) {
            showToast("Pairing code is required", false);
            return;
        }
        if (connectPort.isEmpty()) {
            showToast("ADB connect port is required", false);
            return;
        }

        getSharedPreferences(CLAWMOBILE_UI_PREFS, MODE_PRIVATE)
            .edit()
            .putString(PREF_PAIR_HOST, host)
            .putString(PREF_PAIR_PORT, pairPort)
            .putString(PREF_PAIR_CONNECT_PORT, connectPort)
            .apply();

        if (mLauncherPairingCodeInput != null) {
            mLauncherPairingCodeInput.setText(pairCode);
        }

        startAdbPairing(host, pairPort, pairCode);
    }

    private void submitAdbConnectFromLauncher() {
        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning
            || mIsChannelPairingRunning || !mHasClawMobileRuntimeInstalled) {
            return;
        }

        String host = mLauncherPairingHostInput == null ? "127.0.0.1"
            : mLauncherPairingHostInput.getText().toString().trim();
        String connectPort = mLauncherPairingConnectPortInput == null ? ""
            : mLauncherPairingConnectPortInput.getText().toString().trim();

        if (host.isEmpty()) host = "127.0.0.1";
        if (connectPort.isEmpty()) {
            showToast("ADB connect port is required", false);
            return;
        }

        getSharedPreferences(CLAWMOBILE_UI_PREFS, MODE_PRIVATE)
            .edit()
            .putString(PREF_PAIR_HOST, host)
            .putString(PREF_PAIR_CONNECT_PORT, connectPort)
            .apply();

        startAdbConnect(host, connectPort);
    }

    private void submitTelegramPairingFromLauncher() {
        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning
            || mIsChannelPairingRunning || !mHasClawMobileRuntimeInstalled) {
            return;
        }

        String pairCode = mLauncherChannelPairingCodeInput == null ? ""
            : mLauncherChannelPairingCodeInput.getText().toString().trim().toUpperCase();
        if (pairCode.isEmpty()) {
            showToast("Telegram pairing code is required", false);
            return;
        }

        if (mLauncherChannelPairingCodeInput != null) {
            mLauncherChannelPairingCodeInput.setText(pairCode);
        }

        startTelegramPairing(pairCode);
    }

    private void startClawMobileRuntime() {
        if (mIsInstallRunning || mIsOnboardingRunning || mIsAdbPairingRunning
            || mIsChannelPairingRunning
            || mIsRuntimeLaunchRunning || !mHasClawMobileRuntimeInstalled) {
            return;
        }

        TerminalSession session = createFreshInstallSession();
        if (session == null) {
            onRuntimeFailed("Failed to create OpenClaw runtime session");
            return;
        }

        mIsRuntimeLaunchRunning = true;
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        setOperateUiEnabled(false);
        setHealthUiEnabled(false);
        ((MaterialButton) mLauncherRunButton).setText(R.string.clawmobile_runtime_starting);
        mLauncherProgressSection.setVisibility(View.VISIBLE);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
        mLauncherProgressText.setText("Starting OpenClaw gateway inside Ubuntu...");
        updateLauncherTerminalTail(null, "Waiting for shell prompt before launching OpenClaw gateway...");
        updateConsoleRuntimeState("Preparing OpenClaw runtime command...", "Waiting for prompt");
        showLauncherOverlay();
        selectLauncherTab(CLAWMOBILE_TAB_OPERATE);

        launchRunCommandWhenReady(session, buildRunScriptCommand(), 0);
    }

    private void refreshLauncherHealth(boolean forceVisibleFeedback) {
        if (mIsHealthRefreshRunning) return;

        mIsHealthRefreshRunning = true;
        setHealthUiEnabled(false);
        if (forceVisibleFeedback && mLauncherProgressSection != null) {
            mLauncherProgressSection.setVisibility(View.VISIBLE);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherStatusText.setText(R.string.clawmobile_ready);
            mLauncherProgressText.setText("Refreshing component health...");
            updateLauncherTerminalTail(null, "Refreshing component health...");
        }

        new Thread(() -> {
            ClawMobileHealthSnapshot snapshot = buildClawMobileHealthSnapshot();
            runOnUiThread(() -> applyClawMobileHealthSnapshot(snapshot, forceVisibleFeedback));
        }, "ClawMobileHealthRefresh").start();
    }

    private File prepareClawMobileRepoFromAssets() throws IOException {
        return prepareClawMobileRepoFromAssets(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, CLAWMOBILE_REPO_DIR_NAME), true);
    }

    private File prepareClawMobileRepoFromAssets(@NonNull File repoDir, boolean seedResolvedHosts) throws IOException {
        String bundledVersion = readAssetText(CLAWMOBILE_REPO_ASSET_DIR + "/" + CLAWMOBILE_BUNDLE_VERSION_FILENAME).trim();
        if (bundledVersion.isEmpty()) {
            throw new IOException("Missing bundled version marker");
        }

        if (shouldRefreshClawMobileRepo(repoDir, bundledVersion)) {
            deleteRecursively(repoDir);
            copyAssetPathToFile(CLAWMOBILE_REPO_ASSET_DIR, repoDir);
        }

        markShellScriptsExecutable(new File(repoDir, "installer"));

        File installScript = new File(repoDir, CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH);
        if (!installScript.isFile()) {
            throw new IOException("Bundled installer not found at " + CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH);
        }

        if (seedResolvedHosts) {
            ClawMobileHostResolver.seedResolvedHosts(this, repoDir);
        }

        return repoDir;
    }

    private boolean shouldRefreshClawMobileRepo(File repoDir, String bundledVersion) throws IOException {
        if (!repoDir.exists()) return true;
        if (!repoDir.isDirectory()) return true;

        File versionFile = new File(repoDir, CLAWMOBILE_BUNDLE_VERSION_FILENAME);
        if (!versionFile.isFile()) return true;

        return !bundledVersion.equals(readFileText(versionFile).trim());
    }

    private boolean isClawMobileRuntimeInstalledOnDisk() {
        File ubuntuRootfsDir = getClawMobileRootfsDir();
        File openclawUsrBin = new File(ubuntuRootfsDir, "usr/bin/openclaw");
        File openclawUsrLocalBin = new File(ubuntuRootfsDir, "usr/local/bin/openclaw");
        return ubuntuRootfsDir.isDirectory() && (openclawUsrBin.isFile() || openclawUsrLocalBin.isFile());
    }

    private boolean isClawMobileSetupReadyOnDisk() {
        return getClawMobileStateDirInRootfs().isDirectory();
    }

    private void copyAssetPathToFile(String assetPath, File targetFile) throws IOException {
        String[] childAssets = getAssets().list(assetPath);
        if (childAssets != null && childAssets.length > 0) {
            if (!targetFile.exists() && !targetFile.mkdirs()) {
                throw new IOException("Failed to create directory " + targetFile.getAbsolutePath());
            }

            for (String childAsset : childAssets) {
                copyAssetPathToFile(assetPath + "/" + childAsset, new File(targetFile, childAsset));
            }

            return;
        }

        copyAssetFile(assetPath, targetFile);
    }

    private void copyAssetFile(String assetPath, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory " + parent.getAbsolutePath());
        }

        try (InputStream is = getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Failed to list directory " + file.getAbsolutePath());
            }

            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!file.delete()) {
            throw new IOException("Failed to delete " + file.getAbsolutePath());
        }
    }

    private void markShellScriptsExecutable(File file) throws IOException {
        if (!file.exists()) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Failed to list directory " + file.getAbsolutePath());
            }

            for (File child : children) {
                markShellScriptsExecutable(child);
            }

            return;
        }

        if (file.getName().endsWith(".sh") && !file.setExecutable(true, false)) {
            throw new IOException("Failed to mark script executable: " + file.getAbsolutePath());
        }
    }

    private String readAssetText(String assetPath) throws IOException {
        try (InputStream is = getAssets().open(assetPath)) {
            return readText(is);
        }
    }

    private String readFileText(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return readText(is);
        }
    }

    private String readText(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) builder.append('\n');
                builder.append(line);
                first = false;
            }
        }

        return builder.toString();
    }

    private void applyBundledInstallScriptOverride(@NonNull File repoDir) throws IOException {
        if (!repoDir.isDirectory()) {
            throw new IOException("ClawMobile repo missing at " + repoDir.getAbsolutePath());
        }

        File installerDir = new File(repoDir, "installer");
        copyAssetPathToFile(CLAWMOBILE_REPO_ASSET_DIR + "/installer", installerDir);
        markShellScriptsExecutable(installerDir);
    }

    private void launchInstallScript() {
        if (mTermuxService == null || mTermuxTerminalSessionActivityClient == null) {
            onInstallFailed("Service disconnected");
            return;
        }

        TerminalSession session = createFreshInstallSession();
        if (session == null) {
            onInstallFailed("Failed to create background install session");
            return;
        }

        showLauncherOverlay();
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(10);
        mLauncherStatusText.setText(R.string.clawmobile_installing);
        updateLauncherTerminalTail(null, "Waiting for the live shell to accept install.sh...");
        updateConsoleInstallState("Preparing upstream installer shell...", "Install shell", "Foreground terminal", "Waiting for prompt");
        String installMarker = createClawMobileMarker("INSTALL");
        launchInstallCommandWhenReady(session, buildInstallScriptCommand(installMarker), 0, installMarker);
    }

    @Nullable
    private File findClawMobilePayloadDirectory() {
        List<File> candidates = new ArrayList<>();
        File[] externalFilesDirs = getExternalFilesDirs(null);
        if (externalFilesDirs != null) {
            for (File externalFilesDir : externalFilesDirs) {
                File candidate = buildPayloadChildDirectory(externalFilesDir);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        candidates.add(buildPayloadChildDirectory(new File("/sdcard/Android/data/" + getPackageName() + "/files")));
        candidates.add(buildPayloadChildDirectory(new File("/storage/emulated/0/Android/data/" + getPackageName() + "/files")));
        candidates.add(buildPayloadChildDirectory(new File(Environment.getExternalStorageDirectory(),
            "Android/data/" + getPackageName() + "/files")));
        candidates.add(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, CLAWMOBILE_PAYLOAD_INTERNAL_DIR_PATH));

        for (File candidate : candidates) {
            if (candidate == null) continue;
            logPayloadCandidate(candidate);
            if (hasClawMobilePayload(candidate)) {
                Logger.logInfo(LOG_TAG, "Using ClawMobile payload directory " + candidate.getAbsolutePath());
                return candidate;
            }
        }

        Logger.logInfo(LOG_TAG, "No ClawMobile payload directory found");
        return null;
    }

    @Nullable
    private File buildPayloadChildDirectory(@Nullable File baseDir) {
        return baseDir == null ? null : new File(baseDir, CLAWMOBILE_PAYLOAD_EXTERNAL_DIR_NAME);
    }

    private void logPayloadCandidate(@NonNull File payloadDir) {
        File termuxLayerPayload = new File(payloadDir, CLAWMOBILE_TERMUX_LAYER_PAYLOAD_FILENAME);
        File rootfsPayload = new File(payloadDir, CLAWMOBILE_ROOTFS_PAYLOAD_FILENAME);

        Logger.logInfo(LOG_TAG,
            "Payload candidate " + payloadDir.getAbsolutePath()
                + " exists=" + payloadDir.exists()
                + " dir=" + payloadDir.isDirectory()
                + " termuxLayer=" + termuxLayerPayload.isFile()
                + " rootfs=" + rootfsPayload.isFile());
    }

    private boolean hasClawMobilePayload(@Nullable File payloadDir) {
        return payloadDir != null && payloadDir.isDirectory()
            && new File(payloadDir, CLAWMOBILE_TERMUX_LAYER_PAYLOAD_FILENAME).isFile()
            && new File(payloadDir, CLAWMOBILE_ROOTFS_PAYLOAD_FILENAME).isFile();
    }

    private void launchPayloadRestore(@NonNull File payloadDir) {
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(10);
        mLauncherStatusText.setText(R.string.clawmobile_installing);
        mLauncherProgressText.setText("Restoring runtime payload...");
        updateLauncherTerminalTail(null, "Restoring runtime payload...");

        new Thread(() -> {
            try {
                restoreClawMobilePayload(payloadDir);
                runOnUiThread(() -> onInstallComplete("Runtime restored from payload."));
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore ClawMobile payload", e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> onInstallFailed(reason));
            }
        }, "ClawMobilePayloadRestore").start();
    }

    private void restoreClawMobilePayload(@NonNull File payloadDir) throws IOException, InterruptedException {
        File termuxLayerPayload = new File(payloadDir, CLAWMOBILE_TERMUX_LAYER_PAYLOAD_FILENAME);
        File rootfsPayload = new File(payloadDir, CLAWMOBILE_ROOTFS_PAYLOAD_FILENAME);
        File filesDir = TermuxConstants.TERMUX_FILES_DIR;
        File installedRootfsDir = new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs");

        postInstallProgress(15, "Restoring ClawMobile base runtime...");
        extractTarPayload(termuxLayerPayload, filesDir, "ClawMobile base runtime payload");

        postInstallProgress(35, "Refreshing ClawMobile repo...");
        File homeRepoDir = prepareClawMobileRepoFromAssets();

        postInstallProgress(70, "Restoring Ubuntu runtime...");
        clearRestoredUbuntuRootfs(installedRootfsDir);
        extractTarPayload(rootfsPayload, installedRootfsDir, "Ubuntu runtime payload");

        postInstallProgress(90, "Activating restored runtime...");
        activateRestoredRuntime(homeRepoDir);
    }

    private void clearRestoredUbuntuRootfs(@NonNull File installedRootfsDir) throws IOException {
        deletePathWithSystemRm(new File(installedRootfsDir, CLAWMOBILE_DEFAULT_ROOTFS_NAME));
        deletePathWithSystemRm(new File(installedRootfsDir, CLAWMOBILE_FALLBACK_ROOTFS_NAME));
    }

    private void deletePathWithSystemRm(@NonNull File path) throws IOException {
        try {
            runProcess(Arrays.asList("/system/bin/rm", "-rf", path.getAbsolutePath()),
                "Deleting " + path.getAbsolutePath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while deleting " + path.getAbsolutePath(), e);
        }
    }

    private void activateRestoredRuntime(@NonNull File homeRepoDir) throws IOException {
        ensureDirectoryExists(TermuxConstants.TERMUX_DATA_HOME_DIR);
        ensureDirectoryExists(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh"));
        ensureDirectoryExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR);
        ensureDirectoryExists(new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/dlcache"));

        if (!TermuxConstants.TERMUX_TMP_PREFIX_DIR.setReadable(true, true)
            || !TermuxConstants.TERMUX_TMP_PREFIX_DIR.setWritable(true, true)
            || !TermuxConstants.TERMUX_TMP_PREFIX_DIR.setExecutable(true, true)) {
            throw new IOException("Failed to set tmp directory permissions");
        }

        File ubuntuRootfsDir = resolveRestoredUbuntuRootfsDir();
        File ubuntuRootRepoDir = new File(new File(ubuntuRootfsDir, "root"), CLAWMOBILE_REPO_DIR_NAME);
        prepareClawMobileRepoFromAssets(ubuntuRootRepoDir, false);
    }

    @NonNull
    private File resolveRestoredUbuntuRootfsDir() throws IOException {
        File defaultRootfsDir = new File(new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs"),
            CLAWMOBILE_DEFAULT_ROOTFS_NAME);
        if (defaultRootfsDir.isDirectory()) {
            return defaultRootfsDir;
        }

        File fallbackRootfsDir = new File(new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs"),
            CLAWMOBILE_FALLBACK_ROOTFS_NAME);
        if (fallbackRootfsDir.isDirectory()) {
            return fallbackRootfsDir;
        }

        throw new IOException("Restored Ubuntu rootfs not found");
    }

    private void ensureDirectoryExists(@NonNull File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (directory.exists()) {
            throw new IOException("Expected directory but found file: " + directory.getAbsolutePath());
        }
        if (!directory.mkdirs()) {
            throw new IOException("Failed to create directory " + directory.getAbsolutePath());
        }
    }

    private void extractTarPayload(@NonNull File tarFile, @NonNull File destinationDir,
                                   @NonNull String label) throws IOException, InterruptedException {
        if (!tarFile.isFile()) {
            throw new IOException("Missing payload: " + tarFile.getAbsolutePath());
        }

        ensureDirectoryExists(destinationDir);
        runProcess(Arrays.asList("/system/bin/tar", "-C", destinationDir.getAbsolutePath(),
            "-xf", tarFile.getAbsolutePath()), label);
    }

    private void runProcess(@NonNull List<String> command, @NonNull String label)
        throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output = readText(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String hint = getLastNonEmptyLine(output);
            throw new IOException(label + " failed" + (hint != null ? ": " + hint : " (exit " + exitCode + ")"));
        }
    }

    private void postInstallProgress(int progress, @NonNull String progressText) {
        runOnUiThread(() -> {
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(progress);
            mLauncherStatusText.setText(R.string.clawmobile_installing);
            mLauncherProgressText.setText(progressText);
            updateLauncherTerminalTail(null, progressText);
        });
    }

    private void updateLauncherTerminalTail(@Nullable String transcript, @Nullable String fallbackLine) {
        if (mLauncherTerminalTailText == null) return;

        String latestLine = getLastTerminalTailLine(transcript);
        if (latestLine == null || latestLine.isEmpty()) {
            latestLine = fallbackLine;
        }
        if (latestLine == null || latestLine.trim().isEmpty()) {
            mLauncherTerminalTailText.setText(R.string.clawmobile_terminal_tail_placeholder);
            return;
        }

        mLauncherTerminalTailText.setText(latestLine.trim());
    }

    @Nullable
    private String getLastTerminalTailLine(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;

        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = stripAnsiEscapeSequences(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("__CLAWMOBILE_")) continue;
            return line;
        }

        return null;
    }

    @NonNull
    private String createClawMobileMarker(@NonNull String stage) {
        return "__CLAWMOBILE_" + stage + "_" + System.nanoTime() + "__:";
    }

    @NonNull
    private String buildInstallScriptCommand(@NonNull String marker) {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        return "rc=0; cd \"" + repoDir + "\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./" +
            CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH + "; rc=$?; printf '\\n" + marker + "%s\\n' \"$rc\"";
    }

    @NonNull
    private String buildOnboardScriptCommand(@NonNull String marker, @NonNull List<String> args) {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        StringBuilder command = new StringBuilder();
        command.append("rc=0; cd \"").append(repoDir).append("\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./")
            .append(CLAWMOBILE_ONBOARD_SCRIPT_RELATIVE_PATH);
        for (String arg : args) {
            command.append(' ').append(shellQuote(arg));
        }
        command.append("; rc=$?; printf '\\n").append(marker).append("%s\\n' \"$rc\"");
        return command.toString();
    }

    @NonNull
    private String buildTelegramPairCommand(@NonNull String marker, @NonNull String code) {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        return "rc=0; cd \"" + repoDir + "\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./" +
            CLAWMOBILE_CHANNEL_PAIR_SCRIPT_RELATIVE_PATH + " " + shellQuote(code)
            + "; rc=$?; printf '\\n" + marker + "%s\\n' \"$rc\"";
    }

    @NonNull
    private String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private String normalizeLauncherPath(@NonNull String value) {
        String trimmed = value.trim();
        if (trimmed.equals("~")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        if (trimmed.startsWith("~/")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH + trimmed.substring(1);
        }
        if (trimmed.equals("$HOME") || trimmed.equals("${HOME}")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        if (trimmed.startsWith("$HOME/")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH + trimmed.substring("$HOME".length());
        }
        if (trimmed.startsWith("${HOME}/")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH + trimmed.substring("${HOME}".length());
        }
        return trimmed;
    }

    @Nullable
    private List<String> collectOnboardingArgsFromLauncher() {
        String provider = getSelectedOnboardingProvider();
        String workspaceValue = mLauncherOnboardingWorkspaceInput == null
            ? CLAWMOBILE_DEFAULT_WORKSPACE
            : mLauncherOnboardingWorkspaceInput.getText().toString();
        String workspace = normalizeLauncherPath(
            workspaceValue.trim().isEmpty() ? CLAWMOBILE_DEFAULT_WORKSPACE : workspaceValue);

        List<String> args = new ArrayList<>();
        args.add("--non-interactive");
        args.add("--flow");
        args.add("quickstart");
        args.add("--mode");
        args.add("local");
        args.add("--workspace");
        args.add(workspace);
        args.add("--gateway-bind");
        args.add("loopback");
        args.add("--gateway-port");
        args.add("18789");
        args.add("--skip-health");
        args.add("--skip-skills");
        args.add("--skip-channels");
        args.add("--skip-ui");

        String apiKey = mLauncherOnboardingApiKeyInput == null
            ? ""
            : mLauncherOnboardingApiKeyInput.getText().toString().trim();
        String baseUrl = mLauncherOnboardingBaseUrlInput == null
            ? ""
            : mLauncherOnboardingBaseUrlInput.getText().toString().trim();
        String modelId = mLauncherOnboardingModelIdInput == null
            ? ""
            : mLauncherOnboardingModelIdInput.getText().toString().trim();

        switch (provider) {
            case CLAWMOBILE_ONBOARD_PROVIDER_OPENAI:
                if (apiKey.isEmpty()) {
                    showToast("OpenAI API key is required", false);
                    return null;
                }
                args.add("--auth-choice");
                args.add("openai-api-key");
                args.add("--openai-api-key");
                args.add(apiKey);
                break;
            case CLAWMOBILE_ONBOARD_PROVIDER_ANTHROPIC:
                if (apiKey.isEmpty()) {
                    showToast("Anthropic API key is required", false);
                    return null;
                }
                args.add("--auth-choice");
                args.add("apiKey");
                args.add("--anthropic-api-key");
                args.add(apiKey);
                break;
            case CLAWMOBILE_ONBOARD_PROVIDER_GEMINI:
                if (apiKey.isEmpty()) {
                    showToast("Gemini API key is required", false);
                    return null;
                }
                args.add("--auth-choice");
                args.add("gemini-api-key");
                args.add("--gemini-api-key");
                args.add(apiKey);
                break;
            case CLAWMOBILE_ONBOARD_PROVIDER_ZAI:
                if (apiKey.isEmpty()) {
                    showToast("Z.AI API key is required", false);
                    return null;
                }
                args.add("--auth-choice");
                args.add("zai-api-key");
                args.add("--zai-api-key");
                args.add(apiKey);
                break;
            case CLAWMOBILE_ONBOARD_PROVIDER_CUSTOM:
                if (apiKey.isEmpty()) {
                    showToast("Custom provider API key is required", false);
                    return null;
                }
                if (baseUrl.isEmpty()) {
                    showToast("Custom base URL is required", false);
                    return null;
                }
                if (modelId.isEmpty()) {
                    showToast("Custom model ID is required", false);
                    return null;
                }
                args.add("--auth-choice");
                args.add("custom-api-key");
                args.add("--custom-api-key");
                args.add(apiKey);
                args.add("--custom-base-url");
                args.add(baseUrl);
                args.add("--custom-model-id");
                args.add(modelId);
                args.add("--custom-provider-id");
                args.add(CLAWMOBILE_ONBOARD_CUSTOM_PROVIDER_ID);
                break;
            case CLAWMOBILE_ONBOARD_PROVIDER_SKIP:
                args.add("--auth-choice");
                args.add("skip");
                break;
            default:
                showToast("Unknown OpenClaw auth provider", false);
                return null;
        }

        return args;
    }

    @NonNull
    private String buildAdbPairCommand(@NonNull String marker, @NonNull String host,
                                       @NonNull String pairPort, @NonNull String pairCode) {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        return "rc=0; cd \"" + repoDir + "\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./" +
            CLAWMOBILE_ADB_PAIR_SCRIPT_RELATIVE_PATH + " " + shellQuote(host) + " " + shellQuote(pairPort) + " "
            + shellQuote(pairCode)
            + "; rc=$?; printf '\\n" + marker + "%s\\n' \"$rc\"";
    }

    @NonNull
    private String buildAdbConnectCommand(@NonNull String marker, @NonNull String host,
                                          @NonNull String connectPort) {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        return "rc=0; cd \"" + repoDir + "\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./" +
            CLAWMOBILE_ADB_CONNECT_SCRIPT_RELATIVE_PATH + " " + shellQuote(host) + " " + shellQuote(connectPort)
            + "; rc=$?; printf '\\n" + marker + "%s\\n' \"$rc\"";
    }

    @NonNull
    private String buildRunScriptCommand() {
        String repoDir = "$HOME/" + CLAWMOBILE_REPO_DIR_NAME;
        return "cd \"" + repoDir + "\" && chmod +x installer/termux/*.sh installer/ubuntu/*.sh && ./" +
            CLAWMOBILE_RUN_SCRIPT_RELATIVE_PATH;
    }

    @Nullable
    private TerminalSession createFreshInstallSession() {
        if (mTermuxService == null || mTermuxTerminalSessionActivityClient == null) {
            return null;
        }

        retirePreparationSession();
        closeDedicatedInstallSession();

        if (mTermuxService.getTermuxSessionsSize() >= 8) {
            return null;
        }

        TermuxSession termuxSession = mTermuxService.createTermuxSession(null, null, null,
            TermuxConstants.TERMUX_HOME_DIR_PATH, false, CLAWMOBILE_INSTALL_SESSION_NAME);
        if (termuxSession == null) {
            return null;
        }

        mInstallSession = termuxSession.getTerminalSession();
        mInstallSessionIsDedicated = true;
        mTermuxTerminalSessionActivityClient.setCurrentSession(mInstallSession);
        return mInstallSession;
    }

    private boolean isClawMobilePreparedOnDisk() {
        File repoDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, CLAWMOBILE_REPO_DIR_NAME);
        File installScript = new File(repoDir, CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH);
        return repoDir.isDirectory() && installScript.isFile();
    }

    @NonNull
    private File getClawMobileRootfsDir() {
        return new File(new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs"),
            CLAWMOBILE_DEFAULT_ROOTFS_NAME);
    }

    @NonNull
    private File getClawMobileStateDirInRootfs() {
        return new File(getClawMobileRootfsDir(), "root/.openclaw");
    }

    @NonNull
    private ClawMobileHealthSnapshot buildClawMobileHealthSnapshot() {
        File repoDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, CLAWMOBILE_REPO_DIR_NAME);
        File installScript = new File(repoDir, CLAWMOBILE_INSTALL_SCRIPT_RELATIVE_PATH);
        File runtimeDir = getClawMobileRootfsDir();
        File stateDir = getClawMobileStateDirInRootfs();
        File adbBinary = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "adb");

        boolean repoReady = repoDir.isDirectory() && installScript.isFile();
        boolean runtimeReady = isClawMobileRuntimeInstalledOnDisk();
        boolean setupReady = stateDir.isDirectory();
        boolean adbReady = adbBinary.isFile() && adbBinary.canExecute();
        boolean gatewayOnline = isLocalPortOpen(18789);
        boolean browserControlOnline = isLocalPortOpen(18791);
        ClawMobileAdbProbe adbProbe = probeClawMobileAdb(adbReady);

        ClawMobileHealthItem repoItem = repoReady
            ? healthyItem("Ready", "~/ClawMobile is deployed with installer scripts.")
            : faultItem("Missing", "Bundled repo has not been deployed to ~/ClawMobile yet.");

        ClawMobileHealthItem runtimeItem = runtimeReady
            ? healthyItem("Installed", "Ubuntu rootfs and OpenClaw binaries are present.")
            : faultItem("Missing", "Run the Setup tab to deploy Ubuntu and OpenClaw.");

        ClawMobileHealthItem setupItem;
        if (!runtimeReady) {
            setupItem = idleItem("Waiting", "OpenClaw setup appears after the runtime is installed.");
        } else if (setupReady) {
            setupItem = healthyItem("Configured", "OpenClaw state directory exists inside Ubuntu.");
        } else {
            setupItem = warnItem("Needed", "Apply OpenClaw setup from the Channels tab.");
        }

        ClawMobileHealthItem adbItem = adbReady
            ? healthyItem("Available", "Termux adb binary is ready for local pairing and connect.")
            : faultItem("Missing", "Termux adb binary is not available yet.");

        ClawMobileHealthItem deviceItem = new ClawMobileHealthItem(
            adbProbe.linked ? "Linked" : (adbProbe.available ? "Waiting" : "Unavailable"),
            adbProbe.detail,
            adbProbe.colorResId,
            adbProbe.linked
        );

        ClawMobileHealthItem gatewayItem;
        if (!runtimeReady) {
            gatewayItem = idleItem("Waiting", "Gateway can start only after runtime deployment.");
        } else if (gatewayOnline) {
            String detail = browserControlOnline
                ? "Gateway is listening on 127.0.0.1:18789 and browser control is up on 18791."
                : "Gateway is listening on 127.0.0.1:18789.";
            gatewayItem = healthyItem("Online", detail);
        } else if (setupReady) {
            gatewayItem = warnItem("Stopped", "OpenClaw is configured but the gateway is not running.");
        } else {
            gatewayItem = warnItem("Not ready", "Finish OpenClaw setup before starting the gateway.");
        }

        return new ClawMobileHealthSnapshot(repoItem, runtimeItem, setupItem, adbItem, deviceItem,
            gatewayItem, repoReady, runtimeReady, setupReady, adbReady, adbProbe.linked,
            gatewayOnline, browserControlOnline);
    }

    private void applyClawMobileHealthSnapshot(@NonNull ClawMobileHealthSnapshot snapshot,
                                               boolean forceVisibleFeedback) {
        mIsHealthRefreshRunning = false;
        mHasClawMobileRuntimeInstalled = snapshot.runtimeReady;
        setHealthUiEnabled(true);

        bindHealthCard(mLauncherHealthRepoDot, mLauncherHealthRepoStateText, mLauncherHealthRepoDetailText, snapshot.repo);
        bindHealthCard(mLauncherHealthRuntimeDot, mLauncherHealthRuntimeStateText, mLauncherHealthRuntimeDetailText, snapshot.runtime);
        bindHealthCard(mLauncherHealthSetupDot, mLauncherHealthSetupStateText, mLauncherHealthSetupDetailText, snapshot.setup);
        bindHealthCard(mLauncherHealthAdbDot, mLauncherHealthAdbStateText, mLauncherHealthAdbDetailText, snapshot.adb);
        bindHealthCard(mLauncherHealthDeviceDot, mLauncherHealthDeviceStateText, mLauncherHealthDeviceDetailText, snapshot.device);
        bindHealthCard(mLauncherHealthGatewayDot, mLauncherHealthGatewayStateText, mLauncherHealthGatewayDetailText, snapshot.gateway);

        updateOperateSurfaceFromHealth(snapshot);
        setOperateUiEnabled(true);

        if (!mIsInstallRunning && !mIsOnboardingRunning && !mIsAdbPairingRunning
            && !mIsChannelPairingRunning && !mIsRuntimeLaunchRunning) {
            if (snapshot.runtimeReady) {
                setOnboardingSectionVisible(true);
                setPairingSectionVisible(true);
                setChannelPairingSectionVisible(true);
                setOnboardingUiEnabled(true);
                setPairingUiEnabled(true);
                setChannelPairingUiEnabled(true);
            } else {
                setOnboardingSectionVisible(false);
                setPairingSectionVisible(false);
                setChannelPairingSectionVisible(false);
                setOnboardingUiEnabled(true);
                setPairingUiEnabled(false);
                setChannelPairingUiEnabled(false);
            }
            updatePrimaryLauncherButtonState();
        }

        if (forceVisibleFeedback && !mIsInstallRunning && !mIsOnboardingRunning
            && !mIsAdbPairingRunning && !mIsChannelPairingRunning && !mIsRuntimeLaunchRunning) {
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherStatusText.setText(snapshot.gatewayOnline
                ? R.string.clawmobile_runtime_running
                : R.string.clawmobile_ready);
            mLauncherProgressText.setText(snapshot.gateway.detail);
            updateLauncherTerminalTail(null, snapshot.gateway.detail);
        }
    }

    private void updateOperateSurfaceFromHealth(@NonNull ClawMobileHealthSnapshot snapshot) {
        if (mLauncherOperateStatusText == null || mLauncherOperateSummaryText == null
            || mLauncherOperateDetailText == null || mLauncherOperateGatewayValueText == null
            || mLauncherOperateDeviceValueText == null || mLauncherRunButton == null) {
            return;
        }

        String operateState;
        String operateSummary;
        String operateDetail;
        int operateColorRes;

        if (snapshot.gatewayOnline) {
            operateState = "Online";
            operateSummary = "OpenClaw gateway is live and ready for daily use.";
            operateDetail = snapshot.browserControlOnline
                ? "Gateway and browser control are both reachable on loopback."
                : "Gateway is reachable on loopback. Browser control has not reported in yet.";
            operateColorRes = R.color.claw_signal;
        } else if (!snapshot.runtimeReady) {
            operateState = "Setup required";
            operateSummary = "Deploy the runtime from Setup before using daily controls.";
            operateDetail = "Ubuntu and OpenClaw are not installed on disk yet.";
            operateColorRes = R.color.claw_ember;
        } else if (!snapshot.setupReady) {
            operateState = "Configuration required";
            operateSummary = "Runtime is installed. Apply OpenClaw setup in the Channels tab next.";
            operateDetail = "OpenClaw state has not been created inside Ubuntu yet.";
            operateColorRes = R.color.claw_amber;
        } else if (!snapshot.deviceLinked) {
            operateState = "Waiting for device";
            operateSummary = "OpenClaw is configured. Link the local phone over wireless ADB next.";
            operateDetail = snapshot.device.detail;
            operateColorRes = R.color.claw_amber;
        } else {
            operateState = "Ready to start";
            operateSummary = "All prerequisites are in place. Start OpenClaw whenever you are ready.";
            operateDetail = "The gateway is currently stopped, but the local stack looks healthy.";
            operateColorRes = R.color.claw_amber;
        }

        mLauncherOperateStatusText.setText(operateState);
        mLauncherOperateStatusText.setTextColor(ContextCompat.getColor(this, operateColorRes));
        tintStatusDot(mLauncherOperateStatusDot, operateColorRes);
        mLauncherOperateSummaryText.setText(operateSummary);
        mLauncherOperateDetailText.setText(operateDetail);
        mLauncherOperateGatewayValueText.setText(snapshot.gatewayOnline
            ? "Online · 127.0.0.1:18789"
            : "Offline · 127.0.0.1:18789");
        mLauncherOperateDeviceValueText.setText(snapshot.deviceLinked
            ? "Linked · local wireless adb"
            : "Waiting · local wireless adb");
        ((MaterialButton) mLauncherRunButton).setText(
            snapshot.gatewayOnline ? R.string.clawmobile_run_restart : R.string.clawmobile_run_start);
    }

    private void bindHealthCard(@Nullable View dotView, @Nullable TextView stateView, @Nullable TextView detailView,
                                @NonNull ClawMobileHealthItem item) {
        tintStatusDot(dotView, item.colorResId);
        if (stateView != null) {
            stateView.setText(item.state);
            stateView.setTextColor(ContextCompat.getColor(this, item.colorResId));
        }
        if (detailView != null) {
            detailView.setText(item.detail);
        }
    }

    private void tintStatusDot(@Nullable View dotView, int colorResId) {
        if (dotView == null) return;
        dotView.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorResId)));
    }

    @NonNull
    private ClawMobileHealthItem healthyItem(@NonNull String state, @NonNull String detail) {
        return new ClawMobileHealthItem(state, detail, R.color.claw_signal, true);
    }

    @NonNull
    private ClawMobileHealthItem warnItem(@NonNull String state, @NonNull String detail) {
        return new ClawMobileHealthItem(state, detail, R.color.claw_amber, false);
    }

    @NonNull
    private ClawMobileHealthItem faultItem(@NonNull String state, @NonNull String detail) {
        return new ClawMobileHealthItem(state, detail, R.color.claw_ember, false);
    }

    @NonNull
    private ClawMobileHealthItem idleItem(@NonNull String state, @NonNull String detail) {
        return new ClawMobileHealthItem(state, detail, R.color.claw_text_muted, false);
    }

    private boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    @NonNull
    private ClawMobileAdbProbe probeClawMobileAdb(boolean adbReady) {
        if (!adbReady) {
            return new ClawMobileAdbProbe("Termux adb binary is missing.", false, false, R.color.claw_ember);
        }

        File adbBinary = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "adb");
        try {
            runCommandCapture(Arrays.asList(adbBinary.getAbsolutePath(), "start-server"));
            String output = runCommandCapture(Arrays.asList(adbBinary.getAbsolutePath(), "devices"));
            String[] lines = output.split("\\R");
            String unauthorizedLine = null;
            String offlineLine = null;

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("List of devices attached")) continue;
                if (line.matches(".+\\sdevice$")) {
                    return new ClawMobileAdbProbe(line, true, true, R.color.claw_signal);
                }
                if (line.contains("\tunauthorized")) unauthorizedLine = line;
                if (line.contains("\toffline")) offlineLine = line;
            }

            if (unauthorizedLine != null) {
                return new ClawMobileAdbProbe("ADB transport is visible but still unauthorized: " + unauthorizedLine,
                    false, true, R.color.claw_amber);
            }

            if (offlineLine != null) {
                return new ClawMobileAdbProbe("ADB transport is present but offline: " + offlineLine,
                    false, true, R.color.claw_amber);
            }

            return new ClawMobileAdbProbe("No local wireless ADB device is connected yet.",
                false, true, R.color.claw_amber);
        } catch (IOException e) {
            return new ClawMobileAdbProbe("Could not execute adb: " + e.getMessage(),
                false, false, R.color.claw_ember);
        }
    }

    @NonNull
    private String runCommandCapture(@NonNull List<String> command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(TermuxConstants.TERMUX_HOME_DIR);
        processBuilder.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        processBuilder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        processBuilder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        processBuilder.environment().put("PATH",
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
            StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while executing command", e);
        }

        return output.toString();
    }

    private void scheduleAutomaticClawMobilePreparation() {
        if (mHasScheduledClawMobilePreparation) return;
        mHasScheduledClawMobilePreparation = true;
        new android.os.Handler(getMainLooper()).post(this::startAutomaticClawMobilePreparation);
    }

    private void startAutomaticClawMobilePreparation() {
        if (mTermuxService == null || mTermuxTerminalSessionActivityClient == null) {
            return;
        }

        if (mIsClawMobilePreparing || mIsInstallRunning) {
            return;
        }

        mIsClawMobilePreparing = true;
        mLauncherInstallButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_preparing);
        mLauncherStatusText.setText(R.string.clawmobile_preparing);
        mLauncherProgressSection.setVisibility(View.VISIBLE);
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(10);
        mLauncherProgressText.setText("Deploying bundled ClawMobile repo to ~/ClawMobile...");
        updateLauncherTerminalTail(null, "Deploying bundled ClawMobile repo to ~/ClawMobile...");
        updateConsolePreparingState("Deploying bundled ClawMobile repo to ~/ClawMobile...");

        new Thread(() -> {
            try {
                prepareClawMobileRepoFromAssets();
                runOnUiThread(() -> {
                    onPreparationReady();
                    if (mPendingInstallAfterPreparation) {
                        mPendingInstallAfterPreparation = false;
                        startClawMobileInstall();
                    }
                });
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to deploy bundled ClawMobile repo", e);
                String reason = e.getMessage() != null ? e.getMessage() : "Failed to deploy bundled ClawMobile repo";
                runOnUiThread(() -> onInstallFailed(reason));
            }
        }, "ClawMobileRepoPrepare").start();
    }

    private void injectInstallCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                               int attempt, @NonNull String statusText,
                                               @Nullable String completionMarker,
                                               @Nullable Runnable completionAction) {
        if (!mIsInstallRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText(statusText);
            session.getEmulator().paste(command);
            session.write("\r");
            if (completionMarker != null && completionAction != null) {
                monitorInstallMarker(session, completionMarker, completionAction);
            } else {
                onInstallCommandInjected();
            }
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onInstallFailed("Terminal session did not become ready");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt...");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> injectInstallCommandWhenReady(session, command, attempt + 1, statusText,
                completionMarker, completionAction),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorInstallMarker(@NonNull TerminalSession session, @NonNull String marker,
                                      @NonNull Runnable completionAction) {
        if (!mIsInstallRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        Integer exitCode = parseInstallMarkerExitCode(transcript, marker);
        if (exitCode != null) {
            if (exitCode == 0) {
                completionAction.run();
            } else {
                String hint = getLastNonEmptyLine(transcript);
                onInstallFailed(hint != null ? hint : "Install step failed");
            }
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorInstallMarker(session, marker, completionAction),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private void onPreparationReady() {
        mIsClawMobilePreparing = false;
        mIsClawMobilePrepared = true;
        mHasClawMobileRuntimeInstalled = isClawMobileRuntimeInstalledOnDisk();
        selectLauncherTab(mHasClawMobileRuntimeInstalled ? CLAWMOBILE_TAB_CHANNELS : CLAWMOBILE_TAB_SETUP);
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_ready);
        if (mHasClawMobileRuntimeInstalled) {
            mLauncherProgressText.setText("OpenClaw runtime already exists. Review the Channels tab below.");
            updateLauncherTerminalTail(null, "Runtime already installed. Ready for OpenClaw setup.");
            setOnboardingSectionVisible(true);
            setPairingSectionVisible(true);
            setChannelPairingSectionVisible(true);
            setOnboardingUiEnabled(true);
            setPairingUiEnabled(true);
            setChannelPairingUiEnabled(true);
            setOperateUiEnabled(true);
            updateConsoleReadyState("Runtime already detected on disk. Configure OpenClaw from the Channels tab.");
        } else {
            mLauncherProgressText.setText("Bundled ClawMobile repo is ready at ~/ClawMobile. Tap Install to run install.sh.");
            updateLauncherTerminalTail(null, "Bundled repo is ready at ~/ClawMobile.");
            setOnboardingSectionVisible(false);
            setPairingSectionVisible(false);
            setChannelPairingSectionVisible(false);
            setOperateUiEnabled(false);
            updateConsoleIdleState();
        }
        setHealthUiEnabled(true);
        mLauncherInstallButton.setEnabled(true);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void retirePreparationSession() {
        TerminalSession session = mInstallSession;
        boolean isDedicated = mInstallSessionIsDedicated;
        mInstallSession = null;
        mInstallSessionIsDedicated = false;

        if (session == null) return;
        if (!isDedicated) return;
        if (session.getPid() <= 0) return;

        // Preparation runs in a hidden shell. Close it once git/pkg work has
        // finished so the real installer starts in a fresh shell, matching the
        // clean manual path more closely.
        session.write("exit\r");
    }

    private void closeDedicatedInstallSession() {
        TerminalSession session = mInstallSession;
        boolean isDedicated = mInstallSessionIsDedicated;
        mInstallSession = null;
        mInstallSessionIsDedicated = false;

        if (session == null) return;
        if (!isDedicated) return;
        if (session.getPid() <= 0) return;

        session.write("exit\r");
    }

    private void clearInstallSessionReference() {
        mInstallSession = null;
        mInstallSessionIsDedicated = false;
    }

    private void launchInstallCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                               int attempt, @NonNull String completionMarker) {
        if (!mIsInstallRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText("Pasting install.sh into the foreground terminal...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "Shell prompt detected. Injecting install.sh...");
            updateConsoleInstallState("Pasting upstream install.sh into the foreground terminal...", "Install shell", "Foreground terminal", "Command injection ready");
            session.getEmulator().paste(command);
            session.write("\r");
            mLauncherProgressText.setText("install.sh is now running in the foreground terminal session...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "install.sh has been sent to the live shell.");
            updateConsoleInstallState("install.sh is now running in the foreground terminal session...", "Installer active", "Ubuntu bootstrap", "Streaming live transcript");
            monitorInstallerExecution(session, completionMarker);
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onInstallFailed("Terminal session did not become ready");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt...");
        updateLauncherTerminalTail(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false),
            "Waiting for shell prompt...");
        updateConsoleInstallState("Waiting for the shell prompt before injecting install.sh...", "Install shell", "Foreground terminal", "Awaiting prompt");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> launchInstallCommandWhenReady(session, command, attempt + 1, completionMarker),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorInstallerExecution(@NonNull TerminalSession session, @NonNull String completionMarker) {
        if (!mIsInstallRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        updateLauncherTerminalTail(transcript, null);
        updateInstallProgressFromTranscript(transcript);

        Integer exitCode = parseInstallMarkerExitCode(transcript, completionMarker);
        if (exitCode != null) {
            if (exitCode == 0) {
                mHasClawMobileRuntimeInstalled = true;
                onInstallComplete("ClawMobile installed. Review OpenClaw setup below.");
            } else {
                String hint = getLastNonEmptyLine(transcript);
                onInstallFailed(hint != null ? hint : "install.sh failed");
            }
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorInstallerExecution(session, completionMarker),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private void startClawMobileOnboarding(@NonNull List<String> onboardArgs,
                                           @Nullable TerminalSession existingSession) {
        TerminalSession session = existingSession;
        if (session == null) {
            session = createFreshInstallSession();
        }
        if (session == null) {
            onInstallFailed("Failed to create onboarding terminal session");
            return;
        }

        mIsInstallRunning = false;
        mIsOnboardingRunning = true;
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        mLauncherInstallButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboarding);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherStatusText.setText(R.string.clawmobile_onboarding);
        mLauncherProgressText.setText("Applying OpenClaw setup from launcher selections...");
        updateConsoleOnboardState("Applying OpenClaw setup from the Channels tab...", "Waiting for prompt");
        showLauncherOverlay();
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        setOnboardingSectionVisible(true);
        setChannelPairingSectionVisible(true);

        String onboardMarker = createClawMobileMarker("ONBOARD");
        launchOnboardCommandWhenReady(session, buildOnboardScriptCommand(onboardMarker, onboardArgs), 0, onboardMarker);
    }

    private void startAdbPairing(@NonNull String host, @NonNull String pairPort,
                                 @NonNull String pairCode) {
        TerminalSession session = createFreshInstallSession();
        if (session == null) {
            onInstallFailed("Failed to create device link terminal session");
            return;
        }

        mIsAdbPairingRunning = true;
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        mLauncherInstallButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_pairing_running);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
        mLauncherProgressText.setText("Approving wireless ADB pairing code...");
        updateLauncherTerminalTail(null, "Waiting for shell prompt before approving wireless ADB pairing...");
        updateConsolePairState("Preparing wireless ADB pairing command...", "Waiting for prompt");
        showLauncherOverlay();
        selectLauncherTab(CLAWMOBILE_TAB_SETUP);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);

        String pairMarker = createClawMobileMarker("PAIR");
        launchAdbPairCommandWhenReady(session,
            buildAdbPairCommand(pairMarker, host, pairPort, pairCode), 0, pairMarker);
    }

    private void startAdbConnect(@NonNull String host, @NonNull String connectPort) {
        TerminalSession session = createFreshInstallSession();
        if (session == null) {
            onInstallFailed("Failed to create device connect terminal session");
            return;
        }

        mIsAdbPairingRunning = true;
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        mLauncherInstallButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_pairing_running);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
        mLauncherProgressText.setText("Connecting local wireless ADB device...");
        updateLauncherTerminalTail(null, "Waiting for shell prompt before connecting local wireless ADB...");
        updateConsolePairState("Preparing wireless ADB connect command...", "Waiting for prompt");
        showLauncherOverlay();
        selectLauncherTab(CLAWMOBILE_TAB_SETUP);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);

        String connectMarker = createClawMobileMarker("CONNECT");
        launchAdbPairCommandWhenReady(session,
            buildAdbConnectCommand(connectMarker, host, connectPort), 0, connectMarker);
    }

    private void startTelegramPairing(@NonNull String pairCode) {
        TerminalSession session = createFreshInstallSession();
        if (session == null) {
            onInstallFailed("Failed to create channel pairing terminal session");
            return;
        }

        mIsChannelPairingRunning = true;
        setOnboardingUiEnabled(false);
        setPairingUiEnabled(false);
        setChannelPairingUiEnabled(false);
        setOperateUiEnabled(false);
        setHealthUiEnabled(false);
        mLauncherInstallButton.setEnabled(false);
        ((MaterialButton) mLauncherInstallButton).setText(R.string.clawmobile_channel_pairing_running);
        mLauncherProgressBar.setIndeterminate(true);
        mLauncherStatusText.setText(R.string.clawmobile_channel_pairing_running);
        mLauncherProgressText.setText("Approving Telegram chat pairing...");
        updateLauncherTerminalTail(null, "Waiting for shell prompt before approving Telegram pairing...");
        updateConsoleChannelState("Preparing Telegram pairing approval...", "Waiting for prompt");
        showLauncherOverlay();
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);

        String pairMarker = createClawMobileMarker("CHANNEL_PAIR");
        launchChannelPairCommandWhenReady(session,
            buildTelegramPairCommand(pairMarker, pairCode), 0, pairMarker);
    }

    private void launchRunCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                           int attempt) {
        if (!mIsRuntimeLaunchRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText("Starting OpenClaw gateway in the live shell...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "Shell prompt detected. Launching OpenClaw gateway...");
            updateConsoleRuntimeState("Starting OpenClaw gateway in the live shell...", "Command injection ready");
            session.getEmulator().paste(command);
            session.write("\r");
            updateConsoleRuntimeState("OpenClaw gateway command is running in the live shell...", "Gateway startup transcript");
            monitorRunExecution(session, 0);
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onRuntimeFailed("Terminal session did not become ready for OpenClaw runtime");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt before launching OpenClaw...");
        updateLauncherTerminalTail(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false),
            "Waiting for shell prompt before launching OpenClaw...");
        updateConsoleRuntimeState("Waiting for the shell prompt before launching OpenClaw...", "Awaiting prompt");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> launchRunCommandWhenReady(session, command, attempt + 1),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorRunExecution(@NonNull TerminalSession session, int attempt) {
        if (!mIsRuntimeLaunchRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        updateLauncherTerminalTail(transcript, null);
        updateRuntimeProgressFromTranscript(transcript);

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);
        if (cleanedTranscript.contains("listening on ws://127.0.0.1:18789")
            || cleanedTranscript.contains("listening on ws://[::1]:18789")
            || isLocalPortOpen(18789)) {
            onRuntimeReady("OpenClaw gateway is online on 127.0.0.1:18789.");
            return;
        }

        if (cleanedTranscript.contains("command not found")
            || cleanedTranscript.contains("No such file or directory")
            || cleanedTranscript.contains("Error: Could not")
            || cleanedTranscript.contains("Error: Can't find")) {
            String hint = getLastNonEmptyLine(transcript);
            onRuntimeFailed(hint != null ? hint : "OpenClaw runtime failed to start");
            return;
        }

        if (attempt >= CLAWMOBILE_RUNTIME_READY_RETRY_LIMIT) {
            onRuntimePending("OpenClaw start command is still running. Check the Health tab for live status.");
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorRunExecution(session, attempt + 1),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private void launchAdbPairCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                               int attempt, @NonNull String completionMarker) {
        if (!mIsAdbPairingRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText("Sending wireless ADB command into the live shell...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "Shell prompt detected. Running wireless ADB command...");
            updateConsolePairState("Sending wireless ADB command into the live shell...", "Command injection ready");
            session.getEmulator().paste(command);
            session.write("\r");
            updateConsolePairState("Wireless ADB command is running in the live shell...", "ADB pairing transcript");
            monitorAdbPairExecution(session, completionMarker);
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onAdbPairingFailed("Terminal session did not become ready for wireless ADB link");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt before running wireless ADB command...");
        updateLauncherTerminalTail(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false),
            "Waiting for shell prompt before running wireless ADB command...");
        updateConsolePairState("Waiting for the shell prompt before running wireless ADB command...", "Awaiting prompt");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> launchAdbPairCommandWhenReady(session, command, attempt + 1, completionMarker),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorAdbPairExecution(@NonNull TerminalSession session, @NonNull String completionMarker) {
        if (!mIsAdbPairingRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        String cleanedTranscript = stripAnsiEscapeSequences(transcript);
        updateLauncherTerminalTail(transcript, null);
        updateAdbPairProgressFromTranscript(transcript);

        Integer exitCode = parseInstallMarkerExitCode(transcript, completionMarker);
        if (exitCode != null) {
            if (exitCode == 0) {
                boolean isConnect = cleanedTranscript.contains("[adb-connect]");
                if (isConnect) {
                    onAdbPairingReady("Local wireless ADB device linked.", true);
                } else {
                    onAdbPairingReady("Wireless ADB pairing approved. Run Connect local device next.", false);
                }
            } else {
                String hint = getLastNonEmptyLine(transcript);
                onAdbPairingFailed(hint != null ? hint : "Wireless ADB link failed");
            }
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorAdbPairExecution(session, completionMarker),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private void launchChannelPairCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                                   int attempt, @NonNull String completionMarker) {
        if (!mIsChannelPairingRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText("Sending Telegram pairing approval into the live shell...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "Shell prompt detected. Running Telegram pairing approval...");
            updateConsoleChannelState("Sending Telegram pairing approval into the live shell...", "Command injection ready");
            session.getEmulator().paste(command);
            session.write("\r");
            updateConsoleChannelState("Telegram pairing approval is running in the live shell...", "Channel pairing transcript");
            monitorChannelPairExecution(session, completionMarker);
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onChannelPairingFailed("Terminal session did not become ready for Telegram pairing");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt before approving Telegram pairing...");
        updateLauncherTerminalTail(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false),
            "Waiting for shell prompt before approving Telegram pairing...");
        updateConsoleChannelState("Waiting for the shell prompt before approving Telegram pairing...", "Awaiting prompt");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> launchChannelPairCommandWhenReady(session, command, attempt + 1, completionMarker),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorChannelPairExecution(@NonNull TerminalSession session, @NonNull String completionMarker) {
        if (!mIsChannelPairingRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        updateLauncherTerminalTail(transcript, null);
        updateChannelPairProgressFromTranscript(transcript);

        Integer exitCode = parseInstallMarkerExitCode(transcript, completionMarker);
        if (exitCode != null) {
            if (exitCode == 0) {
                onChannelPairingReady("Telegram chat pairing approved.");
            } else {
                String hint = getLastNonEmptyLine(transcript);
                onChannelPairingFailed(hint != null ? hint : "Telegram pairing failed");
            }
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorChannelPairExecution(session, completionMarker),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private void launchOnboardCommandWhenReady(@NonNull TerminalSession session, @NonNull String command,
                                               int attempt, @NonNull String completionMarker) {
        if (!mIsOnboardingRunning || session != mInstallSession) return;

        if (isInstallShellReady(session)) {
            mLauncherProgressText.setText("Starting non-interactive OpenClaw setup in the live shell...");
            updateLauncherTerminalTail(
                ShellUtils.getTerminalSessionTranscriptText(session, false, false),
                "Shell prompt detected. Applying OpenClaw setup...");
            updateConsoleOnboardState("Sending non-interactive OpenClaw setup into the live shell...", "Command injection ready");
            session.getEmulator().paste(command);
            session.write("\r");
            updateConsoleOnboardState("OpenClaw setup is now running in the live shell session...", "Non-interactive onboard transcript");
            monitorOnboardExecution(session, completionMarker);
            return;
        }

        if (attempt >= CLAWMOBILE_INSTALL_SESSION_READY_RETRY_LIMIT) {
            onInstallFailed("Terminal session did not become ready for onboard");
            return;
        }

        mLauncherProgressText.setText("Waiting for shell prompt before applying OpenClaw setup...");
        updateLauncherTerminalTail(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false),
            "Waiting for shell prompt before applying OpenClaw setup...");
        updateConsoleOnboardState("Waiting for the shell prompt before applying OpenClaw setup...", "Awaiting prompt");
        new android.os.Handler(getMainLooper()).postDelayed(
            () -> launchOnboardCommandWhenReady(session, command, attempt + 1, completionMarker),
            CLAWMOBILE_INSTALL_SESSION_READY_RETRY_DELAY_MS
        );
    }

    private void monitorOnboardExecution(@NonNull TerminalSession session, @NonNull String completionMarker) {
        if (!mIsOnboardingRunning || session != mInstallSession) return;

        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, false);
        updateLauncherTerminalTail(transcript, null);
        updateOnboardProgressFromTranscript(transcript);

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);
        if (cleanedTranscript.contains("Onboard complete")) {
            onOnboardReady("OpenClaw setup reached completion.");
            return;
        }

        Integer exitCode = parseInstallMarkerExitCode(transcript, completionMarker);
        if (exitCode != null) {
            if (exitCode == 0) {
                onOnboardReady("OpenClaw setup finished successfully.");
            } else {
                String hint = getLastNonEmptyLine(transcript);
                onOnboardFailed(hint != null ? hint : "OpenClaw setup failed");
            }
            return;
        }

        new android.os.Handler(getMainLooper()).postDelayed(
            () -> monitorOnboardExecution(session, completionMarker),
            CLAWMOBILE_INSTALL_STEP_POLL_DELAY_MS
        );
    }

    private boolean isInstallShellReady(@Nullable TerminalSession session) {
        if (session == null || session.getEmulator() == null || session.getPid() <= 0) {
            return false;
        }

        String transcript = stripAnsiEscapeSequences(
            ShellUtils.getTerminalSessionTranscriptText(session, false, false));
        String lastLine = getLastNonEmptyLine(transcript);
        if (lastLine == null) {
            return false;
        }

        // Wait for a real shell prompt instead of only checking for a live
        // TerminalSession. Otherwise command injection can race MOTD/startup
        // output, which diverges from the stable manual path.
        return lastLine.matches(".*[#$]\\s?$");
    }

    private void updateInstallProgressFromTranscript(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) return;

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);

        if (cleanedTranscript.contains("OpenClaw installed successfully")) {
            advanceLauncherInstallProgress(96, R.string.clawmobile_bootstrapping_ubuntu, "OpenClaw installed inside Ubuntu.");
            updateConsoleInstallState("OpenClaw installed inside Ubuntu. Preparing the Channels tab next...", "Bootstrap complete", "OpenClaw / Ubuntu", "Waiting for installer exit");
            return;
        }
        if (cleanedTranscript.contains("[✓] Install finished.")) {
            advanceLauncherInstallProgress(100, R.string.clawmobile_installing, "ClawMobile install finished.");
            updateConsoleInstallState("ClawMobile install finished. Returning to the Channels tab...", "Installer complete", "Foreground terminal", "Waiting for completion");
            return;
        }
        if (cleanedTranscript.contains("[✓] Bootstrap complete.")) {
            advanceLauncherInstallProgress(95, R.string.clawmobile_bootstrapping_ubuntu, "Ubuntu bootstrap complete.");
            updateConsoleInstallState("Ubuntu bootstrap complete.", "Bootstrap complete", "Ubuntu / OpenClaw", "Installer finalizing");
            return;
        }
        if (cleanedTranscript.contains("Installing OpenClaw v") || cleanedTranscript.contains("[*] OpenClaw installation")) {
            advanceLauncherInstallProgress(88, R.string.clawmobile_bootstrapping_ubuntu, "Installing OpenClaw inside Ubuntu...");
            updateConsoleInstallState("Installing OpenClaw inside Ubuntu...", "OpenClaw install", "Ubuntu / npm", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("Installing Droidrun")) {
            advanceLauncherInstallProgress(82, R.string.clawmobile_bootstrapping_ubuntu, "Installing Droidrun inside Ubuntu...");
            updateConsoleInstallState("Installing Droidrun inside Ubuntu...", "Python setup", "Ubuntu / pip", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("Creating venv for clawbot/openclaw tooling")) {
            advanceLauncherInstallProgress(76, R.string.clawmobile_bootstrapping_ubuntu, "Creating Ubuntu venv for ClawMobile...");
            updateConsoleInstallState("Creating Ubuntu venv for ClawMobile...", "Python runtime", "Ubuntu bootstrap", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Installing base dependencies...")) {
            advanceLauncherInstallProgress(70, R.string.clawmobile_bootstrapping_ubuntu, "Installing Ubuntu base dependencies...");
            updateConsoleInstallState("Installing Ubuntu base dependencies...", "Ubuntu packages", "Ubuntu bootstrap", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Updating apt...")) {
            advanceLauncherInstallProgress(64, R.string.clawmobile_bootstrapping_ubuntu, "Updating Ubuntu apt indexes...");
            updateConsoleInstallState("Updating Ubuntu apt indexes...", "Ubuntu bootstrap", "Ubuntu apt", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("Configuring PPA repository for Firefox and Thunderbird")) {
            advanceLauncherInstallProgress(58, R.string.clawmobile_bootstrapping_ubuntu, "Running Ubuntu distro post-install hooks...");
            updateConsoleInstallState("Running Ubuntu distro post-install hooks...", "Rootfs configuration", "proot-distro", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("Generating locales")) {
            advanceLauncherInstallProgress(52, R.string.clawmobile_bootstrapping_ubuntu, "Generating Ubuntu locales...");
            updateConsoleInstallState("Generating Ubuntu locales...", "Rootfs configuration", "proot-distro", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Entering Ubuntu and running bootstrap...")) {
            advanceLauncherInstallProgress(48, R.string.clawmobile_bootstrapping_ubuntu, "Entering Ubuntu bootstrap...");
            updateConsoleInstallState("Entering Ubuntu bootstrap...", "Ubuntu bootstrap", "Foreground terminal", "proot login active");
            return;
        }
        if (cleanedTranscript.contains("Extracting rootfs, please wait")) {
            advanceLauncherInstallProgress(42, R.string.clawmobile_installing, "Extracting Ubuntu rootfs...");
            updateConsoleInstallState("Extracting Ubuntu rootfs...", "Ubuntu rootfs", "proot-distro", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("Downloading rootfs archive")) {
            advanceLauncherInstallProgress(38, R.string.clawmobile_installing, "Downloading Ubuntu rootfs archive...");
            updateConsoleInstallState("Downloading Ubuntu rootfs archive...", "Ubuntu rootfs", "proot-distro", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Installing proot Ubuntu")) {
            advanceLauncherInstallProgress(34, R.string.clawmobile_installing, "Installing Ubuntu rootfs...");
            updateConsoleInstallState("Installing Ubuntu rootfs...", "Ubuntu rootfs", "proot-distro", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Installing prerequisites...")) {
            advanceLauncherInstallProgress(22, R.string.clawmobile_installing, "Installing Termux prerequisites...");
            updateConsoleInstallState("Installing Termux prerequisites...", "Termux packages", "pkg install", "Live install transcript");
            return;
        }
        if (cleanedTranscript.contains("[+] Updating Termux packages...")) {
            advanceLauncherInstallProgress(12, R.string.clawmobile_installing, "Updating Termux package indexes...");
            updateConsoleInstallState("Updating Termux package indexes...", "Termux packages", "pkg update", "Live install transcript");
        }
    }

    private void updateOnboardProgressFromTranscript(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) return;

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);

        if (cleanedTranscript.contains("Onboard complete")) {
            mLauncherStatusText.setText(R.string.clawmobile_install_complete);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherProgressText.setText("OpenClaw setup reached completion.");
            updateConsoleReadyState("OpenClaw setup reached completion.");
            return;
        }

        if (cleanedTranscript.contains("openclaw onboard") || cleanedTranscript.contains("Entering Ubuntu and starting OpenClaw onboard")) {
            mLauncherStatusText.setText(R.string.clawmobile_onboarding);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("OpenClaw setup is running in the live shell session...");
            updateConsoleOnboardState("OpenClaw setup is running in the live shell session...", "Non-interactive onboard transcript");
        }
    }

    private void updateAdbPairProgressFromTranscript(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) return;

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);

        if (cleanedTranscript.contains("[adb-connect] Device linked:")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_complete);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherProgressText.setText("Local wireless ADB device linked.");
            updateConsoleReadyState("Local wireless ADB device linked.");
            return;
        }

        if (cleanedTranscript.contains("[adb-pair] Pairing approved for")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_pair_complete);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherProgressText.setText("Wireless ADB pairing approved. Run Connect local device next.");
            updateConsoleReadyState("Wireless ADB pairing approved. Run Connect local device next.");
            return;
        }

        if (cleanedTranscript.contains("[adb-connect] Verifying adb device state")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Verifying wireless ADB device state...");
            updateConsolePairState("Verifying wireless ADB device state...", "ADB device verification");
            return;
        }

        if (cleanedTranscript.contains("[adb-connect] Connecting to")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Connecting local ADB transport...");
            updateConsolePairState("Connecting local ADB transport...", "adb connect");
            return;
        }

        if (cleanedTranscript.contains("[adb-pair] Pairing to")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Approving wireless ADB pairing...");
            updateConsolePairState("Approving wireless ADB pairing...", "adb pair");
            return;
        }

        if (cleanedTranscript.contains("[adb-pair] Starting adb server")
            || cleanedTranscript.contains("[adb-connect] Starting adb server")) {
            mLauncherStatusText.setText(R.string.clawmobile_pairing_running);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Starting adb server...");
            updateConsolePairState("Starting adb server...", "adb start-server");
        }
    }

    private void updateChannelPairProgressFromTranscript(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) return;

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);

        if (cleanedTranscript.contains("[pair] Done.")) {
            mLauncherStatusText.setText(R.string.clawmobile_channel_pairing_complete);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherProgressText.setText("Telegram chat pairing approved.");
            updateConsoleReadyState("Telegram chat pairing approved.");
            return;
        }

        if (cleanedTranscript.contains("[pair] Approving Telegram pairing code:")) {
            mLauncherStatusText.setText(R.string.clawmobile_channel_pairing_running);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Approving Telegram chat pairing...");
            updateConsoleChannelState("Approving Telegram chat pairing...", "openclaw pairing approve telegram");
        }
    }

    private void updateRuntimeProgressFromTranscript(@Nullable String transcript) {
        if (transcript == null || transcript.isEmpty()) return;

        String cleanedTranscript = stripAnsiEscapeSequences(transcript);

        if (cleanedTranscript.contains("listening on ws://127.0.0.1:18789")
            || cleanedTranscript.contains("listening on ws://[::1]:18789")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_running);
            mLauncherProgressBar.setIndeterminate(false);
            mLauncherProgressBar.setProgress(100);
            mLauncherProgressText.setText("OpenClaw gateway is online on loopback.");
            updateConsoleReadyState("OpenClaw gateway is online on loopback.");
            return;
        }

        if (cleanedTranscript.contains("Browser control listening on http://127.0.0.1:18791/")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Browser control is online. Waiting for gateway socket...");
            updateConsoleRuntimeState("Browser control is online. Waiting for gateway socket...", "Runtime startup transcript");
            return;
        }

        if (cleanedTranscript.contains("openclaw plugins install")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Installing or refreshing the mobile plugin...");
            updateConsoleRuntimeState("Installing or refreshing the mobile plugin...", "Plugin setup");
            return;
        }

        if (cleanedTranscript.contains("synced skills")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Syncing workspace seed for daily runtime...");
            updateConsoleRuntimeState("Syncing workspace seed for daily runtime...", "Workspace seed");
            return;
        }

        if (cleanedTranscript.contains("adb selected serial")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Local wireless ADB transport detected.");
            updateConsoleRuntimeState("Local wireless ADB transport detected.", "ADB transport");
            return;
        }

        if (cleanedTranscript.contains("WARNING: no adb device in 'device' state")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_pending);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Gateway is starting, but no local ADB device is linked yet.");
            updateConsoleRuntimeState("Gateway is starting, but no local ADB device is linked yet.", "ADB transport missing");
            return;
        }

        if (cleanedTranscript.contains("[clawbot] Starting OpenClaw Gateway")) {
            mLauncherStatusText.setText(R.string.clawmobile_runtime_starting);
            mLauncherProgressBar.setIndeterminate(true);
            mLauncherProgressText.setText("Starting OpenClaw gateway inside Ubuntu...");
            updateConsoleRuntimeState("Starting OpenClaw gateway inside Ubuntu...", "Gateway launch");
        }
    }

    private void advanceLauncherInstallProgress(int progress, int statusResId, @NonNull String progressText) {
        mLauncherProgressBar.setIndeterminate(false);
        if (progress > mLauncherProgressBar.getProgress()) {
            mLauncherProgressBar.setProgress(progress);
        }
        mLauncherStatusText.setText(statusResId);
        mLauncherProgressText.setText(progressText);
    }

    @Nullable
    private Integer parseInstallMarkerExitCode(@Nullable String transcript, @NonNull String marker) {
        if (transcript == null || transcript.isEmpty()) return null;

        int markerIndex = transcript.lastIndexOf(marker);
        if (markerIndex < 0) return null;

        int valueStart = markerIndex + marker.length();
        int valueEnd = valueStart;
        while (valueEnd < transcript.length() && Character.isDigit(transcript.charAt(valueEnd))) {
            valueEnd++;
        }

        if (valueEnd == valueStart) return null;

        try {
            return Integer.parseInt(transcript.substring(valueStart, valueEnd));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private String getLastNonEmptyLine(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;

        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = stripAnsiEscapeSequences(lines[i]).trim();
            if (!line.isEmpty()) return line;
        }

        return null;
    }

    @NonNull
    private String stripAnsiEscapeSequences(@NonNull String text) {
        return text
            .replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
            .replaceAll("\\u001B\\([A-Za-z0-9]", "");
    }

    private void onInstallCommandInjected() {
        mIsInstallRunning = false;
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_installing);
        mLauncherProgressText.setText("Installer command pasted into terminal.");
        updateLauncherTerminalTail(null, "Installer command pasted into terminal.");
        mLauncherInstallButton.setEnabled(true);
        updatePrimaryLauncherButtonState();
    }

    private void onInstallComplete(@NonNull String progressText) {
        clearInstallSessionReference();
        mIsInstallRunning = false;
        mIsOnboardingRunning = false;
        mIsAdbPairingRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mIsClawMobilePreparing = false;
        mIsClawMobilePrepared = true;
        mHasClawMobileRuntimeInstalled = true;
        mPendingInstallAfterPreparation = false;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_install_complete);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        switchToLauncherView();
        updateConsoleReadyState(progressText);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onInstallFailed(String reason) {
        clearInstallSessionReference();
        mIsInstallRunning = false;
        mIsOnboardingRunning = false;
        mIsAdbPairingRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mIsClawMobilePreparing = false;
        mIsClawMobilePrepared = isClawMobilePreparedOnDisk();
        mHasClawMobileRuntimeInstalled = isClawMobileRuntimeInstalledOnDisk();
        mLauncherStatusText.setText(R.string.clawmobile_install_failed);
        mLauncherProgressText.setText(reason);
        mLauncherProgressBar.setProgress(0);
        updateLauncherTerminalTail(null, reason);
        updateConsoleFailureState(reason);

        // Re-enable button to allow retry
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(mHasClawMobileRuntimeInstalled ? R.string.clawmobile_onboard_start : R.string.clawmobile_install_start);
        setOnboardingSectionVisible(mHasClawMobileRuntimeInstalled);
        setOnboardingUiEnabled(mHasClawMobileRuntimeInstalled);
        setPairingSectionVisible(mHasClawMobileRuntimeInstalled);
        setPairingUiEnabled(mHasClawMobileRuntimeInstalled);
        setChannelPairingSectionVisible(mHasClawMobileRuntimeInstalled);
        setChannelPairingUiEnabled(mHasClawMobileRuntimeInstalled);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onOnboardReady(@NonNull String progressText) {
        clearInstallSessionReference();
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsAdbPairingRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mIsClawMobilePreparing = false;
        mIsClawMobilePrepared = true;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_install_complete);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        switchToLauncherView();
        updateConsoleReadyState(progressText);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onOnboardFailed(@NonNull String reason) {
        clearInstallSessionReference();
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsAdbPairingRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherStatusText.setText(R.string.clawmobile_install_failed);
        mLauncherProgressText.setText(reason);
        mLauncherInstallButton.setEnabled(true);
        updateLauncherTerminalTail(null, reason);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        switchToLauncherView();
        updateConsoleFailureState(reason);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onAdbPairingReady(@NonNull String progressText, boolean connected) {
        clearInstallSessionReference();
        mIsAdbPairingRunning = false;
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(connected
            ? R.string.clawmobile_pairing_complete
            : R.string.clawmobile_pairing_pair_complete);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(connected ? CLAWMOBILE_TAB_HEALTH : CLAWMOBILE_TAB_SETUP);
        switchToLauncherView();
        updateConsoleReadyState(progressText);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onAdbPairingFailed(@NonNull String reason) {
        clearInstallSessionReference();
        mIsAdbPairingRunning = false;
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsChannelPairingRunning = false;
        mIsRuntimeLaunchRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherStatusText.setText(R.string.clawmobile_install_failed);
        mLauncherProgressText.setText(reason);
        updateLauncherTerminalTail(null, reason);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        switchToLauncherView();
        updateConsoleFailureState(reason);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onChannelPairingReady(@NonNull String progressText) {
        clearInstallSessionReference();
        mIsChannelPairingRunning = false;
        mIsAdbPairingRunning = false;
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsRuntimeLaunchRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_channel_pairing_complete);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        switchToLauncherView();
        updateConsoleReadyState(progressText);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onChannelPairingFailed(@NonNull String reason) {
        clearInstallSessionReference();
        mIsChannelPairingRunning = false;
        mIsAdbPairingRunning = false;
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsRuntimeLaunchRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherStatusText.setText(R.string.clawmobile_install_failed);
        mLauncherProgressText.setText(reason);
        updateLauncherTerminalTail(null, reason);
        mLauncherInstallButton.setEnabled(true);
        ((com.google.android.material.button.MaterialButton) mLauncherInstallButton)
            .setText(R.string.clawmobile_onboard_start);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_CHANNELS);
        switchToLauncherView();
        updateConsoleFailureState(reason);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onRuntimeReady(@NonNull String progressText) {
        clearInstallSessionReference();
        mIsRuntimeLaunchRunning = false;
        mIsOnboardingRunning = false;
        mIsInstallRunning = false;
        mIsAdbPairingRunning = false;
        mIsChannelPairingRunning = false;
        mHasClawMobileRuntimeInstalled = true;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(100);
        mLauncherStatusText.setText(R.string.clawmobile_runtime_running);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        ((MaterialButton) mLauncherRunButton).setText(R.string.clawmobile_run_restart);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_OPERATE);
        switchToLauncherView();
        updateConsoleReadyState(progressText);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }

    private void onRuntimePending(@NonNull String progressText) {
        clearInstallSessionReference();
        mIsRuntimeLaunchRunning = false;
        mIsChannelPairingRunning = false;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherProgressBar.setProgress(90);
        mLauncherStatusText.setText(R.string.clawmobile_runtime_pending);
        mLauncherProgressText.setText(progressText);
        updateLauncherTerminalTail(null, progressText);
        setOnboardingSectionVisible(true);
        setPairingSectionVisible(true);
        setChannelPairingSectionVisible(true);
        setOnboardingUiEnabled(true);
        setPairingUiEnabled(true);
        setChannelPairingUiEnabled(true);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        selectLauncherTab(CLAWMOBILE_TAB_OPERATE);
        switchToLauncherView();
        updateConsoleRuntimeState(progressText, "Health snapshot recommended");
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(true);
    }

    private void onRuntimeFailed(@NonNull String reason) {
        clearInstallSessionReference();
        mIsRuntimeLaunchRunning = false;
        mIsChannelPairingRunning = false;
        mLauncherProgressBar.setIndeterminate(false);
        mLauncherStatusText.setText(R.string.clawmobile_install_failed);
        mLauncherProgressText.setText(reason);
        updateLauncherTerminalTail(null, reason);
        setOnboardingSectionVisible(mHasClawMobileRuntimeInstalled);
        setPairingSectionVisible(mHasClawMobileRuntimeInstalled);
        setChannelPairingSectionVisible(mHasClawMobileRuntimeInstalled);
        setOnboardingUiEnabled(mHasClawMobileRuntimeInstalled);
        setPairingUiEnabled(mHasClawMobileRuntimeInstalled);
        setChannelPairingUiEnabled(mHasClawMobileRuntimeInstalled);
        setOperateUiEnabled(true);
        setHealthUiEnabled(true);
        ((MaterialButton) mLauncherRunButton).setText(R.string.clawmobile_run_start);
        switchToLauncherView();
        updateConsoleFailureState(reason);
        updatePrimaryLauncherButtonState();
        refreshLauncherHealth(false);
    }


    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else if (!mIsLauncherVisible) {
            // If in terminal view, go back to launcher instead of exiting
            switchToLauncherView();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();


        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
