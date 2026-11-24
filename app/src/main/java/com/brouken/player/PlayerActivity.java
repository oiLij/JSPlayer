package com.brouken.player;

import static android.content.pm.PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import android.widget.Button;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import android.view.Gravity;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.TimeBar;

import com.brouken.player.dtpv.DoubleTapPlayerView;
import com.brouken.player.dtpv.youtube.YouTubeOverlay;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class PlayerActivity extends Activity {

    private boolean sdhAppliedForCurrentItem = false;
    private long preSkipPositionMs = -1;
    private boolean resumeDialogShownForCurrentItem = false;
    private boolean isResumeDialogOpen = false;
    private boolean suppressResumeDialogOnce = false;
    private android.app.AlertDialog activeResumeDialog = null;
    
    private List<ChapterHelper.ChapterItem> activeChapters = new ArrayList<>();
    private android.widget.Button btnSkipChapter;
    private int lastShownSkipChapterIndex = -1;
    private final android.os.Handler skipHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideSkipRunnable = () -> {
        if (btnSkipChapter != null) btnSkipChapter.setVisibility(View.GONE);
    };
    private final Runnable chapterMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            checkChapterSkip();
            skipHandler.postDelayed(this, 500);
        }
    };
    
    private PlayerListener playerListener;
    private BroadcastReceiver mReceiver;
    private AudioManager mAudioManager;
    private MediaSession mediaSession;
    private DefaultTrackSelector trackSelector;
    public static LoudnessEnhancer loudnessEnhancer;

    public CustomPlayerView playerView;
    public static ExoPlayer player;
    private YouTubeOverlay youTubeOverlay;

    private Object mPictureInPictureParamsBuilder;

    public Prefs mPrefs;
    public BrightnessControl mBrightnessControl;
    public static boolean haveMedia;
    private boolean videoLoading;
    public static boolean controllerVisible;
    public static boolean controllerVisibleFully;
    public static Snackbar snackbar;
    private ExoPlaybackException errorToShow;
    public static int boostLevel = 0;
    private boolean isScaling = false;
    private boolean isScaleStarting = false;
    private float scaleFactor = 1.0f;

    private static final int REQUEST_CHOOSER_VIDEO = 1;
    private static final int REQUEST_CHOOSER_SUBTITLE = 2;
    private static final int REQUEST_CHOOSER_SCOPE_DIR = 10;
    private static final int REQUEST_CHOOSER_VIDEO_MEDIASTORE = 20;
    private static final int REQUEST_CHOOSER_SUBTITLE_MEDIASTORE = 21;
    private static final int REQUEST_SETTINGS = 100;
    private static final int REQUEST_SYSTEM_CAPTIONS = 200;
    public static final int CONTROLLER_TIMEOUT = 3500;
    private static final String ACTION_MEDIA_CONTROL = "media_control";
    private static final String EXTRA_CONTROL_TYPE = "control_type";
    private static final int REQUEST_PLAY = 1;
    private static final int REQUEST_PAUSE = 2;
    private static final int CONTROL_TYPE_PLAY = 1;
    private static final int CONTROL_TYPE_PAUSE = 2;

    private CoordinatorLayout coordinatorLayout;
    private TextView titleView;
    private ImageButton buttonOpen;
    private ImageButton buttonPiP;
    private ImageButton buttonAspectRatio;
    private ImageButton buttonRotation;
    private TextView buttonPassthrough;
    private boolean passthroughEnabled = true;
    private TextView buttonSync;
    private long audioDelayMs = 0;
    private long subtitleDelayMs = 0;
    private ImageButton exoSettings;
    private ImageButton exoPlayPause;
    private ProgressBar loadingProgressBar;
    private PlayerControlView controlView;
    private CustomDefaultTimeBar timeBar;

    private boolean restoreOrientationLock;
    private boolean restorePlayState;
    private boolean restorePlayStateAllowed;
    private boolean play;
    private float subtitlesScale;
    private boolean isScrubbing;
    private boolean scrubbingNoticeable;
    private long scrubbingStart;
    public boolean frameRendered;
    private boolean alive;
    public static boolean focusPlay = false;
    private Uri nextUri;
    private static boolean isTvBox;
    public static boolean locked = false;
    private Thread nextUriThread;
    public Thread frameRateSwitchThread;

    public static boolean restoreControllerTimeout = false;
    public static boolean shortControllerTimeout = false;

    final Rational rationalLimitWide = new Rational(239, 100);
    final Rational rationalLimitTall = new Rational(100, 239);

    static final String API_POSITION = "position";
    static final String API_DURATION = "duration";
    static final String API_RETURN_RESULT = "return_result";
    static final String API_SUBS = "subs";
    static final String API_SUBS_ENABLE = "subs.enable";
    static final String API_SUBS_NAME = "subs.name";
    static final String API_TITLE = "title";
    static final String API_END_BY = "end_by";
    boolean apiAccess;
    boolean apiAccessPartial;
    String apiTitle;
    List<MediaItem.SubtitleConfiguration> apiSubs = new ArrayList<>();
    boolean intentReturnResult;
    boolean playbackFinished;

    DisplayManager displayManager;
    DisplayManager.DisplayListener displayListener;
    SubtitleFinder subtitleFinder;

    Runnable barsHider = () -> {
        if (playerView != null && !controllerVisible) {
            Utils.toggleSystemUi(PlayerActivity.this, playerView, false);
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Rotate ASAP, before super/inflating to avoid glitches with activity launch animation
        mPrefs = new Prefs(this);
        Utils.setOrientation(this, mPrefs.orientation);

        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT == 28 && Build.MANUFACTURER.equalsIgnoreCase("xiaomi") &&
                (Build.DEVICE.equalsIgnoreCase("oneday") || Build.DEVICE.equalsIgnoreCase("once"))) {
            setContentView(R.layout.activity_player_textureview);
        } else {
            setContentView(R.layout.activity_player);
        }

        if (Build.VERSION.SDK_INT >= 31) {
            Window window = getWindow();
            if (window != null) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController windowInsetsController = window.getInsetsController();
                if (windowInsetsController != null) {
                    // On Android 12 BEHAVIOR_DEFAULT allows system gestures without visible system bars
                    windowInsetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            }
        }

        isTvBox = Utils.isTvBox(this);

        if (isTvBox) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }

        final Intent launchIntent = getIntent();
        final String action = launchIntent.getAction();
        final String type = launchIntent.getType();

        if ("com.brouken.player.action.SHORTCUT_VIDEOS".equals(action)) {
            openFile(Utils.getMoviesFolderUri());
        } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String text = launchIntent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                final Uri parsedUri = Uri.parse(text);
                if (parsedUri.isAbsolute()) {
                    mPrefs.updateMedia(this, parsedUri, null);
                    focusPlay = true;
                }
            }
        } else if (launchIntent.getData() != null) {
            resetApiAccess();
            final Uri uri = launchIntent.getData();
            if (SubtitleUtils.isSubtitle(uri, type)) {
                handleSubtitles(uri);
            } else {
                Bundle bundle = launchIntent.getExtras();
                if (bundle != null) {
                    apiAccess = bundle.containsKey(API_POSITION) || bundle.containsKey(API_RETURN_RESULT)
                            || bundle.containsKey(API_SUBS) || bundle.containsKey(API_SUBS_ENABLE);
                    if (apiAccess) {
                        mPrefs.setPersistent(false);
                    } else if (bundle.containsKey(API_TITLE)) {
                        apiAccessPartial = true;
                    }
                    apiTitle = bundle.getString(API_TITLE);
                }

                mPrefs.updateMedia(this, uri, type);

                if (bundle != null) {
                    Uri defaultSub = null;
                    Parcelable[] subsEnable = bundle.getParcelableArray(API_SUBS_ENABLE);
                    if (subsEnable != null && subsEnable.length > 0) {
                        defaultSub = (Uri) subsEnable[0];
                    }

                    Parcelable[] subs = bundle.getParcelableArray(API_SUBS);
                    String[] subsName = bundle.getStringArray(API_SUBS_NAME);
                    if (subs != null && subs.length > 0) {
                        for (int i = 0; i < subs.length; i++) {
                            Uri sub = (Uri) subs[i];
                            String name = null;
                            if (subsName != null && subsName.length > i) {
                                name = subsName[i];
                            }
                            apiSubs.add(SubtitleUtils.buildSubtitle(this, sub, name, sub.equals(defaultSub)));
                        }
                    }
                }

                if (apiSubs.isEmpty()) {
                    searchSubtitles();
                }

                if (bundle != null) {
                    intentReturnResult = bundle.getBoolean(API_RETURN_RESULT);

                    if (bundle.containsKey(API_POSITION)) {
                        mPrefs.updatePosition((long) bundle.getInt(API_POSITION));
                    }
                }
            }
            focusPlay = true;
        }

        coordinatorLayout = findViewById(R.id.coordinatorLayout);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        playerView = findViewById(R.id.video_view);
        playerView.setShowPreviousButton(true);
        playerView.setShowNextButton(true);
        exoPlayPause = findViewById(R.id.exo_play_pause);
        loadingProgressBar = findViewById(R.id.loading);

        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowRewindButton(false);

        playerView.setRepeatToggleModes(Player.REPEAT_MODE_ONE);

        playerView.setControllerHideOnTouch(false);
        playerView.setControllerAutoShow(true);

        ((DoubleTapPlayerView)playerView).setDoubleTapEnabled(false);

        timeBar = playerView.findViewById(R.id.exo_progress);
        timeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                if (player == null) {
                    return;
                }
                restorePlayState = player.isPlaying();
                if (restorePlayState) {
                    player.pause();
                }
                scrubbingNoticeable = false;
                isScrubbing = true;
                frameRendered = true;
                playerView.setControllerShowTimeoutMs(-1);
                scrubbingStart = player.getCurrentPosition();
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
                reportScrubbing(position);
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                reportScrubbing(position);
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                playerView.setCustomErrorMessage(null);
                isScrubbing = false;
                if (restorePlayState) {
                    restorePlayState = false;
                    playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
                    if (player != null) {
                        player.setPlayWhenReady(true);
                    }
                }
            }
        });

        buttonOpen = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonOpen.setImageResource(R.drawable.ic_folder_open_24dp);
        buttonOpen.setId(View.generateViewId());
        buttonOpen.setContentDescription(getString(R.string.button_open));

        buttonOpen.setOnClickListener(view -> openFile(mPrefs.mediaUri));

        buttonOpen.setOnLongClickListener(view -> {
            if (!isTvBox && mPrefs.askScope) {
                askForScope(true, false);
            } else {
                loadSubtitleFile(mPrefs.mediaUri);
            }
            return true;
        });

        if (Utils.isPiPSupported(this)) {
            // TODO: Android 12 improvements:
            // https://developer.android.com/about/versions/12/features/pip-improvements
            mPictureInPictureParamsBuilder = new PictureInPictureParams.Builder();
            boolean success = updatePictureInPictureActions(R.drawable.ic_play_arrow_24dp, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY);

            if (success) {
                buttonPiP = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
                buttonPiP.setContentDescription(getString(R.string.button_pip));
                buttonPiP.setImageResource(R.drawable.ic_picture_in_picture_alt_24dp);

                buttonPiP.setOnClickListener(view -> enterPiP());
            }
        }

        buttonAspectRatio = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonAspectRatio.setId(Integer.MAX_VALUE - 100);
        buttonAspectRatio.setContentDescription(getString(R.string.button_crop));
        updatebuttonAspectRatioIcon();
        buttonAspectRatio.setOnClickListener(view -> {
            playerView.setScale(1.f);
            if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                Utils.showText(playerView, getString(R.string.video_resize_crop));
            } else {
                // Default mode
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                Utils.showText(playerView, getString(R.string.video_resize_fit));
            }
            updatebuttonAspectRatioIcon();
            resetHideCallbacks();
        });
        if (isTvBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            buttonAspectRatio.setOnLongClickListener(v -> {
                scaleStart();
                updatebuttonAspectRatioIcon();
                return true;
            });
        }
        buttonRotation = new ImageButton(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonRotation.setContentDescription(getString(R.string.button_rotate));
        updateButtonRotation();
        buttonRotation.setOnClickListener(view -> {
            mPrefs.orientation = Utils.getNextOrientation(mPrefs.orientation);
            Utils.setOrientation(PlayerActivity.this, mPrefs.orientation);
            updateButtonRotation();
            Utils.showText(playerView, getString(mPrefs.orientation.description), 2500);
            resetHideCallbacks();
        });

        final int titleViewPaddingHorizontal = Utils.dpToPx(14);
        final int titleViewPaddingVertical = getResources().getDimensionPixelOffset(R.dimen.exo_styled_bottom_bar_time_padding);
        FrameLayout centerView = playerView.findViewById(R.id.exo_controls_background);
        titleView = new TextView(this);
        titleView.setBackgroundResource(R.color.ui_controls_background);
        titleView.setTextColor(Color.WHITE);
        titleView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleView.setPadding(titleViewPaddingHorizontal, titleViewPaddingVertical, titleViewPaddingHorizontal, titleViewPaddingVertical);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setVisibility(View.GONE);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        centerView.addView(titleView);

        titleView.setOnLongClickListener(view -> {
            // Prevent FileUriExposedException
            if (mPrefs.mediaUri != null && ContentResolver.SCHEME_FILE.equals(mPrefs.mediaUri.getScheme())) {
                return false;
            }

            final Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, mPrefs.mediaUri);
            if (mPrefs.mediaType == null)
                shareIntent.setType("video/*");
            else
                shareIntent.setType(mPrefs.mediaType);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Start without intent chooser to allow any target to be set as default
            startActivity(shareIntent);

            return true;
        });

        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        controlView = playerView.findViewById(R.id.exo_controller);
        controlView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (windowInsets != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    boolean visibleBars = windowInsets.isVisible(WindowInsets.Type.statusBars());
                    if (visibleBars && !controllerVisible) {
                        playerView.postDelayed(barsHider, 2500);
                    } else {
                        playerView.removeCallbacks(barsHider);
                    }
                }

                int insetLeft = windowInsets.getSystemWindowInsetLeft();
                int insetRight = windowInsets.getSystemWindowInsetRight();

                int paddingLeft = 0;
                int marginLeft = insetLeft;

                int paddingRight = 0;
                int marginRight = insetRight;

                if (Build.VERSION.SDK_INT >= 28 && windowInsets.getDisplayCutout() != null) {
                    if (windowInsets.getDisplayCutout().getSafeInsetLeft() == insetLeft) {
                        paddingLeft = insetLeft;
                        marginLeft = 0;
                    }
                    if (windowInsets.getDisplayCutout().getSafeInsetRight() == insetRight) {
                        paddingRight = insetRight;
                        marginRight = 0;
                    }
                }

                int bottomBarPaddingBottom = 0;
                int progressBarMarginBottom = 0;

                if (Build.VERSION.SDK_INT >= 35) {
                    final int left = windowInsets.getInsets(WindowInsets.Type.navigationBars()).left;
                    final int right = windowInsets.getInsets(WindowInsets.Type.navigationBars()).right;

                    final View exoTop = findViewById(R.id.exo_top);
                    exoTop.getLayoutParams().height = windowInsets.getSystemWindowInsetTop();
                    Utils.setViewMargins(exoTop, left, 0, right, 0);

                    final FrameLayout exoBottomBar = findViewById(R.id.exo_bottom_bar);
                    ViewGroup.LayoutParams params = exoBottomBar.getLayoutParams();
                    params.height = getResources().getDimensionPixelSize(R.dimen.exo_styled_bottom_bar_height) + windowInsets.getSystemWindowInsetBottom();
                    exoBottomBar.setLayoutParams(params);

                    findViewById(R.id.exo_left).getLayoutParams().width = left;
                    findViewById(R.id.exo_right).getLayoutParams().width = right;

                    bottomBarPaddingBottom = windowInsets.getSystemWindowInsetBottom();
                    progressBarMarginBottom = windowInsets.getSystemWindowInsetBottom();
                } else {
                    view.setPadding(0, windowInsets.getSystemWindowInsetTop(),0, windowInsets.getSystemWindowInsetBottom());
                }

                Utils.setViewParams(titleView, paddingLeft + titleViewPaddingHorizontal, titleViewPaddingVertical, paddingRight + titleViewPaddingHorizontal, titleViewPaddingVertical,
                        marginLeft, windowInsets.getSystemWindowInsetTop(), marginRight, 0);

                Utils.setViewParams(findViewById(R.id.exo_bottom_bar), paddingLeft, 0, paddingRight, bottomBarPaddingBottom,
                        marginLeft, 0, marginRight, 0);

                Utils.setViewParams(findViewById(R.id.exo_progress), windowInsets.getSystemWindowInsetLeft(), 0, windowInsets.getSystemWindowInsetRight(), 0,
                        0, 0, 0, getResources().getDimensionPixelSize(R.dimen.exo_styled_progress_margin_bottom) + progressBarMarginBottom);

                Utils.setViewMargins(findViewById(R.id.exo_error_message), 0, windowInsets.getSystemWindowInsetTop() / 2, 0, getResources().getDimensionPixelSize(R.dimen.exo_error_message_margin_bottom) + windowInsets.getSystemWindowInsetBottom() / 2);

                windowInsets.consumeSystemWindowInsets();
            }
            return windowInsets;
        });
        timeBar.setAdMarkerColor(Color.argb(0x00, 0xFF, 0xFF, 0xFF));
        timeBar.setPlayedAdMarkerColor(Color.argb(0x98, 0xFF, 0xFF, 0xFF));

        try {
            CustomDefaultTrackNameProvider customDefaultTrackNameProvider = new CustomDefaultTrackNameProvider(getResources());
            final Field field = PlayerControlView.class.getDeclaredField("trackNameProvider");
            field.setAccessible(true);
            field.set(controlView, customDefaultTrackNameProvider);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        findViewById(R.id.delete).setOnClickListener(view -> askDeleteMedia());

        findViewById(R.id.next).setOnClickListener(view -> {
            if (!isTvBox && mPrefs.askScope) {
                askForScope(false, true);
            } else {
                skipToNext();
            }
        });

        exoPlayPause.setOnClickListener(view -> dispatchPlayPause());

        // Prevent double tap actions in controller
        findViewById(R.id.exo_bottom_bar).setOnTouchListener((v, event) -> true);
        //titleView.setOnTouchListener((v, event) -> true);

        playerListener = new PlayerListener();

        mBrightnessControl = new BrightnessControl(this);
        if (mPrefs.brightness >= 0) {
            mBrightnessControl.currentBrightnessLevel = mPrefs.brightness;
            mBrightnessControl.setScreenBrightness(mBrightnessControl.levelToBrightness(mBrightnessControl.currentBrightnessLevel));
        }
        playerView.setBrightnessControl(mBrightnessControl);

        final LinearLayout exoBasicControls = playerView.findViewById(R.id.exo_basic_controls);
        final ImageButton exoSubtitle = exoBasicControls.findViewById(R.id.exo_subtitle);
        exoBasicControls.removeView(exoSubtitle);

        exoSettings = exoBasicControls.findViewById(R.id.exo_settings);
        exoBasicControls.removeView(exoSettings);
        final ImageButton exoRepeat = exoBasicControls.findViewById(R.id.exo_repeat_toggle);
        exoBasicControls.removeView(exoRepeat);
        //exoBasicControls.setVisibility(View.GONE);

        exoSettings.setOnLongClickListener(view -> {
            //askForScope(false, false);
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_SETTINGS);
            return true;
        });

        exoSubtitle.setOnLongClickListener(v -> {
            enableRotation();
            safelyStartActivityForResult(new Intent(Settings.ACTION_CAPTIONING_SETTINGS), REQUEST_SYSTEM_CAPTIONS);
            return true;
        });

        updateButtons(false);

        buttonPassthrough = new TextView(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonPassthrough.setId(View.generateViewId());
        buttonPassthrough.setGravity(Gravity.CENTER);
        buttonPassthrough.setTextColor(Color.WHITE);
        buttonPassthrough.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        buttonPassthrough.setTypeface(Typeface.DEFAULT_BOLD);
        buttonPassthrough.setFocusable(true);
        buttonPassthrough.setClickable(true);
        buttonPassthrough.setText("PT");
        buttonPassthrough.setOnClickListener(view -> {
            passthroughEnabled = !passthroughEnabled;
            buttonPassthrough.setText(passthroughEnabled ? "PT" : "PCM");
            Utils.showText(playerView, passthroughEnabled ? "Audio: Passthrough" : "Audio: PCM", 1500);
            if (haveMedia && player != null) {
                String curPath = (mPrefs != null && mPrefs.mediaUri != null)
                        ? mPrefs.mediaUri.toString().toLowerCase(java.util.Locale.US) : "";
                boolean isPcmOnlyAudio = curPath.endsWith(".flac") || curPath.endsWith(".mp3")
                        || curPath.endsWith(".m4a") || curPath.endsWith(".aac")
                        || curPath.endsWith(".wav") || curPath.endsWith(".ogg") || curPath.endsWith(".opus");

                if (!isPcmOnlyAudio) {
                    long resumePos = player != null ? player.getCurrentPosition() : 0;
                    suppressResumeDialogOnce = true;
                    releasePlayer(true);
                    initializePlayer();
                    if (player != null && resumePos > 0) {
                        player.seekTo(resumePos);
                    }
                }
            }
            resetHideCallbacks();
        });

        buttonSync = new TextView(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonSync.setId(View.generateViewId());
        buttonSync.setGravity(Gravity.CENTER);
        buttonSync.setTextColor(Color.WHITE);
        buttonSync.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        buttonSync.setTypeface(Typeface.DEFAULT_BOLD);
        buttonSync.setFocusable(true);
        buttonSync.setClickable(true);
        buttonSync.setText("Sync");
        buttonSync.setOnClickListener(view -> showAudioDelayDialog());

        TextView buttonSubExtra = new TextView(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonSubExtra.setId(View.generateViewId());
        buttonSubExtra.setGravity(Gravity.CENTER);
        buttonSubExtra.setTextColor(Color.WHITE);
        buttonSubExtra.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        buttonSubExtra.setTypeface(Typeface.DEFAULT_BOLD);
        buttonSubExtra.setFocusable(true);
        buttonSubExtra.setClickable(true);
        buttonSubExtra.setText("Sub+");
        buttonSubExtra.setOnClickListener(v -> showSubtitleSourceDialog());

        TextView buttonChapters = new TextView(this, null, 0, R.style.ExoStyledControls_Button_Bottom);
        buttonChapters.setId(View.generateViewId());
        buttonChapters.setGravity(Gravity.CENTER);
        buttonChapters.setTextColor(Color.WHITE);
        buttonChapters.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        buttonChapters.setTypeface(Typeface.DEFAULT_BOLD);
        buttonChapters.setFocusable(true);
        buttonChapters.setClickable(true);
        buttonChapters.setText("Ch");
        buttonChapters.setOnClickListener(v -> showChaptersDialog());

        btnSkipChapter = new android.widget.Button(this);
        btnSkipChapter.setVisibility(View.GONE);
        btnSkipChapter.setTextColor(android.graphics.Color.WHITE);
        btnSkipChapter.setBackgroundColor(android.graphics.Color.parseColor("#E61E88E5"));
        btnSkipChapter.setPadding(32, 18, 32, 18);
        btnSkipChapter.setFocusable(true);
        btnSkipChapter.setElevation(100f);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.END);
        lp.setMargins(0, 0, 48, 150);
        addContentView(btnSkipChapter, lp);
        btnSkipChapter.bringToFront();
        
        final HorizontalScrollView horizontalScrollView = (HorizontalScrollView) getLayoutInflater().inflate(R.layout.controls, null);
        final LinearLayout controls = horizontalScrollView.findViewById(R.id.controls);

        controls.addView(buttonOpen);
        controls.addView(exoSubtitle);
        controls.addView(buttonPassthrough);
        controls.addView(buttonSync);
        controls.addView(buttonSubExtra);
        controls.addView(buttonChapters);
        controls.addView(buttonAspectRatio);
        if (Utils.isPiPSupported(this) && buttonPiP != null) {
            controls.addView(buttonPiP);
        }
        if (mPrefs.repeatToggle) {
            controls.addView(exoRepeat);
        }
        if (!isTvBox) {
            controls.addView(buttonRotation);
        }
        controls.addView(exoSettings);

        exoBasicControls.addView(horizontalScrollView);

        if (Build.VERSION.SDK_INT > 23) {
            horizontalScrollView.setOnScrollChangeListener((view, i, i1, i2, i3) -> resetHideCallbacks());
        }

        playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                controllerVisible = visibility == View.VISIBLE;
                controllerVisibleFully = playerView.isControllerFullyVisible();

                if (PlayerActivity.restoreControllerTimeout) {
                    restoreControllerTimeout = false;
                    if (player == null || !player.isPlaying()) {
                        playerView.setControllerShowTimeoutMs(-1);
                    } else {
                        playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
                    }
                }

                // https://developer.android.com/training/system-ui/immersive
                Utils.toggleSystemUi(PlayerActivity.this, playerView, visibility == View.VISIBLE);
                if (visibility == View.VISIBLE) {
                    focusPlayPauseButton();
                    playerView.post(this::focusPlayPauseButton);
                    playerView.postDelayed(this::focusPlayPauseButton, 260);
                }

                if (controllerVisible && playerView.isControllerFullyVisible()) {
                    if (mPrefs.firstRun) {
                        TapTargetView.showFor(PlayerActivity.this,
                                TapTarget.forView(buttonOpen, getString(R.string.onboarding_open_title), getString(R.string.onboarding_open_description))
                                        .outerCircleColor(R.color.green)
                                        .targetCircleColor(R.color.white)
                                        .titleTextSize(22)
                                        .titleTextColor(R.color.white)
                                        .descriptionTextSize(14)
                                        .cancelable(true),
                                new TapTargetView.Listener() {
                                    @Override
                                    public void onTargetClick(TapTargetView view) {
                                        super.onTargetClick(view);
                                        buttonOpen.performClick();
                                    }
                                });
                        // TODO: Explain gestures?
                        //  "Use vertical and horizontal gestures to change brightness, volume and seek in video"
                        mPrefs.markFirstRun();
                    }
                    if (errorToShow != null) {
                        showError(errorToShow);
                        errorToShow = null;
                    }
                }
            }
        });

        youTubeOverlay = findViewById(R.id.youtube_overlay);
        youTubeOverlay.performListener(new YouTubeOverlay.PerformListener() {
            @Override
            public void onAnimationStart() {
                youTubeOverlay.setAlpha(1.0f);
                youTubeOverlay.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd() {
                youTubeOverlay.animate()
                        .alpha(0.0f)
                        .setDuration(300)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                youTubeOverlay.setVisibility(View.GONE);
                                youTubeOverlay.setAlpha(1.0f);
                            }
                        });
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (useMediaStore()) {
                Utils.scanMediaStorage(this);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        alive = true;
        if (!(isTvBox && Build.VERSION.SDK_INT >= 31)) {
            updateSubtitleStyle(this);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
            Utils.toggleSystemUi(this, playerView, true);
        }
        initializePlayer();
        updateButtonRotation();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyPlaybackControlsPosition();
        restorePlayStateAllowed = true;
        if (isTvBox && Build.VERSION.SDK_INT >= 31) {
            updateSubtitleStyle(this);
        }
    }

    @Override
    protected void onPause() {
        saveVideoResumePosition();
        super.onPause();
        savePlayer();
    }

    @Override
    public void onStop() {
        super.onStop();
        alive = false;
        if (Build.VERSION.SDK_INT >= 31) {
            playerView.removeCallbacks(barsHider);
        }
        playerView.setCustomErrorMessage(null);
        if (player != null && mPrefs != null && mPrefs.mediaUri != null
                && isAudioUri(mPrefs.mediaUri) && player.isPlaying()) {
            skipHandler.removeCallbacks(chapterMonitorRunnable);
            skipHandler.removeCallbacks(hideSkipRunnable);
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            return;
        }
        releasePlayer(false);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        restorePlayStateAllowed = false;
        super.onBackPressed();
    }

    @Override
    public void finish() {
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
            if (!playbackFinished) {
                if (player != null) {
                    long duration = player.getDuration();
                    if (duration != C.TIME_UNSET) {
                        intent.putExtra(API_DURATION, (int) player.getDuration());
                    }
                    if (player.isCurrentMediaItemSeekable()) {
                        if (mPrefs.persistentMode) {
                            intent.putExtra(API_POSITION, (int) mPrefs.nonPersitentPosition);
                        } else {
                            intent.putExtra(API_POSITION, (int) player.getCurrentPosition());
                        }
                    }
                }
            }
            setResult(Activity.RESULT_OK, intent);
        }

        super.finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent != null) {
            final String action = intent.getAction();
            final String type = intent.getType();
            final Uri uri = intent.getData();

            if (Intent.ACTION_VIEW.equals(action) && uri != null) {
                if (SubtitleUtils.isSubtitle(uri, type)) {
                    handleSubtitles(uri);
                } else {
                    mPrefs.updateMedia(this, uri, type);
                    searchSubtitles();
                }
                focusPlay = true;
                initializePlayer();
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) {
                    final Uri parsedUri = Uri.parse(text);
                    if (parsedUri.isAbsolute()) {
                        mPrefs.updateMedia(this, parsedUri, null);
                        focusPlay = true;
                        initializePlayer();
                    }
                }
            }
        }
    }

    @SuppressLint("GestureBackNavigation")
    private View findVisibleViewById(View root, int id) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root.getId() == id) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findVisibleViewById(vg.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void focusPlayPauseButton() {
        View target = findVisibleViewById(playerView, R.id.exo_play_pause);
        if (target == null && exoPlayPause != null && exoPlayPause.isShown()) {
            target = exoPlayPause;
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (player == null)
                    break;
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    player.pause();
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    player.play();
                } else if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                Utils.adjustVolume(this, mAudioManager, playerView, keyCode == KeyEvent.KEYCODE_VOLUME_UP, event.getRepeatCount() == 0, true);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (player == null)
                    break;
                if (!controllerVisibleFully) {
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!controllerVisibleFully || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                    if (player == null)
                        break;
                    playerView.removeCallbacks(playerView.textClearRunnable);
                    long pos = player.getCurrentPosition();
                    if (playerView.keySeekStart == -1) {
                        playerView.keySeekStart = pos;
                    }
                    long seekTo = pos - 10_000;
                    if (seekTo < 0)
                        seekTo = 0;
                    player.setSeekParameters(SeekParameters.PREVIOUS_SYNC);
                    player.seekTo(seekTo);
                    final String message = Utils.formatMilisSign(seekTo - playerView.keySeekStart) + "\n" + Utils.formatMilis(seekTo);
                    playerView.setCustomErrorMessage(message);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!controllerVisibleFully || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                    if (player == null)
                        break;
                    playerView.removeCallbacks(playerView.textClearRunnable);
                    long pos = player.getCurrentPosition();
                    if (playerView.keySeekStart == -1) {
                        playerView.keySeekStart = pos;
                    }
                    long seekTo = pos + 10_000;
                    long seekMax = player.getDuration();
                    if (seekMax != C.TIME_UNSET && seekTo > seekMax)
                        seekTo = seekMax;
                    PlayerActivity.player.setSeekParameters(SeekParameters.NEXT_SYNC);
                    player.seekTo(seekTo);
                    final String message = Utils.formatMilisSign(seekTo - playerView.keySeekStart) + "\n" + Utils.formatMilis(seekTo);
                    playerView.setCustomErrorMessage(message);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (isTvBox) {
                    if (controllerVisible && player != null && player.isPlaying()) {
                        playerView.hideController();
                        return true;
                    } else {
                        onBackPressed();
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!controllerVisibleFully) {
                    playerView.showController();
                    focusPlayPauseButton();
                    playerView.post(this::focusPlayPauseButton);
                    playerView.postDelayed(this::focusPlayPauseButton, 260);
                    return true;
                }
                View focused = getCurrentFocus();
                if (focused == null || focused == playerView || focused.getId() == R.id.exo_prev) {
                    focusPlayPauseButton();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_UNKNOWN:
                return super.onKeyDown(keyCode, event);
            default:
                if (!controllerVisibleFully) {
                    playerView.showController();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                playerView.postDelayed(playerView.textClearRunnable, CustomPlayerView.MESSAGE_TIMEOUT_KEY);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!isScrubbing) {
                    playerView.postDelayed(playerView.textClearRunnable, 1000);
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (btnSkipChapter != null && btnSkipChapter.getVisibility() == View.VISIBLE) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    btnSkipChapter.performClick();
                }
                return true;
            }
        }

        if (isScaling) {
            final int keyCode = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        scale(true);
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        scale(false);
                        break;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        break;
                    default:
                        if (isScaleStarting) {
                            isScaleStarting = false;
                        } else {
                            scaleEnd();
                        }
                }
            }
            return true;
        }

        if (isTvBox && !controllerVisibleFully) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                onKeyDown(event.getKeyCode(), event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                onKeyUp(event.getKeyCode(), event);
            }
            return true;
        } else {
            return super.dispatchKeyEvent(event);
        }
    }
    
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (0 != (event.getSource() & InputDevice.SOURCE_CLASS_POINTER)) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL:
                    final float value = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    Utils.adjustVolume(this, mAudioManager, playerView, value > 0.0f, Math.abs(value) > 1.0f, true);
                    return true;
            }
        } else if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                event.getAction() == MotionEvent.ACTION_MOVE) {
            // TODO: This somehow works, but it would use better filtering
            float value = event.getAxisValue(MotionEvent.AXIS_RZ);
            for (int i = 0; i < event.getHistorySize(); i++) {
                float historical = event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, i);
                if (Math.abs(historical) > value) {
                    value = historical;
                }
            }
            if (Math.abs(value) == 1.0f) {
                Utils.adjustVolume(this, mAudioManager, playerView, value < 0, true, true);
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        if (isInPictureInPictureMode) {
            // On Android TV it is required to hide controller in this PIP change callback
            playerView.hideController();
            setSubtitleTextSizePiP();
            playerView.setScale(1.f);
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_MEDIA_CONTROL.equals(intent.getAction()) || player == null) {
                        return;
                    }

                    switch (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                        case CONTROL_TYPE_PLAY:
                            player.play();
                            break;
                        case CONTROL_TYPE_PAUSE:
                            player.pause();
                            break;
                    }
                }
            };
            ContextCompat.registerReceiver(this, mReceiver, new IntentFilter(ACTION_MEDIA_CONTROL), ContextCompat.RECEIVER_EXPORTED);
        } else {
            setSubtitleTextSize();
            if (mPrefs.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setScale(mPrefs.scale);
            }
            if (mReceiver != null) {
                unregisterReceiver(mReceiver);
                mReceiver = null;
            }
            playerView.setControllerAutoShow(true);
            if (player != null) {
                if (player.isPlaying())
                    Utils.toggleSystemUi(this, playerView, false);
                else
                    playerView.showController();
            }
        }
    }

    void resetApiAccess() {
        apiAccess = false;
        apiAccessPartial = false;
        apiTitle = null;
        apiSubs.clear();
        mPrefs.setPersistent(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (restoreOrientationLock) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                restoreOrientationLock = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (resultCode == RESULT_OK && alive) {
            releasePlayer();
        }

        if (requestCode == REQUEST_CHOOSER_VIDEO || requestCode == REQUEST_CHOOSER_VIDEO_MEDIASTORE) {
            if (resultCode == RESULT_OK) {
                resetApiAccess();
                restorePlayState = false;

                final Uri uri = data.getData();

                if (requestCode == REQUEST_CHOOSER_VIDEO) {
                    boolean uriAlreadyTaken = false;

                    // https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html
                    final ContentResolver contentResolver = getContentResolver();
                    for (UriPermission persistedUri : contentResolver.getPersistedUriPermissions()) {
                        if (persistedUri.getUri().equals(mPrefs.scopeUri)) {
                            continue;
                        } else if (persistedUri.getUri().equals(uri)) {
                            uriAlreadyTaken = true;
                        } else {
                            try {
                                contentResolver.releasePersistableUriPermission(persistedUri.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    if (!uriAlreadyTaken && uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            e.printStackTrace();
                        }
                    }
                }

                mPrefs.setPersistent(true);
                mPrefs.updateMedia(this, uri, data.getType());

                if (requestCode == REQUEST_CHOOSER_VIDEO) {
                    searchSubtitles();
                }
            }
        } else if (requestCode == REQUEST_CHOOSER_SUBTITLE || requestCode == REQUEST_CHOOSER_SUBTITLE_MEDIASTORE) {
            if (resultCode == RESULT_OK) {
                Uri uri = data.getData();

                if (requestCode == REQUEST_CHOOSER_SUBTITLE) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    }
                }

                handleSubtitles(uri);
            }
        } else if (requestCode == REQUEST_CHOOSER_SCOPE_DIR) {
            if (resultCode == RESULT_OK) {
                final Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    mPrefs.updateScope(uri);
                    mPrefs.markScopeAsked();
                    searchSubtitles();
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
            }
        } else if (requestCode == REQUEST_SETTINGS) {
            mPrefs.loadUserPreferences();
            updateSubtitleStyle(this);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == 9912 && resultCode == RESULT_OK && data != null && data.getData() != null) {
                attachExternalSubtitle(data.getData());
                return;
            } else if (requestCode == SubtitleStyleHelper.REQUEST_PICK_FONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
                SubtitleStyleHelper.importFontFromUri(this, data.getData());
                updateSubtitleStyle(this);
                return;
            }
        }

        // Init here because onStart won't follow when app was only paused when file chooser was shown
        // (for example pop-up file chooser on tablets)
        if (resultCode == RESULT_OK && alive) {
            initializePlayer();
        }
    }

    private void handleSubtitles(Uri uri) {
        // Convert subtitles to UTF-8 if necessary
        SubtitleUtils.clearCache(this);
        uri = Utils.convertToUTF(this, uri);
        mPrefs.updateSubtitle(uri);
    }

    public void initializePlayer() {

        if (player != null) {
            // If returning to the exact same playing audio track, just re-attach the view without restarting
            if (mPrefs != null && mPrefs.mediaUri != null && isAudioUri(mPrefs.mediaUri)
                    && player.getCurrentMediaItem() != null
                    && mPrefs.mediaUri.equals(player.getCurrentMediaItem().localConfiguration != null
                        ? player.getCurrentMediaItem().localConfiguration.uri : null)) {
                playerView.setPlayer(player);
                return;
            }
            stopBackgroundAudio();
        }
        
        boolean isNetworkUri = Utils.isSupportedNetworkUri(mPrefs.mediaUri);
        haveMedia = mPrefs.mediaUri != null;

        if (player != null) {
            player.removeListener(playerListener);
            player.clearMediaItems();
            player.release();
            player = null;
        }

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true));
        if (mPrefs.tunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true)
            );
        }
        switch (mPrefs.languageAudio) {
            case Prefs.TRACK_DEFAULT:
                break;
            case Prefs.TRACK_DEVICE:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(Utils.getDeviceLanguages())
                );
                break;
            default:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(mPrefs.languageAudio)
                );
        }
        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (!captioningManager.isEnabled()) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            );
        }
        Locale locale = captioningManager.getLocale();
        if (locale != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredTextLanguage(locale.getISO3Language())
            );
        }
        // https://github.com/google/ExoPlayer/issues/8571
        final DefaultSubtitleParserFactory baseSubtitleFactory = new DefaultSubtitleParserFactory();
        SubtitleParser.Factory subtitleParserFactory = new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return baseSubtitleFactory.supportsFormat(format);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return baseSubtitleFactory.getCueReplacementBehavior(format);
            }

            @Override
            public SubtitleParser create(Format format) {
                final SubtitleParser delegate = baseSubtitleFactory.create(format);
                return new SubtitleParser() {
                    @Override
                    public void parse(byte[] data, int offset, int length, OutputOptions outputOptions, java.util.function.Consumer<CuesWithTiming> output) {
                        delegate.parse(data, offset, length, outputOptions, cues -> {
                            long shiftedStart = cues.startTimeUs == C.TIME_UNSET
                                    ? C.TIME_UNSET
                                    : Math.max(0L, cues.startTimeUs + (subtitleDelayMs * 1000L));
                            output.accept(new CuesWithTiming(cues.cues, shiftedStart, cues.durationUs));
                        });
                    }

                    @Override
                    public int getCueReplacementBehavior() {
                        return delegate.getCueReplacementBehavior();
                    }
                };
            }
        };

        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setSubtitleParserFactory(subtitleParserFactory)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE);

        MediaCodecUtil.setMapDV7ToHevc(mPrefs.mapDV7ToHevc);

        @SuppressLint("WrongConstant") RenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                AudioSink baseSink = passthroughEnabled
                        ? super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                        : new DefaultAudioSink.Builder(context)
                                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                                .setEnableFloatOutput(enableFloatOutput)
                                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                                .build();
                if (baseSink == null) {
                    return null;
                }
                return new ForwardingAudioSink(baseSink) {
                    @Override
                    public long getCurrentPositionUs(boolean sourceEnded) {
                        long pos = super.getCurrentPositionUs(sourceEnded);
                        if (pos == AudioSink.CURRENT_POSITION_NOT_SET) {
                            return pos;
                        }
                        return Math.max(0L, pos + (audioDelayMs * 1000L));
                    }
                };
            }
        }.setExtensionRendererMode(passthroughEnabled
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                : mPrefs.decoderPriority);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this, extractorsFactory)
                .setSubtitleParserFactory(subtitleParserFactory);

        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(mediaSourceFactory);

        if (haveMedia && mPrefs.mediaUri != null && mPrefs.mediaUri.getScheme() != null) {
            String scheme = mPrefs.mediaUri.getScheme().toLowerCase();
            if ("ftp".equals(scheme)) {
                playerBuilder.setMediaSourceFactory(
                        new DefaultMediaSourceFactory(new FtpDataSource.Factory(), extractorsFactory)
                                .setSubtitleParserFactory(subtitleParserFactory));
            } else if ("smb".equals(scheme) || "smb1".equals(scheme)) {
                playerBuilder.setMediaSourceFactory(
                        new DefaultMediaSourceFactory(new SmbDataSource.Factory(), extractorsFactory)
                                .setSubtitleParserFactory(subtitleParserFactory));
            }
        }
        if (haveMedia && isNetworkUri) {
            if (mPrefs.mediaUri.getScheme().toLowerCase().startsWith("http")) {
                HashMap<String, String> headers = new HashMap<>();
                String userInfo = mPrefs.mediaUri.getUserInfo();
                if (userInfo != null && userInfo.length() > 0 && userInfo.contains(":")) {
                    headers.put("Authorization", "Basic " + Base64.encodeToString(userInfo.getBytes(), Base64.NO_WRAP));
                    DefaultHttpDataSource.Factory defaultHttpDataSourceFactory = new DefaultHttpDataSource.Factory();
                    defaultHttpDataSourceFactory.setDefaultRequestProperties(headers);
                    playerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(defaultHttpDataSourceFactory, extractorsFactory));
                }
            }
        }

        player = playerBuilder.build();

        boolean isAudioFile = mPrefs != null && mPrefs.mediaUri != null && isAudioUri(mPrefs.mediaUri);
        androidx.media3.common.AudioAttributes audioAttrs = new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(isAudioFile
                        ? androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
                        : androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player.setAudioAttributes(audioAttrs, true); 

        if (mPrefs.skipSilence) {
            player.setSkipSilenceEnabled(true);
        }

        youTubeOverlay.player(player);
        playerView.setPlayer(player);

        if (mediaSession != null) {
            mediaSession.release();
        }

        if (player.canAdvertiseSession()) {
            try {
                mediaSession = new MediaSession.Builder(this, player).build();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        playerView.setControllerShowTimeoutMs(-1);

        locked = false;

        if (haveMedia) {
            if (isNetworkUri) {
                timeBar.setBufferedColor(DefaultTimeBar.DEFAULT_BUFFERED_COLOR);
            } else {
                // https://github.com/google/ExoPlayer/issues/5765
                timeBar.setBufferedColor(0x33FFFFFF);
            }

            playerView.setResizeMode(mPrefs.resizeMode);

            if (mPrefs.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                playerView.setScale(mPrefs.scale);
            } else {
                playerView.setScale(1.f);
            }
            updatebuttonAspectRatioIcon();

            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                    .setUri(mPrefs.mediaUri)
                    .setMimeType(mPrefs.mediaType);
            String title;
            if (apiTitle != null) {
                title = apiTitle;
            } else {
                title = Utils.getFileName(PlayerActivity.this, mPrefs.mediaUri);
            }
            if (title != null) {
                final MediaMetadata mediaMetadata = new MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .build();
                mediaItemBuilder.setMediaMetadata(mediaMetadata);
            }
            if (apiAccess && apiSubs.size() > 0) {
                mediaItemBuilder.setSubtitleConfigurations(apiSubs);
            } else if (mPrefs.subtitleUri != null && Utils.fileExists(this, mPrefs.subtitleUri)) {
                MediaItem.SubtitleConfiguration subtitle = SubtitleUtils.buildSubtitle(this, mPrefs.subtitleUri, null, true);
                mediaItemBuilder.setSubtitleConfigurations(Collections.singletonList(subtitle));
            }
            ArrayList<String> playlistUrls = getIntent() != null ? getIntent().getStringArrayListExtra("playlist_urls") : null;
            ArrayList<String> playlistTitles = getIntent() != null ? getIntent().getStringArrayListExtra("playlist_titles") : null;
            int playlistIndex = getIntent() != null ? getIntent().getIntExtra("playlist_index", 0) : 0;

            if (playlistUrls != null && playlistUrls.size() > 1) {
                List<MediaItem> playlistItems = new ArrayList<>();
                for (int i = 0; i < playlistUrls.size(); i++) {
                    String t = (playlistTitles != null && i < playlistTitles.size()) ? playlistTitles.get(i) : "";
                    playlistItems.add(new MediaItem.Builder()
                            .setUri(Uri.parse(playlistUrls.get(i)))
                            .setMediaMetadata(new MediaMetadata.Builder().setTitle(t).build())
                            .build());
                }
                player.setMediaItems(playlistItems, playlistIndex, C.TIME_UNSET);
            } else {
                player.setMediaItem(mediaItemBuilder.build(), mPrefs.getPosition());
            }

            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.release();
                }
                loudnessEnhancer = new LoudnessEnhancer(player.getAudioSessionId());
            } catch (Exception e) {
                e.printStackTrace();
            }

            notifyAudioSessionUpdate(true);

            videoLoading = true;

            updateLoading(true);

            if (mPrefs.getPosition() == 0L || apiAccess || apiAccessPartial) {
                play = true;
            }

            if (apiTitle != null) {
                titleView.setText(apiTitle);
            } else {
                titleView.setText(Utils.getFileName(this, mPrefs.mediaUri));
            }
            titleView.setVisibility(View.VISIBLE);

            updateButtons(true);

            ((DoubleTapPlayerView)playerView).setDoubleTapEnabled(true);

            if (!apiAccess) {
                if (nextUriThread != null) {
                    nextUriThread.interrupt();
                }
                nextUri = null;
                nextUriThread = new Thread(() -> {
                    Uri uri = findNext();
                    if (!Thread.currentThread().isInterrupted()) {
                        nextUri = uri;
                    }
                });
                nextUriThread.start();
            }

            player.setHandleAudioBecomingNoisy(!isTvBox);
//            mediaSession.setActive(true);
        } else {
            playerView.showController();
        }

        player.addListener(playerListener);
        sdhAppliedForCurrentItem = false;
        TrackPrefHelper.applyToPlayer(this, player);
        if (hasSavedVideoResume()) {
            isResumeDialogOpen = true;
            player.setPlayWhenReady(false);
        }
        if (hasSavedVideoResume()) {
            isResumeDialogOpen = true;
            player.setPlayWhenReady(false);
        }
        player.prepare();
        resumeDialogShownForCurrentItem = false;

        if (restorePlayState) {
            restorePlayState = false;
            playerView.showController();
            playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
            if (!isResumeDialogOpen) {
                player.setPlayWhenReady(true);
            }
        }
    }

    private void savePlayer() {
        if (player != null) {
            mPrefs.updateBrightness(mBrightnessControl.currentBrightnessLevel);
            mPrefs.updateOrientation();

            if (haveMedia) {
                // Prevent overwriting temporarily inaccessible media position
                if (player.isCurrentMediaItemSeekable()) {
                    mPrefs.updatePosition(player.getCurrentPosition());
                }
                mPrefs.updateMeta(getSelectedTrack(C.TRACK_TYPE_AUDIO),
                        getSelectedTrack(C.TRACK_TYPE_TEXT),
                        playerView.getResizeMode(),
                        playerView.getVideoSurfaceView().getScaleX(),
                        player.getPlaybackParameters().speed);
            }
        }
    }

    public void releasePlayer() {

        if (player != null && mPrefs != null && mPrefs.mediaUri != null
                && isAudioUri(mPrefs.mediaUri) && player.isPlaying()) {
            skipHandler.removeCallbacks(chapterMonitorRunnable);
            skipHandler.removeCallbacks(hideSkipRunnable);
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            return;
        }

        saveVideoResumePosition();
        
        releasePlayer(true);
    }

    public void releasePlayer(boolean save) {
        if (save) {
            savePlayer();
        }

        skipHandler.removeCallbacks(chapterMonitorRunnable);
        skipHandler.removeCallbacks(hideSkipRunnable);
        if (btnSkipChapter != null) btnSkipChapter.setVisibility(View.GONE);
        
        if (player != null) {
            notifyAudioSessionUpdate(false);

//            mediaSession.setActive(false);
            if (mediaSession != null) {
                mediaSession.release();
            }

            if (player.isPlaying() && restorePlayStateAllowed) {
                restorePlayState = true;
            }
            player.removeListener(playerListener);
            player.clearMediaItems();
            if (player != null && mPrefs.mediaUri != null && !isAudioUri(mPrefs.mediaUri)) {
                long pos = player.getCurrentPosition();
                long dur = player.getDuration();
                android.content.SharedPreferences sp = getSharedPreferences("jsplayer_resume", MODE_PRIVATE);
                String key = mPrefs.mediaUri.toString();
                if (pos > 5000 && (dur <= 0 || pos < dur - 10000)) {
                    sp.edit().putLong(key, pos).apply();
                } else if (dur > 0 && pos >= dur - 10000) {
                    sp.edit().remove(key).apply(); // Cleared once video finishes
                }
            }
            player.release();
            player = null;
        }
        titleView.setVisibility(View.GONE);
        updateButtons(false);
    }

    private class PlayerListener implements Player.Listener {
        @Override
        public void onMediaItemTransition(@androidx.annotation.Nullable androidx.media3.common.MediaItem mediaItem, int reason) {
            if (mediaItem != null) {
                sdhAppliedForCurrentItem = false;
                lastShownSkipChapterIndex = -1;
                preSkipPositionMs = -1;

                CharSequence newTitle = (mediaItem.mediaMetadata != null && mediaItem.mediaMetadata.title != null)
                        ? mediaItem.mediaMetadata.title : null;
                if (newTitle == null && mediaItem.localConfiguration != null && mediaItem.localConfiguration.uri != null) {
                    String seg = mediaItem.localConfiguration.uri.getLastPathSegment();
                    if (seg != null) newTitle = seg;
                }
                if (newTitle != null && titleView != null) {
                    titleView.setText(newTitle);
                    titleView.setVisibility(View.VISIBLE);
                }
                if (mediaItem.localConfiguration != null && mediaItem.localConfiguration.uri != null && mPrefs != null) {
                    mPrefs.mediaUri = mediaItem.localConfiguration.uri;
                }
            }
        }
        @Override
        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            activeChapters = ChapterHelper.extractChapters(tracks);
            lastShownSkipChapterIndex = -1;
            skipHandler.removeCallbacks(chapterMonitorRunnable);
            if (!activeChapters.isEmpty()) {
                skipHandler.post(chapterMonitorRunnable);
            }
            if (!sdhAppliedForCurrentItem && !tracks.isEmpty()) {
                sdhAppliedForCurrentItem = true;
                TrackPrefHelper.applySmartSdhOverride(PlayerActivity.this, player, tracks);
            }
        }
        
        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.release();
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            notifyAudioSessionUpdate(true);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying && isResumeDialogOpen && player != null) {
                player.setPlayWhenReady(false);
                player.pause();
                return;
            }
            android.widget.ImageButton btnPlayPause = findViewById(R.id.btn_bl_play_pause);
            if (btnPlayPause != null) {
                btnPlayPause.setImageResource(isPlaying
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
            }
            
            playerView.setKeepScreenOn(isPlaying);

            if (Utils.isPiPSupported(PlayerActivity.this)) {
                if (isPlaying) {
                    updatePictureInPictureActions(R.drawable.ic_pause_24dp, R.string.exo_controls_pause_description, CONTROL_TYPE_PAUSE, REQUEST_PAUSE);
                } else {
                    updatePictureInPictureActions(R.drawable.ic_play_arrow_24dp, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY);
                }
            }

            if (!isScrubbing) {
                if (isPlaying) {
                    if (shortControllerTimeout) {
                        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT / 3);
                        shortControllerTimeout = false;
                        restoreControllerTimeout = true;
                    } else {
                        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT);
                    }
                } else {
                    playerView.setControllerShowTimeoutMs(-1);
                }
            }

            if (!isPlaying) {
                PlayerActivity.locked = false;
            }
        }

        @SuppressLint("SourceLockedOrientationActivity")
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == androidx.media3.common.Player.STATE_READY) {
                checkAndShowVideoResumeDialog();
            }
            boolean isNearEnd = false;
            final long duration = player.getDuration();
            if (duration != C.TIME_UNSET) {
                final long position = player.getCurrentPosition();
                if (position + 4000 >= duration) {
                    isNearEnd = true;
                }
            }
            setEndControlsVisible(haveMedia && (state == Player.STATE_ENDED || isNearEnd));

            if (state == Player.STATE_READY) {
                frameRendered = true;

                if (videoLoading) {
                    videoLoading = false;

                    if (mPrefs.orientation == Utils.Orientation.UNSPECIFIED) {
                        mPrefs.orientation = Utils.getNextOrientation(mPrefs.orientation);
                        Utils.setOrientation(PlayerActivity.this, mPrefs.orientation);
                    }

                    final Format format = player.getVideoFormat();

                    if (format != null) {
                        if (!isTvBox && mPrefs.orientation == Utils.Orientation.VIDEO) {
                            if (Utils.isPortrait(format)) {
                                PlayerActivity.this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                            } else {
                                PlayerActivity.this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                            }
                            updateButtonRotation();
                        }

                        updateSubtitleViewMargin(format);
                    }

                    if (duration != C.TIME_UNSET && duration > TimeUnit.MINUTES.toMillis(20)) {
                        timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1));
                    } else {
                        timeBar.setKeyCountIncrement(20);
                    }

                    boolean switched = false;
                    if (mPrefs.frameRateMatching) {
                        if (play) {
                            if (displayManager == null) {
                                displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                            }
                            if (displayListener == null) {
                                displayListener = new DisplayManager.DisplayListener() {
                                    @Override
                                    public void onDisplayAdded(int displayId) {

                                    }

                                    @Override
                                    public void onDisplayRemoved(int displayId) {

                                    }

                                    @Override
                                    public void onDisplayChanged(int displayId) {
                                        if (play) {
                                            play = false;
                                            displayManager.unregisterDisplayListener(this);
                                            if (player != null) {
                                                player.play();
                                            }
                                            if (playerView != null) {
                                                playerView.hideController();
                                            }
                                        }
                                    }
                                };
                            }
                            displayManager.registerDisplayListener(displayListener, null);
                        }
                        switched = Utils.switchFrameRate(PlayerActivity.this, mPrefs.mediaUri, play);
                    }
                    if (!switched) {
                        if (displayManager != null) {
                            displayManager.unregisterDisplayListener(displayListener);
                        }
                        if (play) {
                            play = false;
                            player.play();
                            playerView.hideController();
                        }
                    }

                    updateLoading(false);

                    if (mPrefs.speed <= 0.99f || mPrefs.speed >= 1.01f) {
                        player.setPlaybackSpeed(mPrefs.speed);
                    }
                    if (!apiAccess) {
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }
                }
            } else if (state == Player.STATE_ENDED) {
                playbackFinished = true;
                if (apiAccess) {
                    finish();
                }
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            updateLoading(false);
            if (error instanceof ExoPlaybackException) {
                final ExoPlaybackException exoPlaybackException = (ExoPlaybackException) error;
                if (exoPlaybackException.type == ExoPlaybackException.TYPE_SOURCE) {
                    releasePlayer(false);
                    return;
                }
                if (controllerVisible && controllerVisibleFully) {
                    showError(exoPlaybackException);
                } else {
                    errorToShow = exoPlaybackException;
                }
            }
        }
    }

    private void enableRotation() {
        try {
            if (Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                restoreOrientationLock = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    boolean useMediaStore() {
        final int targetSdkVersion = getApplicationContext().getApplicationInfo().targetSdkVersion;
        return (isTvBox && Build.VERSION.SDK_INT >= 30 && targetSdkVersion >= 30 && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("mediastore");
    }

    private void openFile(Uri pickerInitialUri) {
        if (useMediaStore()) {
            Intent intent = new Intent(this, MediaStoreChooserActivity.class);
            startActivityForResult(intent, REQUEST_CHOOSER_VIDEO_MEDIASTORE);
        } else if ((isTvBox && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("legacy")) {
            Utils.alternativeChooser(this, pickerInitialUri, true);
        } else {
            enableRotation();

            if (pickerInitialUri == null || Utils.isSupportedNetworkUri(pickerInitialUri)) {
                pickerInitialUri = Utils.getMoviesFolderUri();
            }

            final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT, pickerInitialUri);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, Utils.supportedMimeTypesVideo);

            if (Build.VERSION.SDK_INT < 30) {
                final ComponentName systemComponentName = Utils.getSystemComponent(this, intent);
                if (systemComponentName != null) {
                    intent.setComponent(systemComponentName);
                }
            }

            safelyStartActivityForResult(intent, REQUEST_CHOOSER_VIDEO);
        }
    }

    private void loadSubtitleFile(Uri pickerInitialUri) {
        Toast.makeText(PlayerActivity.this, R.string.open_subtitles, Toast.LENGTH_SHORT).show();
        final int targetSdkVersion = getApplicationContext().getApplicationInfo().targetSdkVersion;
        if ((isTvBox && Build.VERSION.SDK_INT >= 30 && targetSdkVersion >= 30 && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("mediastore")) {
            Intent intent = new Intent(this, MediaStoreChooserActivity.class);
            intent.putExtra(MediaStoreChooserActivity.SUBTITLES, true);
            startActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE_MEDIASTORE);
        } else if ((isTvBox && mPrefs.fileAccess.equals("auto")) || mPrefs.fileAccess.equals("legacy")) {
            Utils.alternativeChooser(this, pickerInitialUri, false);
        } else {
            enableRotation();

            final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT, pickerInitialUri);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            final String[] supportedMimeTypes = {
                    MimeTypes.APPLICATION_SUBRIP,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.APPLICATION_TTML,
                    "text/*",
                    "application/octet-stream"
            };
            intent.putExtra(Intent.EXTRA_MIME_TYPES, supportedMimeTypes);

            if (Build.VERSION.SDK_INT < 30) {
                final ComponentName systemComponentName = Utils.getSystemComponent(this, intent);
                if (systemComponentName != null) {
                    intent.setComponent(systemComponentName);
                }
            }

            safelyStartActivityForResult(intent, REQUEST_CHOOSER_SUBTITLE);
        }
    }

    private void requestDirectoryAccess() {
        enableRotation();
        final Intent intent = createBaseFileIntent(Intent.ACTION_OPEN_DOCUMENT_TREE, Utils.getMoviesFolderUri());
        safelyStartActivityForResult(intent, REQUEST_CHOOSER_SCOPE_DIR);
    }

    private Intent createBaseFileIntent(final String action, final Uri initialUri) {
        final Intent intent = new Intent(action);

        // http://stackoverflow.com/a/31334967/1615876
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);

        if (Build.VERSION.SDK_INT >= 26 && initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }

        return intent;
    }

    void safelyStartActivityForResult(final Intent intent, final int code) {
        if (intent.resolveActivity(getPackageManager()) == null)
            showSnack(getText(R.string.error_files_missing).toString(), intent.toString());
        else
            startActivityForResult(intent, code);
    }

    private TrackGroup getTrackGroupFromFormatId(int trackType, String id) {
        if ((id == null && trackType == C.TRACK_TYPE_AUDIO ) || player == null) {
            return null;
        }
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == trackType) {
                final TrackGroup trackGroup = group.getMediaTrackGroup();
                final Format format = trackGroup.getFormat(0);
                if (Objects.equals(id, format.id)) {
                    return trackGroup;
                }
            }
        }
        return null;
    }

    public void setSelectedTracks(final String subtitleId, final String audioId) {
        if ("#none".equals(subtitleId)) {
            if (trackSelector == null) {
                return;
            }
            trackSelector.setParameters(trackSelector.buildUponParameters().setDisabledTextTrackSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED));
        }

        TrackGroup subtitleGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_TEXT, subtitleId);
        TrackGroup audioGroup = getTrackGroupFromFormatId(C.TRACK_TYPE_AUDIO, audioId);

        TrackSelectionParameters.Builder overridesBuilder = new TrackSelectionParameters.Builder(this);
        TrackSelectionOverride trackSelectionOverride = null;
        final List<Integer> tracks = new ArrayList<>(); tracks.add(0);
        if (subtitleGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(subtitleGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }
        if (audioGroup != null) {
            trackSelectionOverride = new TrackSelectionOverride(audioGroup, tracks);
            overridesBuilder.addOverride(trackSelectionOverride);
        }

        if (player != null) {
            TrackSelectionParameters.Builder trackSelectionParametersBuilder = player.getTrackSelectionParameters().buildUpon();
            if (trackSelectionOverride != null) {
                trackSelectionParametersBuilder.setOverrideForType(trackSelectionOverride);
            }
            player.setTrackSelectionParameters(trackSelectionParametersBuilder.build());
        }
    }

    private boolean hasOverrideType(final int trackType) {
        TrackSelectionParameters trackSelectionParameters = player.getTrackSelectionParameters();
        for (TrackSelectionOverride override : trackSelectionParameters.overrides.values()) {
            if (override.getType() == trackType)
                return true;
        }
        return false;
    }

    public String getSelectedTrack(final int trackType) {
        if (player == null) {
            return null;
        }
        Tracks tracks = player.getCurrentTracks();

        // Disabled (e.g. selected subtitle "None" - different than default)
        if (!tracks.isTypeSelected(trackType)) {
            return "#none";
        }

        // Audio track set to "Auto"
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (!hasOverrideType(C.TRACK_TYPE_AUDIO)) {
                return null;
            }
        }

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.isSelected() && group.getType() == trackType) {
                Format format = group.getMediaTrackGroup().getFormat(0);
                return format.id;
            }
        }

        return null;
    }

    void setSubtitleTextSize() {
        setSubtitleTextSize(getResources().getConfiguration().orientation);
    }

    void setSubtitleTextSize(final int orientation) {
        // Tweak text size as fraction size doesn't work well in portrait
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            final float size;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                size = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitlesScale * SubtitleStyleHelper.getSizeMultiplier(this);
            } else {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                float ratio = ((float)metrics.heightPixels / (float)metrics.widthPixels);
                if (ratio < 1)
                    ratio = 1 / ratio;
                size = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitlesScale / ratio * SubtitleStyleHelper.getSizeMultiplier(this);
            }

            subtitleView.setFractionalTextSize(size);
        }
    }

    void updateSubtitleViewMargin() {
        if (player == null) {
            return;
        }

        updateSubtitleViewMargin(player.getVideoFormat());
    }

    // Set margins to fix PGS aspect as subtitle view is outside of content frame
    void updateSubtitleViewMargin(Format format) {
        if (format == null) {
            return;
        }

        final Rational aspectVideo = Utils.getRational(format);
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final Rational aspectDisplay = new Rational(metrics.widthPixels, metrics.heightPixels);

        int marginHorizontal = 0;
        int marginVertical = 0;

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (aspectDisplay.floatValue() > aspectVideo.floatValue()) {
                // Left & right bars
                int videoWidth = metrics.heightPixels / aspectVideo.getDenominator() * aspectVideo.getNumerator();
                marginHorizontal = (metrics.widthPixels - videoWidth) / 2;
            }
        }

        Utils.setViewParams(playerView.getSubtitleView(), 0, 0, 0, 0,
                marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }

    void setSubtitleTextSizePiP() {
        final SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null)
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2);
    }

    @TargetApi(26)
    boolean updatePictureInPictureActions(final int iconId, final int resTitle, final int controlType, final int requestCode) {
        try {
            final ArrayList<RemoteAction> actions = new ArrayList<>();
            final PendingIntent intent = PendingIntent.getBroadcast(PlayerActivity.this, requestCode,
                    new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, controlType), PendingIntent.FLAG_IMMUTABLE);
            final Icon icon = Icon.createWithResource(PlayerActivity.this, iconId);
            final String title = getString(resTitle);
            actions.add(new RemoteAction(icon, title, title, intent));
            ((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).setActions(actions);
            setPictureInPictureParams(((PictureInPictureParams.Builder) mPictureInPictureParamsBuilder).build());
            return true;
        } catch (IllegalStateException e) {
            // On Samsung devices with Talkback active:
            // Caused by: java.lang.IllegalStateException: setPictureInPictureParams: Device doesn't support picture-in-picture mode.
            e.printStackTrace();
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private boolean isInPip() {
        if (!Utils.isPiPSupported(this))
            return false;
        return isInPictureInPictureMode();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (!isInPip()) {
            setSubtitleTextSize(newConfig.orientation);
        }
        updateSubtitleViewMargin();

        updateButtonRotation();
    }

    void showError(ExoPlaybackException error) {
        final String errorGeneral = error.getLocalizedMessage();
        String errorDetailed;

        switch (error.type) {
            case ExoPlaybackException.TYPE_SOURCE:
                errorDetailed = error.getSourceException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_RENDERER:
                errorDetailed = error.getRendererException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_UNEXPECTED:
                errorDetailed = error.getUnexpectedException().getLocalizedMessage();
                break;
            case ExoPlaybackException.TYPE_REMOTE:
            default:
                errorDetailed = errorGeneral;
                break;
        }

        showSnack(errorGeneral, errorDetailed);
    }

    void showSnack(final String textPrimary, final String textSecondary) {
        snackbar = Snackbar.make(coordinatorLayout, textPrimary, Snackbar.LENGTH_LONG);
        if (textSecondary != null) {
            snackbar.setAction(R.string.error_details, v -> {
                final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
                builder.setMessage(textSecondary);
                builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> dialogInterface.dismiss());
                final AlertDialog dialog = builder.create();
                dialog.show();
            });
        }
        snackbar.setAnchorView(R.id.exo_bottom_bar);
        snackbar.show();
    }

    void reportScrubbing(long position) {
        final long diff = position - scrubbingStart;
        if (Math.abs(diff) > 1000) {
            scrubbingNoticeable = true;
        }
        if (scrubbingNoticeable) {
            playerView.clearIcon();
            playerView.setCustomErrorMessage(Utils.formatMilisSign(diff));
        }
        if (frameRendered) {
            frameRendered = false;
            if (player != null) {
                player.seekTo(position);
            }
        }
    }

    void updateSubtitleStyle(final Context context) {
        final CaptioningManager captioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        final SubtitleView subtitleView = playerView.getSubtitleView();
        final boolean isTablet = Utils.isTablet(context);
        subtitlesScale = SubtitleUtils.normalizeFontScale(captioningManager.getFontScale(), isTvBox || isTablet);
        if (subtitleView != null) {
            SubtitleStyleHelper.applyStyle(context, subtitleView, mPrefs);
        }
        setSubtitleTextSize();
    }

    void searchSubtitles() {
        if (mPrefs.mediaUri == null)
            return;

        if (Utils.isSupportedNetworkUri(mPrefs.mediaUri) && Utils.isProgressiveContainerUri(mPrefs.mediaUri)) {
            SubtitleUtils.clearCache(this);
            if (SubtitleFinder.isUriCompatible(mPrefs.mediaUri)) {
                subtitleFinder = new SubtitleFinder(PlayerActivity.this, mPrefs.mediaUri);
                subtitleFinder.start();
            }
            return;
        }

        if (mPrefs.scopeUri != null || isTvBox) {
            DocumentFile video = null;
            File videoRaw = null;
            final String scheme = mPrefs.mediaUri.getScheme();

            if (mPrefs.scopeUri != null) {
                if ("com.android.externalstorage.documents".equals(mPrefs.mediaUri.getHost()) ||
                        "org.courville.nova.provider".equals(mPrefs.mediaUri.getHost())) {
                    // Fast search based on path in uri
                    video = SubtitleUtils.findUriInScope(this, mPrefs.scopeUri, mPrefs.mediaUri);
                } else {
                    // Slow search based on matching metadata, no path in uri
                    // Provider "com.android.providers.media.documents" when using "Videos" tab in file picker
                    DocumentFile fileScope = DocumentFile.fromTreeUri(this, mPrefs.scopeUri);
                    DocumentFile fileMedia = DocumentFile.fromSingleUri(this, mPrefs.mediaUri);
                    video = SubtitleUtils.findDocInScope(fileScope, fileMedia);
                }
            } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                videoRaw = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                video = DocumentFile.fromFile(videoRaw);
            }

            if (video != null) {
                DocumentFile subtitle = null;
                if (mPrefs.scopeUri != null) {
                    subtitle = SubtitleUtils.findSubtitle(video);
                } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                    File parentRaw = videoRaw.getParentFile();
                    DocumentFile dir = DocumentFile.fromFile(parentRaw);
                    subtitle = SubtitleUtils.findSubtitle(video, dir);
                }

                if (subtitle != null) {
                    handleSubtitles(subtitle.getUri());
                }
            }
        }
    }

    Uri findNext() {
        // TODO: Unify with searchSubtitles()
        if (mPrefs.scopeUri != null || isTvBox) {
            DocumentFile video = null;
            File videoRaw = null;

            if (!isTvBox && mPrefs.scopeUri != null) {
                if ("com.android.externalstorage.documents".equals(mPrefs.mediaUri.getHost())) {
                    // Fast search based on path in uri
                    video = SubtitleUtils.findUriInScope(this, mPrefs.scopeUri, mPrefs.mediaUri);
                } else {
                    // Slow search based on matching metadata, no path in uri
                    // Provider "com.android.providers.media.documents" when using "Videos" tab in file picker
                    DocumentFile fileScope = DocumentFile.fromTreeUri(this, mPrefs.scopeUri);
                    DocumentFile fileMedia = DocumentFile.fromSingleUri(this, mPrefs.mediaUri);
                    video = SubtitleUtils.findDocInScope(fileScope, fileMedia);
                }
            } else if (isTvBox) {
                videoRaw = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                video = DocumentFile.fromFile(videoRaw);
            }

            if (video != null) {
                DocumentFile next;
                if (!isTvBox) {
                    next = SubtitleUtils.findNext(video);
                } else {
                    File parentRaw = videoRaw.getParentFile();
                    DocumentFile dir = DocumentFile.fromFile(parentRaw);
                    next = SubtitleUtils.findNext(video, dir);
                }
                if (next != null) {
                    return next.getUri();
                }
            }
        }
        return null;
    }

    void askForScope(boolean loadSubtitlesOnCancel, boolean skipToNextOnCancel) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
        builder.setMessage(String.format(getString(R.string.request_scope), getString(R.string.app_name)));
        builder.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> requestDirectoryAccess()
        );
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
            mPrefs.markScopeAsked();
            if (loadSubtitlesOnCancel) {
                loadSubtitleFile(mPrefs.mediaUri);
            }
            if (skipToNextOnCancel) {
                nextUri = findNext();
                if (nextUri != null) {
                    skipToNext();
                }
            }
        });
        final AlertDialog dialog = builder.create();
        dialog.show();
    }

    void resetHideCallbacks() {
        if (haveMedia && player != null && player.isPlaying()) {
            // Keep controller UI visible - alternative to resetHideCallbacks()
            playerView.setControllerShowTimeoutMs(PlayerActivity.CONTROLLER_TIMEOUT);
        }
    }

    private void updateLoading(final boolean enableLoading) {
        if (enableLoading) {
            exoPlayPause.setVisibility(View.GONE);
            loadingProgressBar.setVisibility(View.VISIBLE);
        } else {
            loadingProgressBar.setVisibility(View.GONE);
            exoPlayPause.setVisibility(View.VISIBLE);
            if (focusPlay) {
                focusPlay = false;
                exoPlayPause.requestFocus();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onUserLeaveHint() {
        if (mPrefs!= null && mPrefs.autoPiP && player != null && player.isPlaying() && Utils.isPiPSupported(this))
            enterPiP();
        else
            super.onUserLeaveHint();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void enterPiP() {
        final AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (AppOpsManager.MODE_ALLOWED != appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), getPackageName())) {
            final Intent intent = new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.fromParts("package", getPackageName(), null));
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            }
            return;
        }

        if (player == null) {
            return;
        }

        playerView.setControllerAutoShow(false);
        playerView.hideController();

        final Format format = player.getVideoFormat();

        if (format != null) {
            // https://github.com/google/ExoPlayer/issues/8611
            // TODO: Test/disable on Android 11+
            final View videoSurfaceView = playerView.getVideoSurfaceView();
            if (videoSurfaceView instanceof SurfaceView) {
                ((SurfaceView)videoSurfaceView).getHolder().setFixedSize(format.width, format.height);
            }

            Rational rational = Utils.getRational(format);
            if (Build.VERSION.SDK_INT >= 33 &&
                    getPackageManager().hasSystemFeature(FEATURE_EXPANDED_PICTURE_IN_PICTURE) &&
                    (rational.floatValue() > rationalLimitWide.floatValue() || rational.floatValue() < rationalLimitTall.floatValue())) {
                ((PictureInPictureParams.Builder)mPictureInPictureParamsBuilder).setExpandedAspectRatio(rational);
            }
            if (rational.floatValue() > rationalLimitWide.floatValue())
                rational = rationalLimitWide;
            else if (rational.floatValue() < rationalLimitTall.floatValue())
                rational = rationalLimitTall;

            ((PictureInPictureParams.Builder)mPictureInPictureParamsBuilder).setAspectRatio(rational);
        }
        enterPictureInPictureMode(((PictureInPictureParams.Builder)mPictureInPictureParamsBuilder).build());
    }

    void setEndControlsVisible(boolean visible) {
        final int deleteVisible = (visible && haveMedia && Utils.isDeletable(this, mPrefs.mediaUri)) ? View.VISIBLE : View.INVISIBLE;
        final int nextVisible = (visible && haveMedia && (nextUri != null || (mPrefs.askScope && !isTvBox))) ? View.VISIBLE : View.INVISIBLE;
        findViewById(R.id.delete).setVisibility(deleteVisible);
        findViewById(R.id.next).setVisibility(nextVisible);
    }

    void askDeleteMedia() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(PlayerActivity.this);
        builder.setMessage(getString(R.string.delete_query));
        builder.setPositiveButton(R.string.delete_confirmation, (dialogInterface, i) -> {
            releasePlayer();
            deleteMedia();
            if (nextUri == null) {
                haveMedia = false;
                setEndControlsVisible(false);
                playerView.setControllerShowTimeoutMs(-1);
            } else {
                skipToNext();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {});
        final AlertDialog dialog = builder.create();
        dialog.show();
    }

    void deleteMedia() {
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(mPrefs.mediaUri.getScheme())) {
                DocumentsContract.deleteDocument(getContentResolver(), mPrefs.mediaUri);
            } else if (ContentResolver.SCHEME_FILE.equals(mPrefs.mediaUri.getScheme())) {
                final File file = new File(mPrefs.mediaUri.getSchemeSpecificPart());
                if (file.canWrite()) {
                    file.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void dispatchPlayPause() {
        if (player == null)
            return;

        @Player.State int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || !player.getPlayWhenReady()) {
            shortControllerTimeout = true;
            androidx.media3.common.util.Util.handlePlayButtonAction(player);
        } else {
            androidx.media3.common.util.Util.handlePauseButtonAction(player);
        }
    }

    void skipToNext() {
        if (nextUri != null) {
            releasePlayer();
            mPrefs.updateMedia(this, nextUri, null);
            searchSubtitles();
            initializePlayer();
        }
    }

    void notifyAudioSessionUpdate(final boolean active) {
        final Intent intent = new Intent(active ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                : AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        if (active) {
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE);
        }
        try {
            sendBroadcast(intent);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    void updateButtons(final boolean enable) {
        if (buttonPiP != null) {
            Utils.setButtonEnabled(this, buttonPiP, enable);
        }
        Utils.setButtonEnabled(this, buttonAspectRatio, enable);
        if (isTvBox) {
            Utils.setButtonEnabled(this, exoSettings, true);
        } else {
            Utils.setButtonEnabled(this, exoSettings, enable);
        }
    }

    private void scaleStart() {
        isScaling = true;
        if (playerView.getResizeMode() != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }
        scaleFactor = playerView.getVideoSurfaceView().getScaleX();
        playerView.removeCallbacks(playerView.textClearRunnable);
        playerView.clearIcon();
        playerView.setCustomErrorMessage((int)(scaleFactor * 100) + "%");
        playerView.hideController();
        isScaleStarting = true;
    }

    private void scale(boolean up) {
        if (up) {
            scaleFactor += 0.01;
        } else {
            scaleFactor -= 0.01;
        }
        scaleFactor = Utils.normalizeScaleFactor(scaleFactor, playerView.getScaleFit());
        playerView.setScale(scaleFactor);
        playerView.setCustomErrorMessage((int)(scaleFactor * 100) + "%");
    }

    private void scaleEnd() {
        isScaling = false;
        playerView.postDelayed(playerView.textClearRunnable, 200);
        if (player != null && !player.isPlaying()) {
            playerView.showController();
        }
        if (Math.abs(playerView.getScaleFit() - scaleFactor) < 0.01 / 2) {
            playerView.setScale(1.f);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        updatebuttonAspectRatioIcon();
    }

    private void updatebuttonAspectRatioIcon() {
        if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            buttonAspectRatio.setImageResource(R.drawable.ic_fit_screen_24dp);
        } else {
            buttonAspectRatio.setImageResource(R.drawable.ic_aspect_ratio_24dp);
        }
    }

    private void updateButtonRotation() {
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean auto = false;
        try {
            auto = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1;
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }

        if (mPrefs.orientation == Utils.Orientation.VIDEO) {
            if (auto) {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_rotation_24dp);
            } else if (portrait) {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_portrait_24dp);
            } else {
                buttonRotation.setImageResource(R.drawable.ic_screen_lock_landscape_24dp);
            }
        } else {
            if (auto) {
                buttonRotation.setImageResource(R.drawable.ic_screen_rotation_24dp);
            } else if (portrait) {
                buttonRotation.setImageResource(R.drawable.ic_screen_portrait_24dp);
            } else {
                buttonRotation.setImageResource(R.drawable.ic_screen_landscape_24dp);
            }
        }
    }

    private void showAudioDelayDialog() {
        final long initialSubDelay = subtitleDelayMs;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 20);

        // --- Audio Delay ---
        TextView audioStatus = new TextView(this);
        audioStatus.setTextSize(17);
        audioStatus.setGravity(Gravity.CENTER);
        audioStatus.setText("Audio Delay: " + (audioDelayMs > 0 ? "+" : "") + audioDelayMs + " ms");
        layout.addView(audioStatus);

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        audioRow.setGravity(Gravity.CENTER);
        audioRow.setPadding(0, 12, 0, 24);

        Button aMinus = new Button(this);
        aMinus.setText("-50 ms");
        aMinus.setOnClickListener(v -> {
            audioDelayMs -= 50;
            audioStatus.setText("Audio Delay: " + (audioDelayMs > 0 ? "+" : "") + audioDelayMs + " ms");
        });

        Button aReset = new Button(this);
        aReset.setText("Reset");
        aReset.setOnClickListener(v -> {
            audioDelayMs = 0;
            audioStatus.setText("Audio Delay: 0 ms");
        });

        Button aPlus = new Button(this);
        aPlus.setText("+50 ms");
        aPlus.setOnClickListener(v -> {
            audioDelayMs += 50;
            audioStatus.setText("Audio Delay: " + (audioDelayMs > 0 ? "+" : "") + audioDelayMs + " ms");
        });

        audioRow.addView(aMinus);
        audioRow.addView(aReset);
        audioRow.addView(aPlus);
        layout.addView(audioRow);

        // --- Subtitle Delay ---
        TextView subStatus = new TextView(this);
        subStatus.setTextSize(17);
        subStatus.setGravity(Gravity.CENTER);
        subStatus.setText("Subtitle Delay: " + (subtitleDelayMs > 0 ? "+" : "") + subtitleDelayMs + " ms");
        layout.addView(subStatus);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER);
        subRow.setPadding(0, 12, 0, 0);

        Button sMinus = new Button(this);
        sMinus.setText("-100 ms");
        sMinus.setOnClickListener(v -> {
            subtitleDelayMs -= 100;
            subStatus.setText("Subtitle Delay: " + (subtitleDelayMs > 0 ? "+" : "") + subtitleDelayMs + " ms");
        });

        Button sReset = new Button(this);
        sReset.setText("Reset");
        sReset.setOnClickListener(v -> {
            subtitleDelayMs = 0;
            subStatus.setText("Subtitle Delay: 0 ms");
        });

        Button sPlus = new Button(this);
        sPlus.setText("+100 ms");
        sPlus.setOnClickListener(v -> {
            subtitleDelayMs += 100;
            subStatus.setText("Subtitle Delay: " + (subtitleDelayMs > 0 ? "+" : "") + subtitleDelayMs + " ms");
        });

        subRow.addView(sMinus);
        subRow.addView(sReset);
        subRow.addView(sPlus);
        layout.addView(subRow);

        new AlertDialog.Builder(this)
                .setTitle("Audio & Subtitle Sync")
                .setView(layout)
                .setPositiveButton("Done", (d, w) -> {
                    if (subtitleDelayMs != initialSubDelay && haveMedia && player != null) {
                        boolean wasPlaying = player.isPlaying();
                        savePlayer();
                        restorePlayState = wasPlaying;
                        initializePlayer();
                    }
                })
                .show();
    }

    private boolean isAudioUri(Uri uri) {
        if (uri == null || uri.getPath() == null) return false;
        String p = uri.getPath().toLowerCase();
        return p.endsWith(".flac") || p.endsWith(".mp3") || p.endsWith(".m4a")
                || p.endsWith(".wav") || p.endsWith(".ogg") || p.endsWith(".opus") || p.endsWith(".aac");
    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private void checkChapterSkip() {
        if (player == null || !player.isPlaying() || activeChapters.isEmpty() || btnSkipChapter == null) return;
        long pos = player.getCurrentPosition();
        int idx = ChapterHelper.findChapterIndex(activeChapters, pos);
        if (idx < 0 || idx == lastShownSkipChapterIndex) return;

        lastShownSkipChapterIndex = idx;
        boolean isLast = (idx == activeChapters.size() - 1);

        // If user just skipped directly into the last chapter, offer "Go Back" for 2 seconds
        if (isLast && preSkipPositionMs >= 0) {
            final long returnPos = preSkipPositionMs;
            preSkipPositionMs = -1;
            btnSkipChapter.setText("↩ Go Back");
            btnSkipChapter.setVisibility(View.VISIBLE);
            btnSkipChapter.bringToFront();
            btnSkipChapter.requestFocus();
            btnSkipChapter.setOnClickListener(v -> {
                skipHandler.removeCallbacks(hideSkipRunnable);
                btnSkipChapter.setVisibility(View.GONE);
                lastShownSkipChapterIndex = -1;
                player.seekTo(returnPos);
            });
            skipHandler.removeCallbacks(hideSkipRunnable);
            skipHandler.postDelayed(hideSkipRunnable, 2000);
            return;
        }

        long showMs = ChapterHelper.getSkipShowDurationMs(idx, activeChapters.size());
        if (showMs <= 0) {
            skipHandler.removeCallbacks(hideSkipRunnable);
            btnSkipChapter.setVisibility(View.GONE);
            return;
        }

        if (pos - activeChapters.get(idx).startMs > 15000) return;

        ChapterHelper.ChapterItem ch = activeChapters.get(idx);
        String label = isLast && player.hasNextMediaItem() ? "Next Episode" : "Skip " + ch.title;

        btnSkipChapter.setText(label);
        btnSkipChapter.setVisibility(View.VISIBLE);
        btnSkipChapter.bringToFront();
        btnSkipChapter.requestFocus();
        btnSkipChapter.setOnClickListener(v -> {
            skipHandler.removeCallbacks(hideSkipRunnable);
            btnSkipChapter.setVisibility(View.GONE);
            if (isLast && player.hasNextMediaItem()) {
                player.seekToNextMediaItem();
            } else {
                preSkipPositionMs = player.getCurrentPosition();
                long target = ChapterHelper.getSkipTargetMs(activeChapters, idx, player.getDuration());
                player.seekTo(target);
            }
        });

        skipHandler.removeCallbacks(hideSkipRunnable);
        skipHandler.postDelayed(hideSkipRunnable, showMs);
    }

    private void showChaptersDialog() {
        if (activeChapters.isEmpty()) {
            android.widget.Toast.makeText(this, "No chapters found in this file", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[activeChapters.size()];
        for (int i = 0; i < activeChapters.size(); i++) {
            ChapterHelper.ChapterItem c = activeChapters.get(i);
            labels[i] = formatTime(c.startMs) + "  -  " + c.title;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Chapters")
                .setItems(labels, (d, which) -> {
                    if (player != null) {
                        player.seekTo(activeChapters.get(which).startMs);
                    }
                })
                .show();
    }

    private void showSubtitleSourceDialog() {
        String secLang = SubtitleOnlineHelper.getSecondaryLangName(this);
        String[] options = {
                "Subtitle Appearance & Position",
                "Load Subtitle from File",
                "Search Online - OpenSubtitles (No API Key)",
                "Search Online - SubDL (Needs API key - add manually)",
                "Secondary Language: " + secLang,
                "Set SubDL API Key",
                "Default Audio & Subtitle Languages"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("Add Subtitles")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        SubtitleStyleHelper.showAppearanceDialog(this, () -> updateSubtitleStyle(this));
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, 9912);
                    } else if (which == 2) {
                        showOnlineSubSearchDialog(false);
                    } else if (which == 3) {
                        showOnlineSubSearchDialog(true);
                    } else if (which == 4) {
                        showSecondaryLangDialog();
                    } else if (which == 5) {
                        showSubdlKeyDialog();
                    } else if (which == 6) {
                        showDefaultLanguagesDialog();
                    }
                })
                .show();
    }

    private void showDefaultLanguagesDialog() {
        String[] items = {
                "1st Audio: " + TrackPrefHelper.LANG_LABELS[TrackPrefHelper.getAudio1(this)],
                "2nd Audio: " + TrackPrefHelper.LANG_LABELS[TrackPrefHelper.getAudio2(this)],
                "1st Subtitle: " + TrackPrefHelper.LANG_LABELS[TrackPrefHelper.getSub1(this)],
                "2nd Subtitle: " + TrackPrefHelper.LANG_LABELS[TrackPrefHelper.getSub2(this)],
                "SDH Mode: " + TrackPrefHelper.SUB_SDH_MODES[TrackPrefHelper.getSdhMode(this)]
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("Default Audio & Subtitle Languages")
                .setItems(items, (d, which) -> {
                    if (which == 4) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Subtitle SDH Mode")
                                .setSingleChoiceItems(TrackPrefHelper.SUB_SDH_MODES, TrackPrefHelper.getSdhMode(this), (d2, m) -> {
                                    TrackPrefHelper.setSdhMode(this, m);
                                    TrackPrefHelper.applyToPlayer(this, player);
                                    d2.dismiss();
                                    showDefaultLanguagesDialog();
                                }).show();
                    } else {
                        int cur = which == 0 ? TrackPrefHelper.getAudio1(this)
                                : which == 1 ? TrackPrefHelper.getAudio2(this)
                                : which == 2 ? TrackPrefHelper.getSub1(this)
                                : TrackPrefHelper.getSub2(this);
                        new android.app.AlertDialog.Builder(this)
                                .setTitle(items[which].split(":")[0])
                                .setSingleChoiceItems(TrackPrefHelper.LANG_LABELS, cur, (d2, idx) -> {
                                    if (which == 0) TrackPrefHelper.setAudio1(this, idx);
                                    else if (which == 1) TrackPrefHelper.setAudio2(this, idx);
                                    else if (which == 2) TrackPrefHelper.setSub1(this, idx);
                                    else TrackPrefHelper.setSub2(this, idx);
                                    TrackPrefHelper.applyToPlayer(this, player);
                                    d2.dismiss();
                                    showDefaultLanguagesDialog();
                                }).show();
                    }
                })
                .setPositiveButton("Done", null)
                .show();
    }
    
    private void showSecondaryLangDialog() {
        int current = SubtitleOnlineHelper.getSecondaryLangIndex(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Secondary Subtitle Language (Default: English)")
                .setSingleChoiceItems(SubtitleOnlineHelper.LANG_NAMES, current, (d, which) -> {
                    SubtitleOnlineHelper.saveSecondaryLangIndex(this, which);
                    android.widget.Toast.makeText(this, "Secondary: " + SubtitleOnlineHelper.LANG_NAMES[which], android.widget.Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSubdlKeyDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Paste SubDL API Key");
        et.setText(SubtitleOnlineHelper.getApiKey(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle("SubDL API Key (subdl.com/panel/api)")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> SubtitleOnlineHelper.saveApiKey(this, et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showOnlineSubSearchDialog(boolean useSubdl) {
        String apiKey = SubtitleOnlineHelper.getApiKey(this);
        if (useSubdl && apiKey.isEmpty()) {
            android.widget.Toast.makeText(this, "SubDL needs an API key. Add it manually first.", android.widget.Toast.LENGTH_LONG).show();
            showSubdlKeyDialog();
            return;
        }
        android.widget.EditText et = new android.widget.EditText(this);
        String defaultTitle = getIntent() != null ? getIntent().getStringExtra(API_TITLE) : "";
        et.setText(SubtitleOnlineHelper.cleanQuery(defaultTitle));
        et.setHint("Movie or Series Name");

        String provider = useSubdl ? "SubDL" : "OpenSubtitles";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Search " + provider)
                .setView(et)
                .setPositiveButton("Search", (d, w) -> {
                    String q = et.getText().toString().trim();
                    if (q.isEmpty()) return;
                    android.widget.Toast.makeText(this, "Searching " + provider + "...", android.widget.Toast.LENGTH_SHORT).show();

                    SubtitleOnlineHelper.SearchCallback cb = (results, err) -> {
                        if (err != null || results.isEmpty()) {
                            android.widget.Toast.makeText(this, err != null ? "Error: " + err : "No subtitles found", android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        String[] items = new String[results.size()];
                        for (int i = 0; i < results.size(); i++) items[i] = results.get(i).title;
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Select Subtitle (" + provider + ")")
                                .setItems(items, (d2, idx) -> {
                                    android.widget.Toast.makeText(this, "Downloading...", android.widget.Toast.LENGTH_SHORT).show();
                                    SubtitleOnlineHelper.downloadAndExtract(this, results.get(idx), (file, dlErr) -> {
                                        if (dlErr != null || file == null) {
                                            android.widget.Toast.makeText(this, "Download failed: " + dlErr, android.widget.Toast.LENGTH_LONG).show();
                                        } else {
                                            attachExternalSubtitle(Uri.fromFile(file));
                                        }
                                    });
                                })
                                .show();
                    };

                    if (useSubdl) {
                        SubtitleOnlineHelper.searchSubdl(this, apiKey, q, cb);
                    } else {
                        SubtitleOnlineHelper.searchOpenSubtitles(this, q, cb);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void attachExternalSubtitle(Uri subUri) {
        if (player == null || player.getCurrentMediaItem() == null) return;
        long pos = player.getCurrentPosition();
        String path = subUri.toString().toLowerCase();
        String mime = androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
        if (path.endsWith(".ass") || path.endsWith(".ssa")) mime = androidx.media3.common.MimeTypes.TEXT_SSA;
        else if (path.endsWith(".vtt")) mime = androidx.media3.common.MimeTypes.TEXT_VTT;

        MediaItem.SubtitleConfiguration subConfig = new MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mime)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
        MediaItem updated = player.getCurrentMediaItem().buildUpon()
                .setSubtitleConfigurations(java.util.Collections.singletonList(subConfig))
                .build();
        player.replaceMediaItem(player.getCurrentMediaItemIndex(), updated);
        player.seekTo(pos);
        android.widget.Toast.makeText(this, "Subtitle loaded", android.widget.Toast.LENGTH_SHORT).show();
    }
    
    private void applyPlaybackControlsPosition() {
        View centerControls = findViewById(androidx.media3.ui.R.id.exo_center_controls);
        View blControls = findViewById(R.id.bottom_left_controls);
        View exoTime = findViewById(androidx.media3.ui.R.id.exo_time);
        View exoProgress = findViewById(androidx.media3.ui.R.id.exo_progress);

        boolean bottomLeft = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("controls_bottom_left", true);

        if (centerControls != null) {
            android.view.ViewGroup.LayoutParams rawLp = centerControls.getLayoutParams();
            if (rawLp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) rawLp;
                lp.gravity = android.view.Gravity.CENTER;
                lp.setMargins(0, 0, 0, 0);
                centerControls.setLayoutParams(lp);
            }
            centerControls.setVisibility(bottomLeft ? View.GONE : View.VISIBLE);
            centerControls.setAlpha(bottomLeft ? 0f : 1f);
            centerControls.setScaleX(bottomLeft ? 0f : 1f);
            centerControls.setScaleY(bottomLeft ? 0f : 1f);
            centerControls.setEnabled(!bottomLeft);
            if (centerControls instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) centerControls).setDescendantFocusability(
                        bottomLeft ? android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS : android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }

            // Force center Prev/Next visible when Center mode is active
            View centerPrev = centerControls.findViewById(androidx.media3.ui.R.id.exo_prev);
            View centerNext = centerControls.findViewById(androidx.media3.ui.R.id.exo_next);
            if (!bottomLeft) {
                if (centerPrev != null) {
                    centerPrev.setVisibility(View.VISIBLE);
                    centerPrev.setOnClickListener(v -> {
                        if (player != null) {
                            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
                            else player.seekTo(0);
                        }
                    });
                }
                if (centerNext != null) {
                    centerNext.setVisibility(View.VISIBLE);
                    centerNext.setOnClickListener(v -> {
                        if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();
                    });
                }
            }
        }

        if (blControls != null) {
            blControls.setVisibility(bottomLeft ? View.VISIBLE : View.GONE);
        }
        if (exoTime != null) {
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) exoTime.getLayoutParams();
            float d = getResources().getDisplayMetrics().density;
            lp.setMarginStart(bottomLeft ? (int) (136 * d) : 0);
            exoTime.setLayoutParams(lp);
        }

        // Pressing DOWN from seek bar lands directly on Bottom-Left Play/Pause
        if (exoProgress != null) {
            exoProgress.setNextFocusDownId(bottomLeft ? R.id.btn_bl_play_pause : View.NO_ID);
        }

        View btnPrev = findViewById(R.id.btn_bl_prev);
        View btnNext = findViewById(R.id.btn_bl_next);
        android.widget.ImageButton btnPlayPause = findViewById(R.id.btn_bl_play_pause);

        if (btnPrev != null) {
            btnPrev.setClickable(true);
            btnPrev.setOnClickListener(v -> {
                if (player != null) {
                    if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
                    else player.seekTo(0);
                }
            });
        }
        if (btnNext != null) {
            btnNext.setClickable(true);
            btnNext.setOnClickListener(v -> {
                if (player != null && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                }
            });
        }
        if (btnPlayPause != null) {
            btnPlayPause.setClickable(true);
            btnPlayPause.setImageResource(player != null && player.isPlaying()
                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            btnPlayPause.setOnClickListener(v -> {
                if (player != null) {
                    if (player.isPlaying()) player.pause();
                    else player.play();
                }
            });
        }
    }
    
    public static void stopBackgroundAudio() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    public static boolean isBackgroundAudioPlaying() {
        return player != null && player.isPlaying();
    }

    public static Uri getBackgroundAudioUri() {
        if (player != null && player.getCurrentMediaItem() != null
                && player.getCurrentMediaItem().localConfiguration != null) {
            return player.getCurrentMediaItem().localConfiguration.uri;
        }
        return null;
    }


    private Uri getActiveMediaUri() {
        if (player != null && player.getCurrentMediaItem() != null
                && player.getCurrentMediaItem().localConfiguration != null
                && player.getCurrentMediaItem().localConfiguration.uri != null) {
            return player.getCurrentMediaItem().localConfiguration.uri;
        }
        if (mPrefs != null && mPrefs.mediaUri != null) {
            return mPrefs.mediaUri;
        }
        return getIntent() != null ? getIntent().getData() : null;
    }

    private void saveVideoResumePosition() {
        Uri uri = getActiveMediaUri();
        if (player == null || uri == null || isAudioUri(uri)) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        android.content.SharedPreferences rsp = getSharedPreferences("jsplayer_resume", MODE_PRIVATE);
        if (dur > 0 && pos > 5000 && pos < dur - 10000) {
            rsp.edit().putLong(uri.toString(), pos).apply();
        } else if (dur > 0 && pos >= dur - 10000) {
            rsp.edit().remove(uri.toString()).apply();
        }
    }

    private boolean hasSavedVideoResume() {
        if (suppressResumeDialogOnce) return false;
        Uri uri = getActiveMediaUri();
        if (uri == null || isAudioUri(uri)) return false;
        long savedPos = getSharedPreferences("jsplayer_resume", MODE_PRIVATE)
                .getLong(uri.toString(), 0);
        return savedPos > 5000;
    }

    private void checkAndShowVideoResumeDialog() {
        if (suppressResumeDialogOnce) {
            suppressResumeDialogOnce = false;
            return;
        }
        if (resumeDialogShownForCurrentItem || (activeResumeDialog != null && activeResumeDialog.isShowing())) return;
        Uri uri = getActiveMediaUri();
        if (player == null || uri == null || isAudioUri(uri)) return;

        long savedPos = getSharedPreferences("jsplayer_resume", MODE_PRIVATE)
                .getLong(uri.toString(), 0);
        if (savedPos <= 5000) return;

        resumeDialogShownForCurrentItem = true;
        isResumeDialogOpen = true;
        player.setPlayWhenReady(false);
        player.pause();

        long sec = savedPos / 1000;
        String timeStr = String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);

        activeResumeDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Resume Playback?")
                .setMessage("Continue from " + timeStr + "?")
                .setPositiveButton("Resume", (d, w) -> {
                    activeResumeDialog = null;
                    isResumeDialogOpen = false;
                    if (player != null) {
                        player.seekTo(savedPos);
                        player.setPlayWhenReady(true);
                        player.play();
                    }
                })
                .setNegativeButton("Start Over", (d, w) -> {
                    activeResumeDialog = null;
                    isResumeDialogOpen = false;
                    if (player != null) {
                        player.seekTo(0);
                        player.setPlayWhenReady(true);
                        player.play();
                    }
                })
                .setCancelable(false)
                .show();

        android.widget.Button posBtn = activeResumeDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) posBtn.requestFocus();
    }
}
