/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.hardware.fingerprint.FingerprintManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.service.media.CameraPrewarmService;
import android.telecom.TelecomManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.EventLogConstants;
import com.android.systemui.EventLogTags;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.cm.LockscreenShortcutsHelper;
import com.android.systemui.cm.LockscreenShortcutsHelper.Shortcuts;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.PreviewInflater;

import java.util.Objects;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout implements View.OnClickListener,
        UnlockMethodCache.OnUnlockMethodChangedListener, LockscreenShortcutsHelper.OnChangeListener,
        AccessibilityController.AccessibilityStateChangedCallback, View.OnLongClickListener {

    final static String TAG = "PhoneStatusBar/KeyguardBottomAreaView";

    public static final String CAMERA_LAUNCH_SOURCE_AFFORDANCE = "lockscreen_affordance";
    public static final String CAMERA_LAUNCH_SOURCE_WIGGLE = "wiggle_gesture";
    public static final String CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = "power_double_tap";
    public static final String CAMERA_LAUNCH_SOURCE_SCREEN_GESTURE = "screen_gesture";

    public static final String EXTRA_CAMERA_LAUNCH_SOURCE
            = "com.android.systemui.camera_launch_source";

    private static final Intent SECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    public static final Intent INSECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    private static final Intent PHONE_INTENT = new Intent(Intent.ACTION_DIAL);
    private static final int DOZE_ANIMATION_STAGGER_DELAY = 48;
    private static final int DOZE_ANIMATION_ELEMENT_DURATION = 250;

    private KeyguardAffordanceView mCameraImageView;
    private KeyguardAffordanceView mLeftAffordanceView;
    private LockIcon mLockIcon;
    private TextView mIndicationText;
    private ViewGroup mPreviewContainer;

    private View mLeftPreview;
    private View mCameraPreview;

    private ActivityStarter mActivityStarter;
    private UnlockMethodCache mUnlockMethodCache;
    private LockPatternUtils mLockPatternUtils;
    private FlashlightController mFlashlightController;
    private PreviewInflater mPreviewInflater;
    private KeyguardIndicationController mIndicationController;
    private AccessibilityController mAccessibilityController;
    private PhoneStatusBar mPhoneStatusBar;
    private KeyguardAffordanceHelper mAffordanceHelper;
    private LockscreenShortcutsHelper mShortcutHelper;
    private final ColorMatrixColorFilter mGrayScaleFilter;

    private boolean mUserSetupComplete;
    private boolean mPrewarmBound;
    private Messenger mPrewarmMessenger;
    private Intent mLastCameraIntent;

    private final ServiceConnection mPrewarmConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mPrewarmMessenger = new Messenger(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mPrewarmMessenger = null;
        }
    };

    private AssistManager mAssistManager;

    public KeyguardBottomAreaView(Context context) {
        this(context, null);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        mGrayScaleFilter = new ColorMatrixColorFilter(cm);
    }

    private AccessibilityDelegate mAccessibilityDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            String label = null;
            if (host == mLockIcon) {
                label = getResources().getString(R.string.unlock_label);
            } else if (host == mCameraImageView) {
                if (isTargetCustom(Shortcuts.RIGHT_SHORTCUT)) {
                    label = mShortcutHelper.getFriendlyNameForUri(Shortcuts.RIGHT_SHORTCUT);
                } else {
                    label = getResources().getString(R.string.camera_label);
                }
            } else if (host == mLeftAffordanceView) {
                if (isTargetCustom(Shortcuts.LEFT_SHORTCUT)) {
                    label = mShortcutHelper.getFriendlyNameForUri(Shortcuts.LEFT_SHORTCUT);
                } else {
                    if (isLeftVoiceAssist()) {
                        label = getResources().getString(R.string.voice_assist_label);
                    } else {
                        label = getResources().getString(R.string.phone_label);
                    }
                }
            }
            info.addAction(new AccessibilityAction(ACTION_CLICK, label));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == ACTION_CLICK) {
                if (host == mLockIcon) {
                    mPhoneStatusBar.animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */);
                    return true;
                } else if (host == mCameraImageView) {
                    launchCamera(CAMERA_LAUNCH_SOURCE_AFFORDANCE);
                    return true;
                } else if (host == mLeftAffordanceView) {
                    launchLeftAffordance();
                    return true;
                }
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mPreviewContainer = (ViewGroup) findViewById(R.id.preview_container);
        mCameraImageView = (KeyguardAffordanceView) findViewById(R.id.camera_button);
        mLeftAffordanceView = (KeyguardAffordanceView) findViewById(R.id.left_button);
        mLockIcon = (LockIcon) findViewById(R.id.lock_icon);
        mIndicationText = (TextView) findViewById(R.id.keyguard_indication_text);
        mShortcutHelper = new LockscreenShortcutsHelper(mContext, this);
        watchForCameraPolicyChanges();
        updateCameraVisibility();
        updateLeftButtonVisibility();
        mUnlockMethodCache = UnlockMethodCache.getInstance(getContext());
        mUnlockMethodCache.addListener(this);
        mLockIcon.update();
        setClipChildren(false);
        setClipToPadding(false);
        mPreviewInflater = new PreviewInflater(mContext, new LockPatternUtils(mContext));
        mLockIcon.setOnClickListener(this);
        mLockIcon.setOnLongClickListener(this);
        mCameraImageView.setOnClickListener(this);
        mLeftAffordanceView.setOnClickListener(this);
        initAccessibility();
        updateCustomShortcuts();
    }

    private void updateCustomShortcuts() {
        updateLeftAffordance();
        updateRightAffordance();
    }

    private void updateRightAffordanceIcon() {
        Drawable drawable;
        String contentDescription;
        boolean shouldGrayScale = false;
        boolean visible = mUserSetupComplete && !NotificationPanelView.isQSEventBlocked();
        if (isTargetCustom(Shortcuts.RIGHT_SHORTCUT)) {
            drawable = mShortcutHelper.getDrawableForTarget(Shortcuts.RIGHT_SHORTCUT);
            shouldGrayScale = true;
            contentDescription = mShortcutHelper.getFriendlyNameForUri(Shortcuts.RIGHT_SHORTCUT);
            visible |= !mShortcutHelper.isTargetEmpty(Shortcuts.RIGHT_SHORTCUT);
        } else {
            drawable = mContext.getDrawable(R.drawable.ic_camera_alt_24dp);
            contentDescription = mContext.getString(R.string.accessibility_camera_button);
        }
        mCameraImageView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mCameraImageView.setImageDrawable(drawable);
        mCameraImageView.setContentDescription(contentDescription);
        mCameraImageView.setDefaultFilter(shouldGrayScale ? mGrayScaleFilter : null);
        updateCameraVisibility();
    }

    private void initAccessibility() {
        mLockIcon.setAccessibilityDelegate(mAccessibilityDelegate);
        mLeftAffordanceView.setAccessibilityDelegate(mAccessibilityDelegate);
        mCameraImageView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int indicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        MarginLayoutParams mlp = (MarginLayoutParams) mIndicationText.getLayoutParams();
        if (mlp.bottomMargin != indicationBottomMargin) {
            mlp.bottomMargin = indicationBottomMargin;
            mIndicationText.setLayoutParams(mlp);
        }

        // Respect font size setting.
        mIndicationText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));

        ViewGroup.LayoutParams lp = mCameraImageView.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_height);
        mCameraImageView.setLayoutParams(lp);
        updateRightAffordanceIcon();

        lp = mLockIcon.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_height);
        mLockIcon.setLayoutParams(lp);
        mLockIcon.update(true /* force */);

        lp = mLeftAffordanceView.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_height);
        mLeftAffordanceView.setLayoutParams(lp);
        updateLeftAffordanceIcon();
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public void setFlashlightController(FlashlightController flashlightController) {
        mFlashlightController = flashlightController;
    }

    public void setAccessibilityController(AccessibilityController accessibilityController) {
        mAccessibilityController = accessibilityController;
        mLockIcon.setAccessibilityController(accessibilityController);
        accessibilityController.addStateChangedCallback(this);
    }

    public void setPhoneStatusBar(PhoneStatusBar phoneStatusBar) {
        mPhoneStatusBar = phoneStatusBar;
        updateCameraVisibility(); // in case onFinishInflate() was called too early
        updateLeftButtonVisibility();
    }

    public void setAffordanceHelper(KeyguardAffordanceHelper affordanceHelper) {
        mAffordanceHelper = affordanceHelper;
    }

    public void setUserSetupComplete(boolean userSetupComplete) {
        mUserSetupComplete = userSetupComplete;
        updateCameraVisibility();
        updateRightAffordanceIcon();
        updateLeftButtonVisibility();
        updateLeftAffordanceIcon();
    }

    private Intent getCameraIntent() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        boolean canSkipBouncer = updateMonitor.getUserCanSkipBouncer(
                KeyguardUpdateMonitor.getCurrentUser());
        boolean secure = mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser());
        return (secure && !canSkipBouncer) ? SECURE_CAMERA_INTENT : INSECURE_CAMERA_INTENT;
    }

    /**
     * Resolves the intent to launch the camera application.
     */
    public ResolveInfo resolveCameraIntent() {
        return mContext.getPackageManager().resolveActivityAsUser(getCameraIntent(),
                PackageManager.MATCH_DEFAULT_ONLY,
                KeyguardUpdateMonitor.getCurrentUser());
    }

    private void updateLeftButtonVisibility() {
        if (mLeftAffordanceView == null) {
            return;
        }
        boolean visible = mUserSetupComplete && !NotificationPanelView.isQSEventBlocked();
        if (visible) {
            if (isTargetCustom(Shortcuts.LEFT_SHORTCUT)) {
                visible = !mShortcutHelper.isTargetEmpty(Shortcuts.LEFT_SHORTCUT);
            } else if (canLaunchVoiceAssist()) {
                // Display left shortcut
            } else {
                visible = isPhoneVisible();
            }
        }
        mLeftAffordanceView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateCameraVisibility() {
        if (mCameraImageView == null) {
            // Things are not set up yet; reply hazy, ask again later
            return;
        }
        boolean visible = mUserSetupComplete && !NotificationPanelView.isQSEventBlocked();
        if (visible) {
            if (isTargetCustom(Shortcuts.RIGHT_SHORTCUT)) {
                visible = !mShortcutHelper.isTargetEmpty(Shortcuts.RIGHT_SHORTCUT);
            } else {
                ResolveInfo resolved = resolveCameraIntent();
                boolean isCameraDisabled =
                        (mPhoneStatusBar != null) && !mPhoneStatusBar.isCameraAllowedByAdmin();
                visible = !isCameraDisabled && resolved != null
                        && getResources().getBoolean(R.bool.config_keyguardShowCameraAffordance);
            }
        }
        mCameraImageView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateLeftAffordanceIcon() {
        Drawable drawable;
        String contentDescription;
        boolean shouldGrayScale = false;
        boolean visible = mUserSetupComplete && !NotificationPanelView.isQSEventBlocked();
        if (mShortcutHelper.isTargetCustom(Shortcuts.LEFT_SHORTCUT)) {
            drawable = mShortcutHelper.getDrawableForTarget(Shortcuts.LEFT_SHORTCUT);
            shouldGrayScale = true;
            contentDescription = mShortcutHelper.getFriendlyNameForUri(Shortcuts.LEFT_SHORTCUT);
            visible |= !mShortcutHelper.isTargetEmpty(Shortcuts.LEFT_SHORTCUT);
        } else if (canLaunchVoiceAssist()) {
            drawable = mContext.getDrawable(R.drawable.ic_mic_26dp);
            contentDescription = mContext.getString(R.string.accessibility_voice_assist_button);
        } else {
            visible &= isPhoneVisible();
            drawable = mContext.getDrawable(R.drawable.ic_phone_24dp);
            contentDescription = mContext.getString(R.string.accessibility_phone_button);
        }
        mLeftAffordanceView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mLeftAffordanceView.setImageDrawable(drawable);
        mLeftAffordanceView.setContentDescription(contentDescription);
        mLeftAffordanceView.setDefaultFilter(shouldGrayScale ? mGrayScaleFilter : null);
        updateLeftButtonVisibility();
    }

    public boolean isLeftVoiceAssist() {
        return !isTargetCustom(Shortcuts.LEFT_SHORTCUT) && canLaunchVoiceAssist();
    }

    private boolean isPhoneVisible() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && pm.resolveActivity(PHONE_INTENT, 0) != null;
    }

    private void watchForCameraPolicyChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        getContext().registerReceiverAsUser(mDevicePolicyReceiver,
                UserHandle.ALL, filter, null, null);
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallback);
    }

    @Override
    public void onStateChanged(boolean accessibilityEnabled, boolean touchExplorationEnabled) {
        mCameraImageView.setClickable(touchExplorationEnabled);
        mLeftAffordanceView.setClickable(touchExplorationEnabled);
        mCameraImageView.setFocusable(accessibilityEnabled);
        mLeftAffordanceView.setFocusable(accessibilityEnabled);
        mLockIcon.update();
    }

    @Override
    public void onClick(View v) {
        if (v == mCameraImageView) {
            launchCamera(CAMERA_LAUNCH_SOURCE_AFFORDANCE);
        } else if (v == mLeftAffordanceView) {
            launchLeftAffordance();
        } if (v == mLockIcon) {
            if (!mAccessibilityController.isAccessibilityEnabled()) {
                handleTrustCircleClick();
            } else {
                mPhoneStatusBar.animateCollapsePanels(
                        CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        handleTrustCircleClick();
        return true;
    }

    private void handleTrustCircleClick() {
        EventLogTags.writeSysuiLockscreenGesture(
                EventLogConstants.SYSUI_LOCKSCREEN_GESTURE_TAP_LOCK, 0 /* lengthDp - N/A */,
                0 /* velocityDp - N/A */);
        mIndicationController.showTransientIndication(
                R.string.keyguard_indication_trust_disabled);
        mLockPatternUtils.requireCredentialEntry(KeyguardUpdateMonitor.getCurrentUser());
    }

    public void bindCameraPrewarmService() {
        Intent intent = getCameraIntent();
        ActivityInfo targetInfo = PreviewInflater.getTargetActivityInfo(mContext, intent,
                KeyguardUpdateMonitor.getCurrentUser(), true /* onlyDirectBootAware */);
        if (targetInfo != null && targetInfo.metaData != null) {
            String clazz = targetInfo.metaData.getString(
                    MediaStore.META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE);
            if (clazz != null) {
                Intent serviceIntent = new Intent();
                serviceIntent.setClassName(targetInfo.packageName, clazz);
                serviceIntent.setAction(CameraPrewarmService.ACTION_PREWARM);
                try {
                    if (getContext().bindServiceAsUser(serviceIntent, mPrewarmConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                            new UserHandle(UserHandle.USER_CURRENT))) {
                        mPrewarmBound = true;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Unable to bind to prewarm service package=" + targetInfo.packageName
                            + " class=" + clazz, e);
                }
            }
        }
    }

    public void unbindCameraPrewarmService(boolean launched) {
        if (mPrewarmBound) {
            if (mPrewarmMessenger != null && launched) {
                try {
                    mPrewarmMessenger.send(Message.obtain(null /* handler */,
                            CameraPrewarmService.MSG_CAMERA_FIRED));
                } catch (RemoteException e) {
                    Log.w(TAG, "Error sending camera fired message", e);
                }
            }
            mContext.unbindService(mPrewarmConnection);
            mPrewarmBound = false;
        }
    }

    public void launchCamera(String source) {
        final Intent intent;
        if (source.equals(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) ||
                source.equals(CAMERA_LAUNCH_SOURCE_SCREEN_GESTURE) ||
                !mShortcutHelper.isTargetCustom(LockscreenShortcutsHelper.Shortcuts.RIGHT_SHORTCUT)) {
            intent = getCameraIntent();
        } else {
            intent = mShortcutHelper.getIntent(LockscreenShortcutsHelper.Shortcuts.RIGHT_SHORTCUT);
            intent.putExtra(EXTRA_CAMERA_LAUNCH_SOURCE, source);
        }
        boolean wouldLaunchResolverActivity = PreviewInflater.wouldLaunchResolverActivity(
                mContext, intent, KeyguardUpdateMonitor.getCurrentUser());
        if (intent == SECURE_CAMERA_INTENT && !wouldLaunchResolverActivity) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    int result = ActivityManager.START_CANCELED;

                    // Normally an activity will set it's requested rotation
                    // animation on its window. However when launching an activity
                    // causes the orientation to change this is too late. In these cases
                    // the default animation is used. This doesn't look good for
                    // the camera (as it rotates the camera contents out of sync
                    // with physical reality). So, we ask the WindowManager to
                    // force the crossfade animation if an orientation change
                    // happens to occur during the launch.
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setRotationAnimationHint(
                            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS);
                    try {
                        result = ActivityManagerNative.getDefault().startActivityAsUser(
                                null, getContext().getBasePackageName(),
                                intent,
                                intent.resolveTypeIfNeeded(getContext().getContentResolver()),
                                null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, o.toBundle(),
                                UserHandle.CURRENT.getIdentifier());
                    } catch (RemoteException e) {
                        Log.w(TAG, "Unable to start camera activity", e);
                    }
                    mActivityStarter.preventNextAnimation();
                    final boolean launched = isSuccessfulLaunch(result);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            unbindCameraPrewarmService(launched);
                        }
                    });
                }
            });
        } else {

            // We need to delay starting the activity because ResolverActivity finishes itself if
            // launched behind lockscreen.
            mActivityStarter.startActivity(intent, false /* dismissShade */,
                    new ActivityStarter.Callback() {
                        @Override
                        public void onActivityStarted(int resultCode) {
                            unbindCameraPrewarmService(isSuccessfulLaunch(resultCode));
                        }
                    });
        }
    }

    private static boolean isSuccessfulLaunch(int result) {
        return result == ActivityManager.START_SUCCESS
                || result == ActivityManager.START_DELIVERED_TO_TOP
                || result == ActivityManager.START_TASK_TO_FRONT;
    }

    public void launchLeftAffordance() {
        if (mShortcutHelper.isTargetCustom(Shortcuts.LEFT_SHORTCUT)) {
            Intent intent = mShortcutHelper.getIntent(Shortcuts.LEFT_SHORTCUT);
            mActivityStarter.startActivity(intent, false /* dismissShade */);
        } else if (isLeftVoiceAssist()) {
            launchVoiceAssist();
        } else {
            launchPhone();
        }
    }

    private void launchVoiceAssist() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                mAssistManager.launchVoiceAssistFromKeyguard();
                mActivityStarter.preventNextAnimation();
            }
        };
        if (mPhoneStatusBar.isKeyguardCurrentlySecure()) {
            AsyncTask.execute(runnable);
        } else {
            mPhoneStatusBar.executeRunnableDismissingKeyguard(runnable, null /* cancelAction */,
                    false /* dismissShade */, false /* afterKeyguardGone */, true /* deferred */);
        }
    }

    private boolean canLaunchVoiceAssist() {
        if (mAssistManager == null) {
            return false;
        }
        return mAssistManager.canVoiceAssistBeLaunchedFromKeyguard();
    }

    private void launchPhone() {
        final TelecomManager tm = TelecomManager.from(mContext);
        if (tm.isInCall()) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    tm.showInCallScreen(false /* showDialpad */);
                }
            });
        } else {
            mActivityStarter.startActivity(PHONE_INTENT, false /* dismissShade */);
        }
    }


    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            mLockIcon.update();
            updateCameraVisibility();
            updateLeftButtonVisibility();
        }
    }

    public KeyguardAffordanceView getLeftView() {
        return mLeftAffordanceView;
    }

    public KeyguardAffordanceView getRightView() {
        return mCameraImageView;
    }

    public View getLeftPreview() {
        return mLeftPreview;
    }

    public View getRightPreview() {
        return mCameraPreview;
    }

    public LockIcon getLockIcon() {
        return mLockIcon;
    }

    public View getIndicationView() {
        return mIndicationText;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onUnlockMethodStateChanged() {
        mLockIcon.update();
        updateCameraVisibility();
        updateLeftButtonVisibility();
    }

    private void inflateCameraPreview() {
        if (isTargetCustom(Shortcuts.RIGHT_SHORTCUT)) {
            mPreviewContainer.removeView(mCameraPreview);
        } else {
            Intent cameraIntent = getCameraIntent();
            if (!Objects.equals(cameraIntent, mLastCameraIntent)) {
                if (mCameraPreview != null) {
                    mPreviewContainer.removeView(mCameraPreview);
                }
                mCameraPreview = mPreviewInflater.inflatePreview(cameraIntent);
                if (mCameraPreview != null) {
                    mPreviewContainer.addView(mCameraPreview);
                }
            }
            mLastCameraIntent = cameraIntent;
            if (mCameraPreview != null) {
                mCameraPreview.setVisibility(View.GONE);
            }
        }
        if (mAffordanceHelper != null) {
            mAffordanceHelper.updatePreviews();
        }
    }

    private void updateLeftPreview() {
        View previewBefore = mLeftPreview;
        if (previewBefore != null) {
            mPreviewContainer.removeView(previewBefore);
        }
        if (isTargetCustom(Shortcuts.LEFT_SHORTCUT)) {
            // Custom shortcuts don't support previews
            return;
        }
        if (isLeftVoiceAssist()) {
            mLeftPreview = mPreviewInflater.inflatePreviewFromService(
                    mAssistManager.getVoiceInteractorComponentName());
        } else {
            mLeftPreview = mPreviewInflater.inflatePreview(PHONE_INTENT);
        }
        if (mLeftPreview != null) {
            mPreviewContainer.addView(mLeftPreview);
            mLeftPreview.setVisibility(View.INVISIBLE);
        }
        if (mAffordanceHelper != null) {
            mAffordanceHelper.updatePreviews();
        }
    }

    public void startFinishDozeAnimation() {
        long delay = 0;
        if (mLeftAffordanceView.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mLeftAffordanceView, delay);
            delay += DOZE_ANIMATION_STAGGER_DELAY;
        }
        startFinishDozeAnimationElement(mLockIcon, delay);
        delay += DOZE_ANIMATION_STAGGER_DELAY;
        if (mCameraImageView.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mCameraImageView, delay);
        }
        mIndicationText.setAlpha(0f);
        mIndicationText.animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                .setDuration(NotificationPanelView.DOZE_ANIMATION_DURATION);
    }

    private void startFinishDozeAnimationElement(View element, long delay) {
        element.setAlpha(0f);
        element.setTranslationY(element.getHeight() / 2);
        element.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                .setStartDelay(delay)
                .setDuration(DOZE_ANIMATION_ELEMENT_DURATION);
    }

    private final BroadcastReceiver mDevicePolicyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            post(new Runnable() {
                @Override
                public void run() {
                    updateCameraVisibility();
                    updateLeftButtonVisibility();
                }
            });
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitchComplete(int userId) {
            updateCameraVisibility();
            updateLeftButtonVisibility();
        }

        @Override
        public void onStartedWakingUp() {
            mLockIcon.setDeviceInteractive(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            mLockIcon.setDeviceInteractive(false);
        }

        @Override
        public void onScreenTurnedOn() {
            mLockIcon.setScreenOn(true);
        }

        @Override
        public void onScreenTurnedOff() {
            mLockIcon.setScreenOn(false);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mLockIcon.update();
        }

        @Override
        public void onFingerprintRunningStateChanged(boolean running) {
            mLockIcon.update();
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            mLockIcon.update();
        }

        @Override
        public void onUserUnlocked() {
            updateLeftAffordance();
            updateRightAffordance();
        }
    };

    public void setKeyguardIndicationController(
            KeyguardIndicationController keyguardIndicationController) {
        mIndicationController = keyguardIndicationController;
    }

    public void setAssistManager(AssistManager assistManager) {
        mAssistManager = assistManager;
        updateLeftAffordance();
    }

    public void updateLeftAffordance() {
        updateLeftAffordanceIcon();
        updateLeftPreview();
    }

    public void updateRightAffordance() {
        updateRightAffordanceIcon();
        inflateCameraPreview();
    }

    public void onKeyguardShowingChanged() {
        updateLeftAffordance();
        updateRightAffordance();
    }

    private String getIndexHint(LockscreenShortcutsHelper.Shortcuts shortcut) {
        if (mShortcutHelper.isTargetCustom(shortcut)) {
            String label = mShortcutHelper.getFriendlyNameForUri(shortcut);
            int resId = 0;
            resId = R.string.shortcut_hint;
            return mContext.getString(resId, label);
        } else {
            return null;
        }
    }

    public String getLeftHint() {
        String label = getIndexHint(LockscreenShortcutsHelper.Shortcuts.LEFT_SHORTCUT);
        if (label == null) {
            if (isLeftVoiceAssist()) {
                label = mContext.getString(R.string.voice_hint);
            } else {
                label = mContext.getString(R.string.phone_hint);
            }
        }
        return label;
    }

    public String getRightHint() {
        String label = getIndexHint(LockscreenShortcutsHelper.Shortcuts.RIGHT_SHORTCUT);
        if (label == null) {
            label = mContext.getString(R.string.camera_hint);
        }
        return label;
    }

    public boolean isTargetCustom(LockscreenShortcutsHelper.Shortcuts shortcut) {
        return mShortcutHelper.isTargetCustom(shortcut);
    }

    @Override
    public void onChange() {
        updateCustomShortcuts();
    }
}
