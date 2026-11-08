package com.personaq.port;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import java.io.IOException;

import android.provider.OpenableColumns;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;

import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * finds the player's own copy of Persona Q wherever it is in the phone, with
 * its DLC, the Spanish patch and the HD textures, or lets them point at it,
 * unpacks it when it came as a RAR, and keeps the graphics options.
 */
public class Launcher extends Activity {
    private static final int PICK_ICON = 43;
    private Runnable iconButtonUpdate;
    private static final int ASK_ACCESS = 2;
    private static final int ACCENT = Color.rgb(255, 214, 10);
    private static final int BACKGROUND = Color.rgb(16, 16, 20);
    /** the app's icon at the top, the one picked for the launcher. */
    private ImageView appIcon;
    private static final int CARD = Color.rgb(30, 30, 38);
    private static final int WARN = Color.rgb(255, 160, 64);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pad;
    private TextView status;
    private TextView phone;
    private TextView warning;
    private LinearLayout problems;
    private LinearLayout working;
    private View workingCard;
    private TextView workingText;
    private ProgressBar workingBar;
    private Button cancel;
    private String gpu;
    private Button access;
    private Button choose;
    private Button rescan;
    private Button play;
    private GameFiles files;
    private volatile boolean busy;
    /** the browser opens by itself once, when nothing was found. */
    private boolean asked;

    private SharedPreferences prefs() {
        return Device.prefs(this);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        Device.upgrade(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad + pad / 2, pad + pad / 2, pad + pad / 2, pad * 2);
        column.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        appIcon = new ImageView(this);
        updateHeaderIcon();
        int side = pad * 4;
        header.addView(appIcon, new LinearLayout.LayoutParams(side, side));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(pad, 0, 0, 0);
        TextView title = text("Persona Q: Shadow of the Labyrinth", 21, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        names.addView(title);
        names.addView(text("Port nativo para Android (3dsrecomp + Zakuro) · " + version(), 13, Color.GRAY));
        header.addView(names);
        column.addView(header);

        status = text("", 15, Color.WHITE);
        status.setLineSpacing(0, 1.15f);
        column.addView(card(status), spaced());

        problems = new LinearLayout(this);
        problems.setOrientation(LinearLayout.VERTICAL);
        column.addView(problems);

        working = new LinearLayout(this);
        working.setOrientation(LinearLayout.VERTICAL);
        workingText = text("", 14, Color.WHITE);
        workingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        workingBar.setMax(1000);
        cancel = button("Cancelar", false, v -> {
            if (Rar.available()) {
                Rar.cancel();
            }
            cancel.setEnabled(false);
            workingText.setText("Cancelando cuando termine el archivo actual…");
        });
        working.addView(workingText);
        working.addView(workingBar);
        working.addView(cancel);
        workingCard = card(working);
        workingCard.setVisibility(View.GONE);
        column.addView(workingCard, spaced());

        access = button("Dar acceso a los archivos", true, v -> askAccess());
        play = button("JUGAR", true, v -> start());
        play.setTextSize(20);
        choose = button("Elegir dónde está el juego…", false, v -> browse(null));
        rescan = button("Buscar de nuevo", false, v -> scan(true));
        column.addView(access, spaced());
        column.addView(play, spaced());
        column.addView(choose, spaced());
        column.addView(rescan, spaced());

        warning = text("", 14, WARN);
        warning.setVisibility(View.GONE);
        column.addView(warning, spaced());
        column.addView(options());

        TextView where = text("Las partidas se guardan en " + new File(GameFiles.home(), "user").getAbsolutePath()
            + "\nSi algo falla, manda " + new File(GameFiles.home(), "zakuro.log").getAbsolutePath() + " con el modelo del teléfono.", 12, Color.GRAY);
        column.addView(where, spaced());
        Button exportLogs = button("Exportar registros de depuración", false, v -> exportLogs());
        column.addView(exportLogs, spaced());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(column);
        setContentView(scroll);
    }

    private String version() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = pad * 3 / 4;
        return params;
    }

