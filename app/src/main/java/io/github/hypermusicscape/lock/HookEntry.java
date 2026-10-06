package io.github.hypermusicscape.lock;

import android.os.Build;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.WeakHashMap;

/** The only ROM-specific hook. No code is imported from HyperMusicCover. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "HyperMusicScapeLock";
    private static final WeakHashMap<View, LockScreenOverlay> overlays = new WeakHashMap<>();
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName) ||
                !Config.FINGERPRINT.equals(Build.FINGERPRINT)) return;
        try {
            Class<?> root = XposedHelpers.findClass(
                    "com.android.keyguard.widget.HyperOSKeyguardRootView", param.classLoader);
            XposedHelpers.findAndHookConstructor(root, android.content.Context.class,
                    android.util.AttributeSet.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            View view = (View) hook.thisObject;
                            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                                @Override public void onViewAttachedToWindow(View attached) {
                                    attached.post(() -> attach(attached));
                                }
                                @Override public void onViewDetachedFromWindow(View detached) {
                                    LockScreenOverlay overlay = overlays.remove(detached);
                                    if (overlay != null) overlay.destroy();
                                }
                            });
                        }
                    });
            XposedBridge.log(TAG + ": hook armed for exact OS3 build");
            Log.i(TAG, "SystemUI root constructor hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": incompatible hook; native lock screen retained: " + error);
            Log.e(TAG, "SystemUI hook unavailable", error);
        }
    }
    private static void attach(View root) {
        if (overlays.containsKey(root)) return;
        try {
            Log.i(TAG, "Keyguard root attached");
            LockScreenOverlay overlay = new LockScreenOverlay(root);
            overlays.put(root, overlay);
            overlay.start();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": overlay skipped: " + error);
            Log.e(TAG, "Overlay attach failed", error);
        }
    }
}
