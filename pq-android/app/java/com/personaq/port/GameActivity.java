package com.personaq.port;

import android.app.NativeActivity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** the game itself, Zakuro drawing into the whole screen from libpersonaq.so. */
public class GameActivity extends NativeActivity {
    static {
        // loaded through the class loader, as NativeActivity's own loading
        // does not, so that trimMemory finds its code
        System.loadLibrary("personaq");
    }

    /** the GPU gives back what it keeps that it can read again. */
    private static native void trimMemory(int level);

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** notes the game is still up now and then, so the launcher can tell a crash at the start from a later one. */
    private final Runnable alive = new Runnable() {
        @Override
        public void run() {
            Device.prefs(GameActivity.this).edit().putLong(Device.ALIVE, System.currentTimeMillis()).commit();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        // the launcher's graphics and CPU options, for Zakuro to read
        Device.apply(this);
        long now = System.currentTimeMillis();
        Device.prefs(this).edit().putLong(Device.STARTED, now).putLong(Device.ALIVE, now).commit();
        handler.postDelayed(alive, 5000);
        // the recompiled code calls as deep as the game does, on the thread
        // android_main runs on, which Rust makes with this much stack
        try {
            android.system.Os.setenv("RUST_MIN_STACK", "67108864", true);
        } catch (Exception ignored) {
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        super.onCreate(state);
        immerse();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(alive);
        // a game that ended as it should
        Device.prefs(this).edit().remove(Device.STARTED).commit();
        super.onDestroy();
    }

    /**
     * the system short of memory while playing, or the game gone to the
     * background, where giving memory back keeps Android from closing it
     * and losing the game since the last save.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {
                trimMemory(level);
            } catch (Throwable e) {
                android.util.Log.w("PersonaQ", "could not trim memory", e);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            immerse();
        }
    }

    /** hides the status and navigation bars, a swipe bringing them back for a while. */
    @SuppressWarnings("deprecation")
    private void immerse() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}
