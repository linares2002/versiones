package com.samsung.versiones;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ── Configuración ─────────────────────────────────────────────────────────
    private static final String GITHUB_API_RELEASES =
            "https://api.github.com/repos/linares2002/ECG-arteria/releases/latest";
    private static final String[] APK_DOWNLOAD_URLS = {
            "https://github.com/linares2002/ECG-arteria/releases/latest/download/biogw.apk",
            "https://github.com/linares2002/ECG-arteria/releases/latest/download/watch.apk"
    };
    private static final String[] APK_FILENAMES = {"biogw.apk", "watch.apk"};

    // ── Pestañas Teléfono / Reloj ────────────────────────────────────────────
    private static final String[] DEVICES = {"phone", "watch"};
    private static final String[] LABELS  = {"📱 Teléfono", "⌚ Reloj"};

    // ── Paleta UJA ────────────────────────────────────────────────────────────
    private static final int COL_BG       = Color.parseColor("#F5F5F0");
    private static final int COL_WHITE    = Color.parseColor("#FFFFFF");
    private static final int COL_TEXT     = Color.parseColor("#1C2019");
    private static final int COL_TEXT_MED = Color.parseColor("#4A5240");
    private static final int COL_TEXT_DIM = Color.parseColor("#8A9180");
    private static final int COL_GOLD_BG  = Color.parseColor("#FBF7EB");
    private static final int COL_GOLD_BD  = Color.parseColor("#E8D9A0");
    private static final int COL_GREEN    = Color.parseColor("#3D5A34");
    private static final int COL_BORDER   = Color.parseColor("#E2E5DC");
    private static final int COL_ERROR    = Color.parseColor("#C04040");
    private static final int COL_ERROR_BG = Color.parseColor("#FAF0F0");
    private static final int COL_BLUE     = Color.parseColor("#1A4FA3");
    private static final int COL_BLUE_BG  = Color.parseColor("#EBF0FB");

    // ── Colores log ───────────────────────────────────────────────────────────
    private static final String LOG_INFO  = "#4A5240";
    private static final String LOG_OK    = "#3D5A34";
    private static final String LOG_WARN  = "#B06820";
    private static final String LOG_ERROR = "#C04040";
    private static final String LOG_STEP  = "#1A4FA3";

    // ── Modelo: una versión concreta de UN dispositivo (phone o watch) ─────────
    // Teléfono y reloj se actualizan de forma independiente, así que cada uno
    // tiene su propia versión, changelog y fecha — no se asume que coincidan.
    static class DeviceBuild {
        String version   = "";
        String changelog = "";
        String fecha     = "";
        String filename  = "";
        String url       = "";
        long   bytes     = 0;
    }

    // ── Estado ────────────────────────────────────────────────────────────────
    // Carga diferida (lazy): cada dispositivo se consulta solo cuando se
    // visita su pestaña por primera vez, no las dos a la vez al arrancar.
    // Índice 0 = phone, 1 = watch (mismo orden que DEVICES/LABELS).
    private final DeviceBuild[] deviceBuilds = new DeviceBuild[2];
    private final boolean[]     deviceLoaded = new boolean[2];
    private final boolean[]     deviceError  = new boolean[2];
    private final ExecutorService executor  = Executors.newCachedThreadPool();

    // ── Log toggle ────────────────────────────────────────────────────────────
    private View     logPanel;
    private TextView logToggleBtn;
    private boolean  logVisible = true;

    // ── File picker para instalar APK en el reloj ─────────────────────────────
    private ActivityResultLauncher<Intent> apkPickerLauncher;

    // Rastrear qué descarga está activa ("phone" o "watch")
    private long   pendingDownloadId   = -1;
    private String pendingFilename     = "";
    private String pendingDevice       = ""; // "phone" | "watch"
    private File   lastDownloadedFile  = null;

    // ── Views ─────────────────────────────────────────────────────────────────
    private LinearLayout contentFrame;
    private TextView     tvLog;

    // Pestaña activa (0 = phone, 1 = watch) y sus vistas para resaltarla
    private int selectedTab = 0;
    private final TextView[] tabLabels     = new TextView[2];
    private final View[]     tabUnderlines = new View[2];

    // ── BroadcastReceiver descarga ────────────────────────────────────────────
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != pendingDownloadId) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(id);
            android.database.Cursor cursor = dm.query(q);
            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    lastDownloadedFile = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            pendingFilename);
                    pendingDownloadId = -1;
                    log("✓ Descarga completada: " + pendingFilename, LOG_OK);
                    runOnUiThread(MainActivity.this::removeBanner);
                } else {
                    pendingDownloadId = -1;
                    log("✗ Error en la descarga de " + pendingFilename, LOG_ERROR);
                    runOnUiThread(() -> showDownloadErrorBanner());
                }
                cursor.close();
            }
        }
    };

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        apkPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) launchWatchInstallFromUri(uri);
                    }
                });

        setContentView(buildLayout());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        log("▶ Iniciando app…", LOG_STEP);
        fetchDeviceIfNeeded(selectedTab);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(downloadReceiver);
        executor.shutdownNow();
    }

    // Refresco manual desde el botón ⟳ de la cabecera: descarta todo lo
    // cargado/cacheado y vuelve a pedir la versión del dispositivo activo.
    private void refreshAll() {
        log("▶ Refrescando versiones…", LOG_STEP);
        for (int i = 0; i < DEVICES.length; i++) {
            deviceBuilds[i] = null;
            deviceLoaded[i] = false;
            deviceError[i]  = false;
        }
        renderContent();
        fetchDeviceIfNeeded(selectedTab);
    }

    // Lanza la consulta de un dispositivo solo si hace falta: si ya se
    // cargó con éxito, o si ya falló y está esperando un reintento manual,
    // no se repite la petición al cambiar de pestaña.
    private void fetchDeviceIfNeeded(int idx) {
        if (deviceLoaded[idx] || deviceError[idx]) return;
        executor.execute(() -> fetchDeviceBuild(idx));
    }

    private void fetchDeviceBuild(int idx) {
        log("▶ Consultando versión: " + LABELS[idx] + "…", LOG_STEP);
        try {
            deviceBuilds[idx] = fetchLatestForDevice(idx);
            deviceLoaded[idx] = true;
            if (deviceBuilds[idx] != null) {
                log("✓ Versión " + LABELS[idx] + ": " + deviceBuilds[idx].version
                        + " (" + deviceBuilds[idx].fecha + ")", LOG_OK);
            } else {
                log("  Sin versiones de " + LABELS[idx] + " publicadas", LOG_WARN);
            }
        } catch (Exception e) {
            deviceError[idx] = true;
            log("✗ Error obteniendo versión de " + LABELS[idx] + ": " + e.getMessage(), LOG_ERROR);
        }
        runOnUiThread(this::renderContent);
    }

    // Consulta la GitHub API para obtener metadatos del último release
    // (versión, changelog, tamaño del asset). La URL de descarga es fija y
    // no depende de la respuesta del servidor.
    private DeviceBuild fetchLatestForDevice(int idx) throws Exception {
        log("  GET " + GITHUB_API_RELEASES, LOG_INFO);

        URL url = new URL(GITHUB_API_RELEASES);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        int code = conn.getResponseCode();
        if (code == 404) return null; // repositorio sin releases todavía
        if (code != 200) throw new Exception("HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject release = new JSONObject(sb.toString());

        DeviceBuild db = new DeviceBuild();
        db.version  = release.optString("tag_name", "—");
        db.changelog = release.optString("body", "");
        String publishedAt = release.optString("published_at", "");
        db.fecha    = publishedAt.length() >= 10 ? publishedAt.substring(0, 10) : publishedAt;
        String base = APK_FILENAMES[idx].replace(".apk", "");
        db.filename = base + "-" + db.version + ".apk";
        db.url      = APK_DOWNLOAD_URLS[idx];

        // Buscar tamaño del asset concreto en la lista de assets del release
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (db.filename.equals(asset.optString("name"))) {
                    db.bytes = asset.optLong("size", 0);
                    break;
                }
            }
        }
        return db;
    }

    // ── Render principal (según pestaña activa) ─────────────────────────────────

    private void renderContent() {
        contentFrame.removeAllViews();

        int idx = selectedTab;

        if (!deviceLoaded[idx] && !deviceError[idx]) {
            contentFrame.addView(buildLoadingView("Consultando " + LABELS[idx] + "…"));
            fetchDeviceIfNeeded(idx);
            return;
        }

        if (deviceError[idx]) {
            contentFrame.addView(buildErrorCard(
                    "No se pudo obtener la versión de " + LABELS[idx],
                    "Comprueba tu conexión y vuelve a intentarlo",
                    () -> {
                        deviceError[idx] = false;
                        log("▶ Reintentando " + LABELS[idx] + "…", LOG_STEP);
                        renderContent();
                        fetchDeviceIfNeeded(idx);
                    }));
            return;
        }

        // Solo se renderiza el dispositivo de la pestaña activa, y solo se
        // pidió al servidor cuando se visitó esa pestaña por primera vez.
        DeviceBuild build = deviceBuilds[idx];
        // El botón de descarga usa el mismo color (verde) en ambas pestañas;
        // el azul sigue marcando solo el indicador de la pestaña activa.
        contentFrame.addView(buildSelectedDeviceCard(LABELS[idx], build, COL_GREEN, DEVICES[idx]));
    }

    // ── Tarjeta grande de la pestaña activa ─────────────────────────────────────

    private View buildSelectedDeviceCard(String label, DeviceBuild build, int color, String device) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRectStroke(dp(16), COL_WHITE, COL_BORDER));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (build == null) {
            TextView deviceLabel = new TextView(this);
            deviceLabel.setText(label.toUpperCase(Locale.getDefault()));
            deviceLabel.setTextColor(COL_TEXT_DIM);
            deviceLabel.setTextSize(10);
            deviceLabel.setTypeface(Typeface.DEFAULT_BOLD);
            deviceLabel.setLetterSpacing(0.1f);
            card.addView(deviceLabel);

            LinearLayout noVersion = new LinearLayout(this);
            noVersion.setOrientation(LinearLayout.HORIZONTAL);
            noVersion.setGravity(Gravity.CENTER_VERTICAL);
            noVersion.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable nwBg = new GradientDrawable();
            nwBg.setColor(COL_GOLD_BG);
            nwBg.setCornerRadius(dp(10));
            nwBg.setStroke(dp(1), COL_GOLD_BD);
            noVersion.setBackground(nwBg);
            LinearLayout.LayoutParams nwLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            nwLp.topMargin = dp(10);
            noVersion.setLayoutParams(nwLp);
            TextView nwTv = new TextView(this);
            nwTv.setText("Sin versiones publicadas todavía");
            nwTv.setTextColor(COL_TEXT_MED);
            nwTv.setTextSize(13);
            noVersion.addView(nwTv);
            card.addView(noVersion);
            return card;
        }

        // Fila: versión grande (izquierda) + changelog (derecha, más pequeño)
        LinearLayout versionRow = new LinearLayout(this);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        versionRow.setGravity(Gravity.TOP);

        TextView versionTv = new TextView(this);
        versionTv.setText(build.version);
        versionTv.setTextColor(COL_TEXT);
        versionTv.setTextSize(26);
        versionTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.rightMargin = dp(14);
        versionTv.setLayoutParams(verLp);
        versionRow.addView(versionTv);

        if (!build.changelog.isEmpty()) {
            TextView changelogTv = new TextView(this);
            changelogTv.setText(build.changelog);
            changelogTv.setTextColor(COL_TEXT_MED);
            changelogTv.setTextSize(12);
            changelogTv.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clLp.topMargin = dp(8);
            changelogTv.setLayoutParams(clLp);
            versionRow.addView(changelogTv);
        }

        card.addView(versionRow);

        // Etiqueta filename
        if (!build.filename.isEmpty()) {
            TextView fnTv = new TextView(this);
            fnTv.setText(build.filename);
            fnTv.setTextColor(COL_TEXT_MED);
            fnTv.setTextSize(10);
            fnTv.setTypeface(Typeface.MONOSPACE);
            fnTv.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable fnBg = new GradientDrawable();
            fnBg.setColor(COL_GOLD_BG);
            fnBg.setCornerRadius(dp(12));
            fnBg.setStroke(dp(1), COL_GOLD_BD);
            fnTv.setBackground(fnBg);
            LinearLayout.LayoutParams fnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            fnLp.topMargin = dp(10);
            fnTv.setLayoutParams(fnLp);
            card.addView(fnTv);
        }

        card.addView(divider());

        // Botón descargar
        TextView btn = new TextView(this);
        btn.setText("⬇  Descargar");
        btn.setTextColor(COL_WHITE);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(color);
        btnBg.setCornerRadius(dp(12));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(4);
        btn.setLayoutParams(btnLp);
        btn.setBackground(btnBg);
        btn.setOnClickListener(v -> startDownload(device, build.filename, build.url));
        card.addView(btn);

        // Botón secundario: abrir carpeta de descargas
        TextView openDlBtn = new TextView(this);
        openDlBtn.setText("📁  Abrir descargas");
        openDlBtn.setTextColor(COL_TEXT_MED);
        openDlBtn.setTextSize(13);
        openDlBtn.setGravity(Gravity.CENTER);
        openDlBtn.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable openDlBg = new GradientDrawable();
        openDlBg.setColor(COL_WHITE);
        openDlBg.setCornerRadius(dp(10));
        openDlBg.setStroke(dp(1), COL_BORDER);
        LinearLayout.LayoutParams openDlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        openDlLp.topMargin = dp(8);
        openDlBtn.setLayoutParams(openDlLp);
        openDlBtn.setBackground(openDlBg);
        openDlBtn.setOnClickListener(v -> openDownloadsFolder());
        card.addView(openDlBtn);

        // Botón seleccionar APK e instalar en reloj (solo pestaña watch)
        if ("watch".equals(device)) {
            TextView watchPickBtn = new TextView(this);
            watchPickBtn.setText("⌚  Seleccionar APK e instalar en reloj");
            watchPickBtn.setTextColor(COL_BLUE);
            watchPickBtn.setTextSize(13);
            watchPickBtn.setTypeface(Typeface.DEFAULT_BOLD);
            watchPickBtn.setGravity(Gravity.CENTER);
            watchPickBtn.setPadding(0, dp(10), 0, dp(10));
            GradientDrawable watchPickBg = new GradientDrawable();
            watchPickBg.setColor(COL_BLUE_BG);
            watchPickBg.setCornerRadius(dp(10));
            watchPickBg.setStroke(dp(1), COL_BLUE);
            watchPickBtn.setBackground(watchPickBg);
            LinearLayout.LayoutParams wpLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            wpLp.topMargin = dp(8);
            watchPickBtn.setLayoutParams(wpLp);
            watchPickBtn.setOnClickListener(v -> {
                Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                pickIntent.setType("application/vnd.android.package-archive");
                pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                apkPickerLauncher.launch(pickIntent);
            });
            card.addView(watchPickBtn);
        }

        return card;
    }

    // ── Descarga ──────────────────────────────────────────────────────────────

    private void startDownload(String device, String filename, String downloadUrl) {
        if (filename.isEmpty()) {
            Toast.makeText(this, "Nombre de archivo no disponible", Toast.LENGTH_SHORT).show();
            return;
        }

        log("▶ Iniciando descarga [" + device + "] " + filename, LOG_STEP);

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // Cancelar descarga anterior
        if (pendingDownloadId != -1) {
            dm.remove(pendingDownloadId);
            log("  Descarga anterior cancelada", LOG_WARN);
            pendingDownloadId = -1;
        }

        // Borrar archivo previo
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) {
            int dashIdx = filename.indexOf('-');
            String baseName = (dashIdx > 0) ? filename.substring(0, dashIdx) : filename.replace(".apk", "");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(baseName) && f.getName().endsWith(".apk")) {
                        boolean deleted = f.delete();
                        log("  Borrado archivo previo: " + f.getName() + (deleted ? " ✓" : " ✗"), LOG_INFO);
                    }
                }
            }
        }

        log("  URL: " + downloadUrl, LOG_INFO);

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
        req.setTitle(filename);
        req.setDescription("BioGW · " + device);
        req.setMimeType("application/vnd.android.package-archive");
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        pendingDownloadId = dm.enqueue(req);
        pendingFilename   = filename;
        pendingDevice     = device;

        log("  Descarga encolada (id=" + pendingDownloadId + ")", LOG_INFO);
        showDownloadingBanner(filename);
    }

    // ── Banners de descarga ───────────────────────────────────────────────────

    private void showDownloadingBanner(String filename) {
        removeBanner();

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_BLUE_BG);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COL_BLUE);
        banner.setBackground(bg);
        banner.setTag("download_banner");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        banner.setLayoutParams(lp);

        ProgressBar pb = new ProgressBar(this);
        pb.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        banner.addView(pb);

        TextView msg = new TextView(this);
        msg.setText("  Descargando " + filename + "…");
        msg.setTextColor(COL_BLUE);
        msg.setTextSize(13);
        msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(msg);

        contentFrame.addView(banner);
    }

    private void showDownloadErrorBanner() {
        removeBanner();

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_ERROR_BG);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COL_ERROR);
        banner.setBackground(bg);
        banner.setTag("download_banner");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        banner.setLayoutParams(lp);

        TextView msg = new TextView(this);
        msg.setText("⚠ Error en la descarga. Inténtalo de nuevo.");
        msg.setTextColor(COL_ERROR);
        msg.setTextSize(13);
        msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        banner.addView(msg);

        contentFrame.addView(banner);
    }

    private void removeBanner() {
        View old = contentFrame.findViewWithTag("download_banner");
        if (old != null) contentFrame.removeView(old);
    }

    // ── Instalar en reloj desde URI seleccionada ──────────────────────────────

    private void launchWatchInstallFromUri(Uri uri) {
        log("▶ Preparando APK seleccionada…", LOG_STEP);
        executor.execute(() -> {
            try {
                String filename = "selected.apk";
                try (android.database.Cursor c = getContentResolver().query(
                        uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) filename = c.getString(idx);
                    }
                }
                File dest = new File(getCacheDir(), filename);
                try (InputStream in  = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                log("✓ APK lista: " + filename, LOG_OK);
                final String finalPath = dest.getAbsolutePath();
                runOnUiThread(() -> {
                    Intent intent = new Intent(MainActivity.this, WatchInstallActivity.class);
                    intent.putExtra(WatchInstallActivity.EXTRA_APK_PATH, finalPath);
                    startActivity(intent);
                });
            } catch (Exception e) {
                log("✗ Error preparando APK: " + e.getMessage(), LOG_ERROR);
            }
        });
    }

    // ── Abrir carpeta de descargas ────────────────────────────────────────────

    private void openDownloadsFolder() {
        try {
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Downloads");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent myFiles = getPackageManager()
                        .getLaunchIntentForPackage("com.sec.android.app.myfiles");
                if (myFiles != null) {
                    myFiles.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(myFiles);
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Log panel ─────────────────────────────────────────────────────────────

    /**
     * Añade una línea al panel de log con timestamp y color.
     * Seguro llamar desde cualquier hilo.
     */
    private void log(String message, String hexColor) {
        String ts  = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + message;
        android.util.Log.d("BioGW", line);

        runOnUiThread(() -> {
            if (tvLog == null) return;
            String current = tvLog.getText().toString();
            tvLog.setText(current.isEmpty() ? line : current + "\n" + line);
            // post(): espera a que Android termine de medir/maquetar el
            // texto recién puesto antes de calcular el scroll. Llamar a
            // getLayout() justo después de setText() devuelve el layout
            // VIEJO (de antes de añadir la línea), así que el cálculo
            // siempre se quedaba corto y no llegaba a la última línea.
            tvLog.post(() -> {
                if (tvLog.getLayout() == null) return;
                int scroll = tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
                tvLog.scrollTo(0, Math.max(scroll, 0));
            });
        });
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        root.addView(buildTopBar());
        root.addView(buildTabs());
        root.addView(buildDivider());

        // Contenido scrollable (tarjeta versión + banners)
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        contentFrame = new LinearLayout(this);
        contentFrame.setOrientation(LinearLayout.VERTICAL);
        contentFrame.setPadding(dp(20), dp(20), dp(20), dp(20));
        sv.addView(contentFrame);
        root.addView(sv);

        // Panel de log fijo en la parte inferior
        logPanel = buildLogPanel();
        root.addView(logPanel);

        // Conectar toggle (logToggleBtn se crea en buildTopBar, logPanel ya disponible)
        logToggleBtn.setOnClickListener(v -> {
            logVisible = !logVisible;
            logPanel.setVisibility(logVisible ? View.VISIBLE : View.GONE);
            logToggleBtn.setAlpha(logVisible ? 1f : 0.4f);
        });

        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COL_WHITE);
        bar.setPadding(dp(20), dp(48), dp(20), dp(16));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(COL_WHITE);
        barBg.setStroke(dp(1), COL_BORDER);
        bar.setBackground(barBg);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("ECG Releases");
        title.setTextColor(COL_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("V1.0.1");
        subtitle.setTextColor(COL_TEXT_DIM);
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);

        bar.addView(titles);

        // Botón toggle del panel de log
        logToggleBtn = new TextView(this);
        logToggleBtn.setText("LOG");
        logToggleBtn.setTextColor(COL_TEXT_MED);
        logToggleBtn.setTextSize(11);
        logToggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        logToggleBtn.setGravity(Gravity.CENTER);
        logToggleBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable logBtnBg = new GradientDrawable();
        logBtnBg.setColor(COL_BG);
        logBtnBg.setCornerRadius(dp(10));
        logBtnBg.setStroke(dp(1), COL_BORDER);
        logToggleBtn.setBackground(logBtnBg);
        LinearLayout.LayoutParams logBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        logBtnLp.rightMargin = dp(8);
        logToggleBtn.setLayoutParams(logBtnLp);
        bar.addView(logToggleBtn);

        // Botón de refresco manual: vuelve a consultar GitHub Releases desde
        // cero. Útil si la app llevaba tiempo en segundo plano y se publicó
        // una nueva versión mientras tanto.
        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("⟳");
        refreshBtn.setTextColor(COL_TEXT_MED);
        refreshBtn.setTextSize(20);
        refreshBtn.setTypeface(Typeface.DEFAULT_BOLD);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setColor(COL_BG);
        refreshBg.setCornerRadius(dp(10));
        refreshBg.setStroke(dp(1), COL_BORDER);
        refreshBtn.setBackground(refreshBg);
        refreshBtn.setOnClickListener(v -> refreshAll());
        bar.addView(refreshBtn);

        return bar;
    }

    // ── Pestañas Teléfono / Reloj ────────────────────────────────────────────

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(COL_WHITE);

        for (int i = 0; i < DEVICES.length; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            tab.setPadding(0, dp(14), 0, 0);
            tab.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tab.setOnClickListener(v -> selectTab(idx));

            tabLabels[i] = new TextView(this);
            tabLabels[i].setText(LABELS[i]);
            tabLabels[i].setTextSize(14);
            tabLabels[i].setGravity(Gravity.CENTER);
            tabLabels[i].setPadding(0, 0, 0, dp(10));
            tab.addView(tabLabels[i]);

            tabUnderlines[i] = new View(this);
            tabUnderlines[i].setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
            tab.addView(tabUnderlines[i]);

            tabs.addView(tab);
        }
        refreshTabStyles();
        return tabs;
    }

    private void selectTab(int idx) {
        if (idx == selectedTab) return;
        selectedTab = idx;
        refreshTabStyles();
        log("▶ Pestaña: " + LABELS[idx], LOG_STEP);
        renderContent();
        fetchDeviceIfNeeded(idx); // carga diferida: solo pide si no se pidió antes
    }

    // El color de la pestaña activa coincide con el color que ya usa cada
    // dispositivo en el resto de la UI (verde = teléfono, azul = reloj).
    private void refreshTabStyles() {
        for (int i = 0; i < DEVICES.length; i++) {
            boolean active = i == selectedTab;
            int activeColor = i == 0 ? COL_GREEN : COL_BLUE;
            tabLabels[i].setTextColor(active ? activeColor : COL_TEXT_DIM);
            tabLabels[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabUnderlines[i].setBackgroundColor(active ? activeColor : Color.TRANSPARENT);
        }
    }

    private View buildLogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#1C2019"));
        panel.setPadding(dp(14), dp(10), dp(14), dp(14));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        panel.setLayoutParams(panelLp);

        // En Android moderno (edge-to-edge) el contenido se dibuja por
        // detrás de la barra de gestos/navegación a menos que tú mismo le
        // sumes ese espacio. Sin esto, las últimas líneas del log quedan
        // físicamente debajo de los controles del sistema.
        final int basePaddingBottom = dp(14);
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBarBottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(14), dp(10), dp(14), basePaddingBottom + navBarBottom);
            return insets;
        });

        // Cabecera log con botón limpiar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        TextView logTitle = new TextView(this);
        logTitle.setText("▸ Registro de ejecución");
        logTitle.setTextColor(Color.parseColor("#8A9180"));
        logTitle.setTextSize(11);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        logTitle.setLayoutParams(ltLp);
        header.addView(logTitle);

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Limpiar");
        clearBtn.setTextColor(Color.parseColor("#6B7B6A"));
        clearBtn.setTextSize(11);
        clearBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
        clearBtn.setOnClickListener(v -> tvLog.setText(""));
        header.addView(clearBtn);
        panel.addView(header);

        // TextView del log
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#A8B8A0"));
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setVerticalScrollBarEnabled(true);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setMinLines(3);
        tvLog.setMaxLines(6);
        tvLog.setLineSpacing(dp(4), 1f);
        panel.addView(tvLog);

        return panel;
    }

    // ── Vistas auxiliares ─────────────────────────────────────────────────────

    private View buildLoadingView(String msg) {
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        ProgressBar pb = new ProgressBar(this);
        pb.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        loading.addView(pb);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(COL_TEXT_DIM);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(12), 0, 0);
        loading.addView(tv);

        return loading;
    }

    private View buildErrorCard(String title, String hint, Runnable onRetry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_ERROR_BG);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#E0B0B0"));
        card.setBackground(bg);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);

        TextView msg = new TextView(this);
        msg.setText(title);
        msg.setTextColor(COL_ERROR);
        msg.setTextSize(14);
        msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(8), 0, 0);
        card.addView(msg);

        TextView hintTv = new TextView(this);
        hintTv.setText(hint);
        hintTv.setTextColor(COL_TEXT_DIM);
        hintTv.setTextSize(12);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, dp(4), 0, dp(16));
        card.addView(hintTv);

        TextView retry = new TextView(this);
        retry.setText("Reintentar");
        retry.setTextColor(COL_WHITE);
        retry.setTextSize(13);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(24), dp(10), dp(24), dp(10));
        GradientDrawable retryBg = new GradientDrawable();
        retryBg.setColor(COL_ERROR);
        retryBg.setCornerRadius(dp(10));
        retry.setBackground(retryBg);
        retry.setOnClickListener(v -> onRetry.run());
        card.addView(retry);

        return card;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COL_BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin    = dp(14);
        lp.bottomMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private View buildDivider() {
        View v = new View(this);
        v.setBackgroundColor(COL_BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private GradientDrawable roundRectStroke(int radius, int fill, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
    }
}
