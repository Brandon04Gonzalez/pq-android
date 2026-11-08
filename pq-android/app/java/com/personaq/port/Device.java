package com.personaq.port;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.BufferedReader;
import java.io.FileReader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.system.Os;

import java.util.Locale;

/**
 * the phone the game runs on, its SoC and GPU, and the graphics options the
 * launcher keeps, which GameActivity hands Zakuro as environment variables
 * before the game's library starts.
 */
final class Device {
    static final String PREFS = "options";
    /** how the game draws: on the GPU, on the GPU going without what its driver may get wrong, or on the CPU. */
    static final String MODE = "mode";
    static final int MODE_AUTO = 0;
    static final int MODE_SAFE = 1;
    static final int MODE_CPU = 2;
    /** whether the emulating thread keeps to the fast cores. */
    static final String FAST_CORES = "fast_cores";
    /** when the last game started, and when it was last seen running. */
    static final String STARTED = "started";
    static final String ALIVE = "alive";
    /** whether the HD textures are drawn. */
    static final String HD_TEXTURES = "hd_textures";
    /** the folder and the game file the player pointed the launcher at. */
    static final String PICKED_FOLDER = "picked_folder";
    static final String PICKED_GAME = "picked_game";
    /** what the launcher found last, for the game to use: paths, the DLC's a line each. */
    static final String GAME = "game";
    static final String DLC = "dlc";
    static final String PATCH = "patch";
    static final String TEXTURES = "textures";
    /** the launcher's version that last ran, for what a new one changes once. */
    static final String VERSION = "version";
    /** the bottom screen's size until the game has saved its settings. */
    static final String BOTTOM_SIZE = "bottom_size";
    /** the scale the 3D is drawn at, 0 for the one picked for the GPU and lowered while it can't keep up. */
    static final String RESOLUTION = "resolution";
    static final int RESOLUTION_AUTO = 0;
    /** 30 or 60 frames a second; 30 leaves every other frame of a game at 60 undrawn. */
    static final String FPS = "fps";
    static final int FPS_DEFAULT = 30;
    /** the touch controls' size and opacity, in percent. */
    static final String CONTROLS_SIZE = "controls_size";
    static final String CONTROLS_OPACITY = "controls_opacity";

    private Device() {
    }

    /** who made the SoC, by what Android says of it, the family's name. */
    static String family() {
        String maker = "", model = "";
        if (Build.VERSION.SDK_INT >= 31) {
            maker = Build.SOC_MANUFACTURER;
            model = Build.SOC_MODEL;
        }
        String all = (Build.HARDWARE + " " + Build.BOARD + " " + maker + " " + model).toLowerCase(Locale.ROOT);
        if (all.contains("qcom") || all.contains("qti") || all.contains("qualcomm") || all.matches(".*\\b(sm|sdm|msm)\\d.*")) {
            return "Qualcomm Snapdragon";
        }
        if (all.contains("mediatek") || all.matches(".*\\bmt\\d{4}.*")) {
            return "MediaTek Dimensity / Helio";
        }
        if (all.contains("kirin") || all.contains("hisilicon") || all.contains("hi36") || all.contains("hi62")) {
            return "HiSilicon Kirin";
        }
        if (all.contains("gs101") || all.contains("gs201") || all.contains("zuma") || all.contains("laguna")
                || all.contains("tensor") || maker.equalsIgnoreCase("google")) {
            return "Google Tensor";
        }
        if (all.contains("exynos") || all.contains("s5e") || all.contains("universal") || maker.equalsIgnoreCase("samsung")) {
            return "Samsung Exynos";
        }
        return null;
    }

    /** the SoC's own name, where Android gives it. */
    static String socName() {
        if (Build.VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.isEmpty() && !Build.SOC_MODEL.equals(Build.UNKNOWN)) {
            return Build.SOC_MODEL;
        }
        return Build.HARDWARE;
    }