    private View card(View inside) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(pad * 0.75f);
        box.setBackground(shape);
        box.addView(inside);
        return box;
    }

    private Button button(String label, boolean main, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(main ? ACCENT : Color.rgb(52, 52, 64));
        shape.setCornerRadius(pad * 0.6f);
        button.setBackground(shape);
        button.setTextColor(main ? Color.BLACK : Color.WHITE);
        button.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        button.setOnClickListener(listener);
        return button;
    }

    /** the phone's SoC and GPU, and the graphics, CPU and screen options. */
    private View options() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, pad, 0, 0);

        TextView heading = text("Tu teléfono y gráficos", 18, ACCENT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        phone = text("", 14, Color.LTGRAY);
        box.addView(phone);
        describePhone();
        new Thread(() -> {
            String name = Device.gpu();
            runOnUiThread(() -> {
                gpu = name;
                describePhone();
            });
        }).start();

        final SharedPreferences prefs = prefs();
        RadioGroup modes = new RadioGroup(this);
        modes.setPadding(0, pad / 3, 0, 0);
        String[] labels = {
            "Automático: dibuja en la GPU con todo lo que tenga (recomendado)",
            "GPU modo compatible: para drivers con fallos gráficos o cierres (Kirin, Mali y Adreno antiguos)",
            "CPU: sin GPU, mucho más lento, solo si lo demás falla",
        };
        for (int i = 0; i < labels.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(i + 1);
            choice.setText(labels[i]);
            choice.setTextColor(Color.WHITE);
            modes.addView(choice);
        }
        modes.check(prefs.getInt(Device.MODE, Device.MODE_AUTO) + 1);
        modes.setOnCheckedChangeListener((group, id) -> prefs.edit().putInt(Device.MODE, id - 1).apply());
        box.addView(modes);

        CheckBox fast = new CheckBox(this);
        fast.setText("Usar solo los núcleos rápidos del procesador para emular (recomendado)");
        fast.setTextColor(Color.WHITE);
        fast.setChecked(prefs.getBoolean(Device.FAST_CORES, true));
        fast.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean(Device.FAST_CORES, checked).apply());
        box.addView(fast);

        CheckBox hd = new CheckBox(this);
        hd.setText("Texturas HD (si tu teléfono va justo de memoria o de velocidad, desactívalas)");
        hd.setTextColor(Color.WHITE);
        hd.setChecked(prefs.getBoolean(Device.HD_TEXTURES, true));
        hd.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean(Device.HD_TEXTURES, checked).apply());
        box.addView(hd);

        // Controlador de GPU personalizado (libadrenotools / Turnip)
        LinearLayout drvRow = new LinearLayout(this);
        drvRow.setOrientation(LinearLayout.HORIZONTAL);
        drvRow.setGravity(Gravity.CENTER_VERTICAL);
        drvRow.setPadding(0, pad / 3, 0, pad / 3);
        driverText = text("Controlador GPU: del sistema", 14, Color.WHITE);
        drvRow.addView(driverText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button drvImport = button("Importar", false, v -> pickDriver());
        drvRow.addView(drvImport);
        Button drvRemove = button("Quitar", false, v -> removeDriver());
        drvRow.addView(drvRemove);
        box.addView(drvRow);
        showDriver();

        TextView display = text("", 18, ACCENT);
        display.setText("Imagen y controles");
        display.setTypeface(Typeface.DEFAULT_BOLD);
        display.setPadding(0, pad, 0, 0);
        box.addView(display);
        box.addView(choice("Resolución del 3D", Device.RESOLUTION, Device.RESOLUTION_AUTO,
            new int[] {Device.RESOLUTION_AUTO, 1, 2, 3, 4},
            new String[] {
                "Automática según la GPU (recomendada)",
                "1x · 400×240, la más rápida",
                "2x · 800×480",
                "3x · 1200×720",
                "4x · 1600×960, solo GPUs de gama alta",
            }));
        box.addView(choice("Fotogramas por segundo", Device.FPS, Device.FPS_DEFAULT,
            new int[] {30, 60},
            new String[] {
                "30 FPS (recomendado): dibuja la mitad, la GPU va el doble de holgada",
                "60 FPS: como la consola, para GPUs potentes",
            }));
        box.addView(choice("Tamaño de los controles", Device.CONTROLS_SIZE, 100,
            new int[] {80, 90, 100, 110, 120},
            new String[] {"80 %", "90 %", "100 %", "110 %", "120 %"}));
        box.addView(choice("Opacidad de los controles", Device.CONTROLS_OPACITY, 100,
            new int[] {30, 50, 75, 100},
            new String[] {"30 %", "50 %", "75 %", "100 %"}));
        box.addView(text("Con la resolución automática, si tu GPU no llega a la velocidad completa el juego baja un paso "
            + "solo y lo recuerda. El contador muestra los FPS y la resolución que se usa.", 12, Color.GRAY));
        box.addView(iconChoice());

        TextView screen = text("", 18, ACCENT);
        screen.setText("Pantalla táctil");
        screen.setTypeface(Typeface.DEFAULT_BOLD);
        screen.setPadding(0, pad, 0, 0);
        box.addView(screen);
        final TextView size = text("", 14, Color.WHITE);
        box.addView(size);
        SeekBar bar = new SeekBar(this);
        final int low = 40, high = 100;
        bar.setMax((high - low) / 5);
        int current = Device.bottomSize(this);
        bar.setProgress((current - low) / 5);
        size.setText(sizeLabel(current));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean user) {
                size.setText(sizeLabel(low + progress * 5));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Device.setBottomSize(Launcher.this, low + seekBar.getProgress() * 5);
            }
        });
        box.addView(bar);
        box.addView(text("Con el teléfono en horizontal. Más pequeña, la pantalla de arriba se ve más grande; los botones "
            + "van encima de la imagen, como en Persona Q2. También se cambia dentro del juego: MENÚ → Settings.", 12, Color.GRAY));
        return box;
    }

    private static String sizeLabel(int percent) {
        return "Tamaño de la pantalla de abajo (táctil): " + percent + " %"
            + (percent == 100 ? " (como en la consola)" : percent == 50 ? " (como en Persona Q2)" : "");
    }

    private void updateHeaderIcon() {
        if (appIcon == null) return;
        File custom = new File(getFilesDir(), "custom_icon.png");
        if (custom.exists()) {
            Bitmap b = BitmapFactory.decodeFile(custom.getAbsolutePath());
            if (b != null) {
                appIcon.setImageBitmap(b);
                return;
            }
        }
        appIcon.setImageResource(R.mipmap.ic_launcher);
    }

    /** the button that lets the player pick a custom image for the app icon and home screen shortcut. */
    private Button iconChoice() {
        final Button button = button("", false, null);
        iconButtonUpdate = () -> {
            File custom = new File(getFilesDir(), "custom_icon.png");
            button.setText("Icono de la app: " + (custom.exists() ? "Personalizado (cambiar/restablecer)" : "Subir imagen personalizada"));
        };
        iconButtonUpdate.run();
        button.setOnClickListener(v -> {
            File custom = new File(getFilesDir(), "custom_icon.png");
            if (custom.exists()) {
                new AlertDialog.Builder(this)
                    .setTitle("Icono de la app")
                    .setItems(new String[] {"Subir nueva imagen", "Restablecer icono original", "Crear acceso directo en pantalla de inicio"}, (dialog, which) -> {
                        if (which == 0) {
                            pickIcon();
                        } else if (which == 1) {
                            custom.delete();
                            updateHeaderIcon();
                            if (iconButtonUpdate != null) iconButtonUpdate.run();
                            Toast.makeText(this, "Icono restablecido al predeterminado", Toast.LENGTH_SHORT).show();
                        } else if (which == 2) {
                            pinShortcut();
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            } else {
                pickIcon();
            }
        });
        return button;
    }

    private void pickIcon() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_ICON);
    }

    private void onIconPicked(Uri uri) {
        try {
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) throw new IOException("No se pudo abrir la imagen");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap icon = Bitmap.createScaledBitmap(square, 432, 432, true);
            File custom = new File(getFilesDir(), "custom_icon.png");
            try (FileOutputStream out = new FileOutputStream(custom)) {
                icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            updateHeaderIcon();
            if (iconButtonUpdate != null) iconButtonUpdate.run();
            pinShortcut();
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo usar esa imagen: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pinShortcut() {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, "Imagen guardada.", Toast.LENGTH_LONG).show();
                return;
            }
            File custom = new File(getFilesDir(), "custom_icon.png");
            Bitmap icon = custom.exists() ? BitmapFactory.decodeFile(custom.getAbsolutePath()) : null;
            if (icon == null) return;
            ShortcutInfo pin = new ShortcutInfo.Builder(this, "custom-icon")
                .setIntent(new Intent(Intent.ACTION_MAIN).setClass(this, Launcher.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                .setShortLabel("PQ").setLongLabel("Persona Q")
                .setIcon(Icon.createWithAdaptiveBitmap(icon))
                .build();
            sm.requestPinShortcut(pin, null);
            Toast.makeText(this, "Acepta el aviso para poner el acceso directo con tu imagen en la pantalla de inicio.", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Error al crear acceso directo: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** a button showing an option's value, which opens the list of values to pick one. */
    private Button choice(String title, String key, int fallback, int[] values, String[] labels) {
        final Button button = button("", false, null);
        final Runnable show = () -> {
            int current = prefs().getInt(key, fallback);
            String label = labels[0];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) label = labels[i];
            }
            button.setText(title + ": " + label);
        };
        show.run();
        button.setOnClickListener(v -> {
            int current = prefs().getInt(key, fallback);
            int checked = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) checked = i;
            }
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs().edit().putInt(key, values[which]).apply();
                    show.run();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });
        return button;
    }

    private void describePhone() {
        String family = Device.family();
        int vulkan = Device.vulkan(this);
        StringBuilder text = new StringBuilder();
        text.append("Procesador: ").append(family != null ? family : "desconocido").append(" (").append(Device.socName()).append(")\n");
        text.append("GPU: ").append(gpu != null ? gpu : "…").append(" · ").append(Device.vulkanName(vulkan)).append("\n");
        text.append("Memoria: ").append(Device.memoryName(this)).append("\n");
        text.append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE);
        if (vulkan == 0) {
            text.append("\n✗ Este teléfono no tiene Vulkan: el juego no puede mostrarse.");
        } else if (vulkan < 0x401000) {
            text.append("\n• Vulkan 1.0: si hay fallos usa el modo compatible o CPU.");
        }
        phone.setText(text.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHeaderIcon();
        if (iconButtonUpdate != null) iconButtonUpdate.run();
        scan(false);
    }

    static final class Api30Helper {
        static boolean isExternalStorageManager() {
            return android.os.Environment.isExternalStorageManager();
        }
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Api30Helper.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, ASK_ACCESS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        scan(false);
    }

    private File pickedFolder() {
        String path = prefs().getString(Device.PICKED_FOLDER, null);
        return path == null ? null : new File(path);
    }

    private File pickedGame() {
        String path = prefs().getString(Device.PICKED_GAME, null);
        return path == null ? null : new File(path);
    }

    /** looks for everything again on a thread, saying so, and shows what it found. */
    private void scan(final boolean asked) {
        if (busy) {
            return;
        }
        boolean allowed = hasAccess();
        access.setVisibility(allowed ? View.GONE : View.VISIBLE);
        choose.setEnabled(allowed);
        rescan.setEnabled(allowed);
        if (!allowed) {
            play.setVisibility(View.GONE);
            problems.removeAllViews();
            status.setText("Para empezar toca \"Dar acceso a los archivos\" y activa el permiso para Persona Q.\n\n"
                + "Lo necesita para leer el juego, el parche y las texturas donde los tengas (por ejemplo en Descargas) "
                + "y para guardar las partidas en la carpeta PersonaQ.");
            return;
        }
        busy = true;
        rescan.setEnabled(false);
        rescan.setText("Buscando…");
        status.setText("Buscando el juego, el parche y las texturas en el teléfono…");
        final File folder = pickedFolder(), game = pickedGame();
        new Thread(() -> {
            GameFiles.home().mkdirs();
            final GameFiles found = GameFiles.find(folder, game);
            runOnUiThread(() -> {
                busy = false;
                rescan.setEnabled(true);
                rescan.setText("Buscar de nuevo");
                show(found);
                // adb shell am start -n com.personaq.port/.LauncherActivity --ez play true
                // starts the game once found, for measuring on a phone without taps
                if (getIntent().getBooleanExtra("play", false) && found.game != null) {
                    getIntent().removeExtra("play");
                    start();
                }
                if (asked) {
                    String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
                    Toast.makeText(this, (found.game != null ? "✓ Juego encontrado" : "✗ No encontré el juego") + " (" + time + ")", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static String size(long bytes) {
        return bytes >= 1e9 ? String.format(Locale.ROOT, "%.2f GB", bytes / 1e9) : String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
    }

    private static String where(File file) {
        String path = file.getAbsolutePath();
        String storage = GameFiles.storage().getAbsolutePath();
        return path.startsWith(storage) ? "Memoria interna" + path.substring(storage.length()) : path;
    }

    /** what was found, what is missing and what to do about it. */
    private void show(GameFiles found) {
        files = found;
        Device.remember(this, found);
        StringBuilder text = new StringBuilder();
        if (found.game != null) {
            text.append("✓ Juego: ").append(found.game.file.getName()).append(" (").append(size(found.game.file.length())).append(")\n   ")
                .append(where(found.game.file.getParentFile())).append("\n");
        } else {
            text.append("✗ No encontré el juego (Persona Q USA, .cia o .3ds descifrado).\n");
        }
        if (found.patch != null) {
            text.append("✓ Parche en español: ").append(where(found.patch)).append("\n");
        } else {
            text.append("• Sin parche en español (carpeta ").append(GameFiles.TITLE_ID).append(" con code.bin y romfs)\n");
        }
        if (found.textures != null && prefs().getBoolean(Device.HD_TEXTURES, true)) {
            text.append("✓ Texturas HD: ").append(found.pictures).append(" imágenes\n");
        } else if (found.textures != null) {
            text.append("• Texturas HD desactivadas (").append(found.pictures).append(" imágenes)\n");
        } else {
            text.append("• Sin pack de texturas HD\n");
        }
        for (GameFiles.Dump dlc : found.dlc) {
            text.append("✓ DLC: ").append(dlc.file.getName()).append("\n");
        }
        if (found.dlc.isEmpty() && !found.encryptedDlc.isEmpty()) {
            text.append("• DLC cifrado, no se usa (descífralo en la 3DS con GodMode9)\n");
        } else if (found.dlc.isEmpty()) {
            text.append("• Sin DLC\n");
        }
        status.setText(text.toString().trim());

        problems.removeAllViews();
        for (final GameFiles.Dump bad : found.misnamed) {
            String kind = bad.kind == null ? "no es un juego de 3DS" : "es un archivo ." + bad.kind + " comprimido, no el juego";
            boolean ours = bad.file.getParentFile() != null && bad.file.getParentFile().equals(GameFiles.home());
            List<Button> actions = new ArrayList<>();
            if ("rar".equals(bad.kind)) {
                actions.add(button("Extraer este .rar", false, v -> extract(bad.file, ours)));
            }
            actions.add(button("Borrar este archivo (" + size(bad.file.length()) + ")", false, v -> delete(bad.file)));
            problem("⚠ " + where(bad.file) + " " + kind + "."
                + (ours ? " Lo copió aquí la versión anterior al elegir el .rar; ya no hace falta." : ""), actions);
        }
        for (GameFiles.Dump unusable : found.unusable) {
            String why = unusable.title == GameFiles.PROGRAM_ID
                ? "está cifrado: hace falta un dump descifrado (GodMode9 en la 3DS, \"Build CIA (decrypted)\")."
                : "no es Persona Q de EE. UU. (" + String.format(Locale.ROOT, "%016X", unusable.title) + "), que es la versión que trae este port.";
            problem("⚠ " + unusable.file.getName() + " " + why, new ArrayList<>());
        }
        if (found.game == null) {
            for (final GameFiles.Dump archive : found.archives) {
                if ("rar".equals(archive.kind)) {
                    List<Button> actions = new ArrayList<>();
                    actions.add(button("Extraer aquí el juego", true, v -> extract(archive.file, false)));
                    problem("Encontré " + where(archive.file) + " (" + size(archive.file.length()) + "). Puedo extraer el juego, el DLC y el parche "
                        + "a la carpeta PersonaQ.", actions);
                }
            }
        }

        SharedPreferences prefs = prefs();
        long started = prefs.getLong(Device.STARTED, 0), alive = prefs.getLong(Device.ALIVE, 0);
        if (found.game != null && started != 0 && alive - started < 15000) {
            warning.setText("⚠ La última vez el juego se cerró al arrancar. Prueba \"GPU modo compatible\" abajo; "
                + "si sigue pasando, \"CPU\", y mándame " + new File(GameFiles.home(), "zakuro.log").getAbsolutePath()
                + " con el modelo del teléfono.");
            warning.setVisibility(View.VISIBLE);
        } else {
            warning.setVisibility(View.GONE);
        }
        play.setVisibility(found.game != null && !busy ? View.VISIBLE : View.GONE);
        choose.setText(found.game != null ? "Cambiar el juego o la carpeta…" : "Elegir dónde está el juego…");
        if (found.game == null && found.archives.isEmpty() && found.misnamed.isEmpty() && !asked) {
            asked = true;
            browse(null);
        }
    }

    private void problem(String message, List<Button> actions) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(message, 14, WARN));
        for (Button action : actions) {
            LinearLayout.LayoutParams params = spaced();
            params.topMargin = pad / 2;
            box.addView(action, params);
        }
        problems.addView(card(box), spaced());
    }

    private void delete(final File file) {
        new AlertDialog.Builder(this)
            .setTitle("¿Borrar " + file.getName() + "?")
            .setMessage(where(file) + "\n" + size(file.length()) + "\n\nNo se puede deshacer.")
            .setPositiveButton("Borrar", (dialog, which) -> {
                if (!file.delete()) {
                    Toast.makeText(this, "No pude borrarlo", Toast.LENGTH_LONG).show();
                }
                scan(false);
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // the folder browser, the app's own, as the phone's file pickers hide
    // the Download folder's insides from apps on newer Android versions

    /** shows a folder's insides to pick the game, a folder holding it or a RAR, or the phone's storages at null. */
    private void browse(final File dir) {
        if (!hasAccess()) {
            askAccess();
            return;
        }
        final List<File> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        if (dir == null) {
            entries.add(GameFiles.storage());
            labels.add("📱  Memoria interna");
            File downloads = new File(GameFiles.storage(), "Download");
            entries.add(downloads);
            labels.add("⬇  Descargas");
            File[] cards = new File("/storage").listFiles();
            if (cards != null) {
                for (File card : cards) {
                    if (card.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}") && card.canRead()) {
                        entries.add(card);
                        labels.add("💾  Tarjeta SD (" + card.getName() + ")");
                    }
                }
            }
        } else {
            File parent = dir.getParentFile();
            entries.add(parent != null && parent.canRead() && !dir.equals(GameFiles.storage()) ? parent : null);
            labels.add("⬆  Subir");
            File[] list = dir.listFiles();
            if (list != null) {
                Arrays.sort(list, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
                for (File file : list) {
                    if (file.getName().startsWith(".")) {
                        continue;
                    }
                    if (file.isDirectory()) {
                        entries.add(file);
                        labels.add("📁  " + file.getName());
                    } else if (GameFiles.gameName(file.getName())) {
                        entries.add(file);
                        labels.add("🎮  " + file.getName() + "  ·  " + size(file.length()));
                    } else if (GameFiles.archiveName(file.getName())) {
                        entries.add(file);
                        labels.add("📦  " + file.getName() + "  ·  " + size(file.length()));
                    }
                }
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(dir == null ? "¿Dónde está el juego?" : where(dir))
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                File picked = entries.get(which);
                if (picked == null || picked.isDirectory()) {
                    browse(picked);
                } else {
                    pick(picked);
                }
            })
            .setNegativeButton("Cancelar", null);
        if (dir != null) {
            builder.setPositiveButton("Usar esta carpeta", (dialog, which) -> {
                prefs().edit().putString(Device.PICKED_FOLDER, dir.getAbsolutePath()).remove(Device.PICKED_GAME).apply();
                scan(true);
            });
        } else {
            builder.setMessage(null);
        }
        builder.show();
    }

    /** a file picked in the browser: the game, a RAR to unpack, or something else. */
    private void pick(final File file) {
        new Thread(() -> {
            final GameFiles.Dump dump = GameFiles.inspect(file);
            runOnUiThread(() -> {
                if (dump.game() && dump.decrypted) {
                    prefs().edit().putString(Device.PICKED_GAME, file.getAbsolutePath())
                        .putString(Device.PICKED_FOLDER, file.getParentFile().getAbsolutePath()).apply();
                    scan(true);
                } else if ("rar".equals(dump.kind)) {
                    extract(file, false);
                } else if (dump.archive()) {
                    message("Archivo ." + dump.kind, "Este port solo extrae .rar. Extrae el ." + dump.kind
                        + " con otra app (por ejemplo RAR o ZArchiver) y luego elige la carpeta donde quedó el juego.");
                } else if (dump.game()) {
                    message("Juego cifrado", "Este .cia está cifrado. Hace falta un dump descifrado: en la 3DS, GodMode9 → "
                        + "\"Build CIA (decrypted)\" o \"Decrypt file\".");
                } else if (dump.dlc()) {
                    message("Esto es el DLC", "Elige el juego (el .cia más grande); el DLC se encuentra solo si está en la misma carpeta.");
                } else if (dump.kind != null) {
                    message("Otro juego", "No es Persona Q de EE. UU. (" + String.format(Locale.ROOT, "%016X", dump.title) + ").");
                } else {
                    message("No es un juego", file.getName() + " no es un juego de 3DS.");
                }
            });
        }).start();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Entendido", null).show();
    }

    /** unpacks a RAR into the PersonaQ folder, with its progress, and offers to delete it once done. */
    private void extract(final File archive, final boolean ours) {
        if (!Rar.available()) {
            message("No puedo extraer", "Falta la librería de RAR en esta instalación. Extrae el .rar con la app RAR y elige la carpeta.");
            return;
        }
        final File target = GameFiles.home();
        target.mkdirs();
        busy = true;
        play.setVisibility(View.GONE);
        choose.setEnabled(false);
        rescan.setEnabled(false);
        problems.removeAllViews();
        workingCard.setVisibility(View.VISIBLE);
        cancel.setEnabled(true);
        workingBar.setProgress(0);
        workingText.setText("Leyendo " + archive.getName() + "…");
        new Thread(() -> {
            String listing = Rar.list(archive.getAbsolutePath());
            String error = null;
            final List<String> names = new ArrayList<>();
            long total = 0;
            if (listing == null || !listing.startsWith("OK\n")) {
                error = listing == null ? "no se pudo leer" : listing.substring(listing.indexOf('\n') + 1);
            } else {
                for (String line : listing.substring(3).split("\n")) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        total += Long.parseLong(line.substring(0, tab));
                        names.add(line.substring(tab + 1));
                    }
                }
                long free = new StatFs(target.getAbsolutePath()).getAvailableBytes();
                if (free < total + (200L << 20)) {
                    error = "no hay espacio: hacen falta " + size(total + (200L << 20)) + " libres y hay " + size(free)
                        + (ours ? "" : ". Borra algo o extrae el .rar en otra parte con la app RAR.");
                }
            }
            if (error == null) {
                final long all = total;
                final boolean[] done = {false};
                // the progress, by how much of the files is on disk
                Runnable poll = new Runnable() {
                    @Override
                    public void run() {
                        if (done[0]) {
                            return;
                        }
                        long written = 0;
                        for (String name : names) {
                            written += new File(target, name).length();
                        }
                        String current = Rar.current();
                        workingBar.setProgress(all > 0 ? (int) (written * 1000 / all) : 0);
                        workingText.setText("Extrayendo a " + where(target) + "\n" + new File(current).getName() + "\n"
                            + size(written) + " de " + size(all));
                        handler.postDelayed(this, 500);
                    }
                };
                runOnUiThread(poll);
                error = Rar.extract(archive.getAbsolutePath(), target.getAbsolutePath());
                done[0] = true;
            }
            final String failed = error;
            runOnUiThread(() -> {
                busy = false;
                workingCard.setVisibility(View.GONE);
                choose.setEnabled(true);
                rescan.setEnabled(true);
                if (failed != null) {
                    message("No se pudo extraer", failed);
                    scan(false);
                    return;
                }
                prefs().edit().remove(Device.PICKED_GAME).remove(Device.PICKED_FOLDER).apply();
                new AlertDialog.Builder(this)
                    .setTitle("✓ Extraído")
                    .setMessage("El juego quedó en " + where(target) + ".\n\n¿Borrar " + archive.getName() + " (" + size(archive.length())
                        + ") para liberar espacio? Ya no hace falta.")
                    .setPositiveButton("Borrar el .rar", (dialog, which) -> {
                        archive.delete();
                        scan(true);
                    })
                    .setNegativeButton("Conservarlo", (dialog, which) -> scan(true))
                    .setCancelable(false)
                    .show();
            });
        }).start();
    }

    private void start() {
        if (files == null || files.game == null || !files.game.file.isFile()) {
            scan(true);
            return;
        }
        Device.remember(this, files);
        // the C compiler's library, unpacked once before the first game: the game recompiles itself on the phone
        if (!Device.compilerReady(this)) {
            Toast.makeText(this, "Preparando el compilador (solo la primera vez)…", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                try {
                    Device.unpackCompiler(this);
                } catch (Exception e) {
                    android.util.Log.w("PersonaQ", "could not unpack the compiler", e);
                }
                runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)));
            }).start();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private static final int PICK_DRIVER = 42;
    private TextView driverText;

    private File driverDir() {
        File d = new File(getFilesDir(), "gpu_driver");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String activeDriver() {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(driverDir(), "active.txt")))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void showDriver() {
        if (driverText == null) return;
        String a = activeDriver();
        driverText.setText(a.isEmpty() ? "Controlador GPU: del sistema" : "Controlador GPU: " + a);
    }

    private void removeDriver() {
        File[] files = driverDir().listFiles();
        if (files != null) for (File f : files) f.delete();
        showDriver();
        Toast.makeText(this, "Controlador del sistema activado", Toast.LENGTH_LONG).show();
    }

    private void pickDriver() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_DRIVER);
    }

    private static void copyDriverStream(InputStream in, File dest) throws java.io.IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) return c.getString(nameIndex);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "driver.zip" : last.substring(last.lastIndexOf('/') + 1);
    }

    private void importDriver(Uri uri) {
        try {
            String picked = getDisplayName(uri);
            File dir = driverDir();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String libName = null;
            if (picked.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String soEntry = null, wanted = null;
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String base = new File(e.getName()).getName();
                        if (e.isDirectory()) continue;
                        if (base.equals("meta.json")) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(bo.toString("UTF-8"));
                            if (m.find()) wanted = m.group(1);
                        } else if (base.endsWith(".so")) {
                            copyDriverStream(zin, new File(dir, base));
                            if (soEntry == null) soEntry = base;
                        }
                    }
                }
                libName = wanted != null && new File(dir, wanted).exists() ? wanted : soEntry;
            } else {
                libName = picked.endsWith(".so") ? picked : picked + ".so";
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot open selected file");
                    copyDriverStream(in, new File(dir, libName));
                }
            }
            if (libName == null) throw new java.io.IOException("No hay ningún archivo .so dentro");
            try (FileWriter w = new FileWriter(new File(dir, "active.txt"))) {
                w.write(libName + "\n");
            }
            showDriver();
            new AlertDialog.Builder(this)
                    .setTitle("Controlador importado")
                    .setMessage(libName + "\n\nCierra el juego por completo y ábrelo otra vez. Para volver al controlador del sistema pulsa Quitar.")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Error al importar controlador")
                    .setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DRIVER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importDriver(data.getData());
        } else if (requestCode == PICK_ICON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            onIconPicked(data.getData());
        }
    }

    private void exportLogs() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "pq-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== Persona Q Port Log Export ===\n");
            try {
                sb.append("App: ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append("\n");
            } catch (Exception ignored) {}
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Date: ").append(new java.util.Date().toString()).append("\n\n");

            try {
                Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-t", "500"});
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("logcat capture error: ").append(e.getMessage()).append("\n");
            }

            File[] possibleLogs = new File[] {
                new File(GameFiles.home(), "zakuro.log"),
                new File(getFilesDir(), "zakuro.log"),
                new File(getExternalFilesDir(null), "zakuro.log")
            };
            for (File zLog : possibleLogs) {
                if (zLog != null && zLog.exists() && zLog.canRead()) {
                    sb.append("\n=== ").append(zLog.getName()).append(" (").append(zLog.getPath()).append(") ===\n");
                    try (BufferedReader br = new BufferedReader(new FileReader(zLog))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    }
                    break;
                }
            }

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(sb.toString());
            }

            Toast.makeText(this, "Registros exportados a Download/pq-log.txt", Toast.LENGTH_SHORT).show();

            Uri logUri = Uri.parse("content://" + getPackageName() + ".logprovider" + out.getAbsolutePath());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, logUri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Persona Q Debug Log");
            share.setClipData(android.content.ClipData.newRawUri("Log", logUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir archivo de registros"));
        } catch (Exception e) {
            Toast.makeText(this, "Error al exportar registros: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}