    /** the newest Vulkan the phone has, 0x403000 for 1.3, 0 for none. */
    static int vulkan(Context context) {
        PackageManager packages = context.getPackageManager();
        int[] versions = {0x404000, 0x403000, 0x402000, 0x401000, 0x400000};
        for (int version : versions) {
            if (packages.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, version)) {
                return version;
            }
        }
        return 0;
    }

    static String vulkanName(int version) {
        return version == 0 ? "sin Vulkan" : "Vulkan 1." + ((version >> 12) & 0x3FF);
    }

    /** the GPU's name as OpenGL ES gives it, Adreno, Mali, Xclipse, PowerVR or Maleoon, or null. */
    static String gpu() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                return null;
            }
            int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return null;
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return null;
            }
            return GLES20.glGetString(GLES20.GL_RENDERER);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
            }
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * what a new version changes once: 0.2 took a RAR picked as the game
     * for it and closed at every start, which had players turn the CPU mode
     * on, so the mode goes back to automatic and the warning goes.
     */
    static void upgrade(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt(VERSION, 0) < 3) {
            prefs.edit().putInt(VERSION, 3).putInt(MODE, MODE_AUTO).remove(STARTED).apply();
        }
    }

    /** keeps where the launcher found everything, for the game to start with. */
    static void remember(Context context, GameFiles found) {
        StringBuilder dlc = new StringBuilder();
        for (GameFiles.Dump dump : found.dlc) {
            dlc.append(dump.file.getAbsolutePath()).append('\n');
        }
        prefs(context).edit()
            .putString(GAME, found.game != null ? found.game.file.getAbsolutePath() : null)
            .putString(DLC, dlc.toString())
            .putString(PATCH, found.patch != null ? found.patch.getAbsolutePath() : null)
            .putString(TEXTURES, found.textures != null ? found.textures.getAbsolutePath() : null)
            .apply();
    }

    /** the phone's memory, as Android counts it. */
    static String memoryName(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return String.format(Locale.ROOT, "%.1f GB, %.1f GB libres ahora", info.totalMem / 1073741824.0, info.availMem / 1073741824.0);
    }

    /** Zakuro's settings file, in the app's own folder, as XDG_CONFIG_HOME has it. */
    private static java.io.File settings(Context context) {
        return new java.io.File(new java.io.File(context.getFilesDir(), "zakuro"), "settings.toml");
    }

    private static final java.util.regex.Pattern BOTTOM = java.util.regex.Pattern.compile("(?m)^bottom_size = ([0-9]+)$");

    /** the bottom screen's size held sideways, in percent, as the game last saved it. */
    static int bottomSize(Context context) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(settings(context).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = BOTTOM.matcher(text);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception ignored) {
        }
        return prefs(context).getInt(BOTTOM_SIZE, 50);
    }

    /** sets the bottom screen's size in the game's settings, which the slider in its menu also changes. */
    static void setBottomSize(Context context, int percent) {
        prefs(context).edit().putInt(BOTTOM_SIZE, percent).apply();
        java.io.File file = settings(context);
        try {
            String text = file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
            java.util.regex.Matcher matcher = BOTTOM.matcher(text);
            // a key of the top table, which has to come before any [table]
            text = matcher.find() ? matcher.replaceFirst("bottom_size = " + percent) : "bottom_size = " + percent + "\n" + text;
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("PersonaQ", "could not save the bottom screen's size", e);
        }
    }

    /**
     * the options as Zakuro's environment variables, each set or unset, as
     * a game started before in this process may have left them otherwise.
     */
    static void apply(Context context) {
        SharedPreferences prefs = prefs(context);
        int mode = prefs.getInt(MODE, MODE_AUTO);
        boolean safe = mode == MODE_SAFE;
        android.util.Log.i("PersonaQ", "graphics mode " + mode + ", fast cores " + prefs.getBoolean(FAST_CORES, true));
        // the safe mode uses the GPU as a Vulkan 1.1 one: render passes,
        // descriptor sets, and the depth, stencil and blend state held by
        // pipelines, what every driver has done longest
        set("ZAKURO_VULKAN", safe ? "1.1" : null);
        set("ZAKURO_FIXED_STATE", safe ? "1" : null);
        set("ZAKURO_STATIC_BLEND", safe ? "1" : null);
        set("ZAKURO_NO_PUSH_DESCRIPTORS", safe ? "1" : null);
        set("ZAKURO_RASTERIZER", mode == MODE_CPU ? "software" : null);
        set("ZAKURO_ANY_CORE", prefs.getBoolean(FAST_CORES, true) ? null : "1");
        // where the launcher found the game and the rest, wherever the player keeps them
        set("PQ_GAME", prefs.getString(GAME, null));
        set("PQ_DLC", prefs.getString(DLC, null));
        set("ZAKURO_MODS_" + GameFiles.TITLE_ID, prefs.getString(PATCH, null));
        // a folder that is not there leaves the pack out
        String textures = prefs.getString(TEXTURES, null);
        set("ZAKURO_TEXTURES_" + GameFiles.TITLE_ID, prefs.getBoolean(HD_TEXTURES, true) ? textures : "none");
        // the screen and the touch controls
        int resolution = prefs.getInt(RESOLUTION, RESOLUTION_AUTO);
        set("ZAKURO_RESOLUTION", resolution == RESOLUTION_AUTO ? "auto" : String.valueOf(resolution));
        set("ZAKURO_FPS", String.valueOf(prefs.getInt(FPS, FPS_DEFAULT)));
        set("ZAKURO_CONTROLS_SIZE", String.valueOf(prefs.getInt(CONTROLS_SIZE, 100)));
        set("ZAKURO_CONTROLS_OPACITY", String.valueOf(prefs.getInt(CONTROLS_OPACITY, 100)));
        // the APK holds no game code: the game is recompiled on the phone the first time it starts
        compiler(context);

        // Custom GPU driver (libadrenotools / Turnip)
        File driverDir = new File(context.getFilesDir(), "gpu_driver");
        File activeFile = new File(driverDir, "active.txt");
        if (activeFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(activeFile))) {
                String active = r.readLine();
                if (active != null && !active.trim().isEmpty()) {
                    set("ZAKURO_HOOK_LIB_DIR", context.getApplicationInfo().nativeLibraryDir);
                    set("ZAKURO_CUSTOM_DRIVER_DIR", driverDir.getAbsolutePath());
                    set("ZAKURO_CUSTOM_DRIVER_LIB", active.trim());
                } else {
                    set("ZAKURO_HOOK_LIB_DIR", null);
                    set("ZAKURO_CUSTOM_DRIVER_DIR", null);
                    set("ZAKURO_CUSTOM_DRIVER_LIB", null);
                }
            } catch (Exception ignored) {
            }
        } else {
            set("ZAKURO_HOOK_LIB_DIR", null);
            set("ZAKURO_CUSTOM_DRIVER_DIR", null);
            set("ZAKURO_CUSTOM_DRIVER_LIB", null);
        }
    }

    // ---- the C compiler the game recompiles itself with on the phone ----

    /** the version of the compiler's library unpacked into files/zig/lib. */
    static final String ZIG_VERSION = "0.17.0-1";

    static File zigLib(Context context) { return new File(context.getFilesDir(), "zig/lib"); }

    /** whether files/zig/lib holds this version of the compiler's library. */
    static boolean compilerReady(Context context) {
        File note = new File(zigLib(context), ".ready");
        try (BufferedReader r = new BufferedReader(new FileReader(note))) {
            return ZIG_VERSION.equals(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    /**
     * unpacks the compiler's library (assets/zig-lib.zip: clang's and musl's headers, Zig's std and compiler_rt) into
     * files/zig/lib, once a version. The compiler itself is lib/arm64-v8a/libzig.so (zig 0.17.0), extracted at install.
     */
    static void unpackCompiler(Context context) throws Exception {
        File lib = zigLib(context);
        deleteTree(lib);
        lib.mkdirs();
        String root = lib.getCanonicalPath() + File.separator;
        byte[] buf = new byte[1 << 16];
        try (InputStream raw = context.getAssets().open("zig-lib.zip"); ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(lib, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
        try (OutputStream o = new FileOutputStream(new File(lib, ".ready"))) {
            o.write((ZIG_VERSION + "\n").getBytes("UTF-8"));
        }
    }

    /** what Zakuro recompiles the game with (zakuro lib.rs recompile_on_phone), when the compiler is ready. */
    private static void compiler(Context context) {
        boolean ready = compilerReady(context);
        File cache = new File(context.getCacheDir(), "zig");
        set("ZAKURO_CC", ready ? context.getApplicationInfo().nativeLibraryDir + "/libzig.so" : null);
        // no libc linked (the translated code calls none), but musl's headers, which -nostdlib leaves out, for
        // recomp.h's math.h, stdint.h and string.h
        String include = zigLib(context).getPath() + "/libc/include/";
        set("ZAKURO_CC_ARGS", ready ? "cc -target aarch64-linux-musl -nostdlib -isystem " + include + "aarch64-linux-musl -isystem "
            + include + "generic-musl -isystem " + include + "any-linux-any -Wl,-z,max-page-size=16384" : null);
        set("ZIG_LIB_DIR", ready ? zigLib(context).getPath() : null);
        set("ZIG_GLOBAL_CACHE_DIR", ready ? cache.getPath() : null);
        set("ZIG_LOCAL_CACHE_DIR", ready ? cache.getPath() : null);
    }

    private static void set(String name, String value) {
        try {
            if (value == null) {
                Os.unsetenv(name);
            } else {
                Os.setenv(name, value, true);
            }
        } catch (Exception e) {
            android.util.Log.w("PersonaQ", "could not set " + name, e);
        }
    }
}
