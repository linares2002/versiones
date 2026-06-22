package com.samsung.versiones;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WatchInstallActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";

    private static final int COL_BG       = Color.parseColor("#F5F5F0");
    private static final int COL_WHITE    = Color.parseColor("#FFFFFF");
    private static final int COL_TEXT     = Color.parseColor("#1C2019");
    private static final int COL_TEXT_DIM = Color.parseColor("#8A9180");
    private static final int COL_GREEN    = Color.parseColor("#3D5A34");
    private static final int COL_BORDER   = Color.parseColor("#E2E5DC");
    private static final int COL_BLUE     = Color.parseColor("#1A4FA3");
    private static final int COL_BLUE_BG  = Color.parseColor("#EBF0FB");
    private static final int COL_ERROR    = Color.parseColor("#C04040");
    private static final int COL_ORANGE   = Color.parseColor("#B06820");

    private String apkPath;
    private volatile String discoveredIp;
    private volatile int    discoveredPairPort;
    private volatile int    discoveredConnectPort;
    private AppState state = AppState.IDLE;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;

    private AdbWifiManager adbManager;
    private final ExecutorService executor    = Executors.newCachedThreadPool();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private TextView     tvStatus;
    private ProgressBar  progressBar;
    private LinearLayout cardCode;
    private EditText     etCode;
    private LinearLayout cardManual;
    private EditText     etManualIp;
    private EditText     etManualPort;
    private EditText     etManualCode;
    private LinearLayout cardConnected;
    private TextView     tvConnectedDevice;
    private TextView     tvApkName;
    private TextView     btnInstall;
    private TextView     tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        apkPath    = getIntent().getStringExtra(EXTRA_APK_PATH);
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        adbManager = new AdbWifiManager(this);
        setContentView(buildLayout());
        setState(AppState.IDLE);
        executor.execute(() -> adbManager.extractAdbBinary());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        executor.execute(() -> adbManager.killServer());
        executor.shutdownNow();
    }

    // ── mDNS ─────────────────────────────────────────────────────────────────

    private void startMdnsDiscovery() {
        setState(AppState.DISCOVERING);
        log("🔍 Buscando reloj en la red WiFi...");
        log("   Activa «Emparejar nuevo dispositivo» en el reloj");

        pairingListener = createDiscoveryListener("_adb-tls-pairing._tcp.", info -> {
            String ip  = extractIp(info);
            int    port = info.getPort();
            if (ip == null) return;
            mainHandler.post(() -> {
                discoveredIp       = ip;
                discoveredPairPort = port;
                log("✅ Reloj encontrado: " + ip + ":" + port);
                log("🔑 Introduce el código de 6 dígitos del reloj:");
                setState(AppState.WAITING_CODE);
                etManualIp.setText(ip);
                etManualPort.setText(String.valueOf(port));
            });
        });

        connectListener = createDiscoveryListener("_adb-tls-connect._tcp.", info -> {
            String ip  = extractIp(info);
            int    port = info.getPort();
            if (ip == null) return;
            mainHandler.post(() -> {
                discoveredConnectPort = port;
                log("📡 Puerto de conexión: " + ip + ":" + port);
                // Intenta conectar directamente: si ya hubo un emparejamiento
                // previo nos saltamos el paso del código automáticamente.
                tryDirectConnect(ip, port);
            });
        });

        nsdManager.discoverServices("_adb-tls-pairing._tcp.",
                NsdManager.PROTOCOL_DNS_SD, pairingListener);
        nsdManager.discoverServices("_adb-tls-connect._tcp.",
                NsdManager.PROTOCOL_DNS_SD, connectListener);
    }

    private NsdManager.DiscoveryListener createDiscoveryListener(
            String type, ResolvedCallback onResolved) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String s) {}
            @Override public void onDiscoveryStopped(String s) {}
            @Override public void onStartDiscoveryFailed(String s, int e) {
                mainHandler.post(() -> log("⚠️ Error mDNS: " + e));
            }
            @Override public void onStopDiscoveryFailed(String s, int e) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                        mainHandler.post(() -> log("⚠️ No se pudo resolver: " + e));
                    }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        onResolved.onResolved(i);
                    }
                });
            }
        };
    }

    private interface ResolvedCallback {
        void onResolved(NsdServiceInfo info);
    }

    /** Extrae la dirección IP de un NsdServiceInfo evitando direcciones IPv6 con zone ID
     *  (p.ej. "fe80::1%wlan0") que ADB no acepta. Prefiere IPv4 si el host la tiene. */
    private String extractIp(NsdServiceInfo info) {
        if (info.getHost() == null) return null;
        String addr = info.getHost().getHostAddress();
        if (addr == null) return null;
        // Eliminar zone ID de IPv6 (e.g. "fe80::1%wlan0" → "fe80::1")
        int zoneIdx = addr.indexOf('%');
        if (zoneIdx >= 0) addr = addr.substring(0, zoneIdx);
        return addr;
    }

    /** Intenta conectar sin emparejar (emparejamiento previo ya existente).
     *  Si tiene éxito y seguimos en DISCOVERING, pasamos directamente a CONNECTED. */
    private void tryDirectConnect(String ip, int port) {
        executor.execute(() -> {
            AdbResult result = adbManager.connect(ip, port);
            mainHandler.post(() -> {
                if (state != AppState.DISCOVERING) return; // ya entró en flujo de emparejamiento
                if (result.success) {
                    stopDiscovery();
                    log("✅ Conectado directamente (emparejamiento previo): " + ip + ":" + port);
                    setState(AppState.CONNECTED);
                    tvConnectedDevice.setText("Conectado: " + ip + ":" + port);
                    if (apkPath != null) {
                        tvApkName.setText(new File(apkPath).getName());
                        btnInstall.setAlpha(1f);
                        btnInstall.setEnabled(true);
                    }
                } else {
                    log("  Sin emparejamiento previo — activa «Emparejar nuevo dispositivo»");
                }
            });
        });
    }

    private void stopDiscovery() {
        try { if (pairingListener != null) nsdManager.stopServiceDiscovery(pairingListener); }
        catch (Exception ignored) {}
        try { if (connectListener  != null) nsdManager.stopServiceDiscovery(connectListener); }
        catch (Exception ignored) {}
    }

    // ── Emparejamiento ────────────────────────────────────────────────────────

    private void performPairing(String ip, int pairPort, String code) {
        setState(AppState.PAIRING);
        log("🔗 Emparejando con " + ip + ":" + pairPort + " ...");
        executor.execute(() -> {
            AdbResult result = adbManager.pair(ip, pairPort, code);
            mainHandler.post(() -> {
                if (result.success) {
                    log("✅ Emparejamiento correcto");
                    log("⚡ Conectando...");
                    stopDiscovery();
                    autoConnect(ip);
                } else {
                    log("❌ " + result.output);
                    setState(AppState.WAITING_CODE);
                }
            });
        });
    }

    private void autoConnect(String ip) {
        executor.execute(() -> {
            int connectPort = discoveredConnectPort;
            if (connectPort <= 0) {
                mainHandler.post(() -> log("🔍 Esperando puerto de conexión..."));
                CountDownLatch latch = new CountDownLatch(1);
                NsdManager.DiscoveryListener tmp = createDiscoveryListener(
                        "_adb-tls-connect._tcp.", info -> {
                            String resolvedIp = extractIp(info);
                            if (ip.equals(resolvedIp)) {
                                discoveredConnectPort = info.getPort();
                                latch.countDown();
                            }
                        });
                mainHandler.post(() -> nsdManager.discoverServices(
                        "_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, tmp));
                try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                try { nsdManager.stopServiceDiscovery(tmp); } catch (Exception ignored) {}
                connectPort = discoveredConnectPort;
            }

            if (connectPort <= 0) {
                mainHandler.post(() -> {
                    log("⚠️ No se detectó el puerto de conexión");
                    log("💡 Introduce la IP y puerto de «Depuración inalámbrica»");
                    setState(AppState.WAITING_CODE);
                });
                return;
            }

            final int finalPort = connectPort;
            AdbResult result = adbManager.connect(ip, finalPort);
            mainHandler.post(() -> {
                if (result.success) {
                    log("✅ Conectado a " + ip + ":" + finalPort);
                    setState(AppState.CONNECTED);
                    tvConnectedDevice.setText("Conectado: " + ip + ":" + finalPort);
                    if (apkPath != null) {
                        tvApkName.setText(new File(apkPath).getName());
                        btnInstall.setAlpha(1f);
                        btnInstall.setEnabled(true);
                    }
                } else {
                    log("❌ " + result.output);
                    setState(AppState.ERROR);
                }
            });
        });
    }

    // ── Instalación ───────────────────────────────────────────────────────────

    private void installApk() {
        if (apkPath == null) return;
        setState(AppState.INSTALLING);
        log("📲 Instalando " + new File(apkPath).getName() + "...");
        executor.execute(() -> {
            AdbResult result = adbManager.install(apkPath);
            mainHandler.post(() -> {
                if (result.success) {
                    log("🎉 ¡Instalación completada!");
                    setState(AppState.DONE);
                } else {
                    log("❌ " + result.output);
                    setState(AppState.CONNECTED);
                    btnInstall.setAlpha(1f);
                    btnInstall.setEnabled(true);
                }
            });
        });
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    private void setState(AppState s) {
        state = s;
        switch (s) {
            case IDLE:
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("Sin conectar");
                tvStatus.setTextColor(COL_TEXT_DIM);
                break;
            case DISCOVERING:
                progressBar.setVisibility(View.VISIBLE);
                tvStatus.setText("Buscando reloj...");
                tvStatus.setTextColor(COL_BLUE);
                cardCode.setVisibility(View.GONE);
                break;
            case WAITING_CODE:
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("Reloj encontrado — introduce el código");
                tvStatus.setTextColor(COL_GREEN);
                cardCode.setVisibility(View.VISIBLE);
                etCode.requestFocus();
                break;
            case PAIRING:
                progressBar.setVisibility(View.VISIBLE);
                tvStatus.setText("Emparejando...");
                tvStatus.setTextColor(COL_ORANGE);
                break;
            case CONNECTED:
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("✅ Conectado al reloj");
                tvStatus.setTextColor(COL_GREEN);
                cardCode.setVisibility(View.GONE);
                cardConnected.setVisibility(View.VISIBLE);
                break;
            case INSTALLING:
                progressBar.setVisibility(View.VISIBLE);
                tvStatus.setText("Instalando APK...");
                tvStatus.setTextColor(COL_ORANGE);
                btnInstall.setEnabled(false);
                btnInstall.setAlpha(0.5f);
                break;
            case DONE:
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("🎉 APK instalada en el reloj");
                tvStatus.setTextColor(COL_GREEN);
                break;
            case ERROR:
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("Error de conexión");
                tvStatus.setTextColor(COL_ERROR);
                break;
        }
    }

    private void log(String message) {
        String current = tvLog.getText().toString();
        tvLog.setText(current.isEmpty() ? message : current + "\n" + message);
        tvLog.post(() -> {
            if (tvLog.getLayout() == null) return;
            int scroll = tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        });
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13f);
        tvStatus.setPadding(0, 0, 0, dp(4));
        content.addView(tvStatus);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.topMargin = dp(6);
        pbLp.bottomMargin = dp(16);
        progressBar.setLayoutParams(pbLp);
        content.addView(progressBar);

        content.addView(buildStepCard());
        content.addView(spacer(dp(14)));

        cardCode = buildCodeCard();
        cardCode.setVisibility(View.GONE);
        content.addView(cardCode);
        content.addView(spacer(dp(14)));

        cardManual = buildManualCard();
        cardManual.setVisibility(View.GONE);
        content.addView(cardManual);
        content.addView(spacer(dp(14)));

        cardConnected = buildInstallCard();
        cardConnected.setVisibility(View.GONE);
        content.addView(cardConnected);
        content.addView(spacer(dp(14)));

        content.addView(buildLogCard());

        scroll.addView(content);
        root.addView(scroll);
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COL_WHITE);
        bar.setPadding(dp(20), dp(48), dp(20), dp(16));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_WHITE);
        bg.setStroke(dp(1), COL_BORDER);
        bar.setBackground(bg);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(COL_TEXT);
        back.setPadding(0, 0, dp(16), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Instalar en reloj");
        title.setTextColor(COL_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Samsung Galaxy Watch · ADB WiFi");
        sub.setTextColor(COL_TEXT_DIM);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);

        bar.addView(col);
        return bar;
    }

    private LinearLayout buildStepCard() {
        LinearLayout card = card();
        card.addView(stepLabel("1", "Buscar reloj"));
        card.addView(spacer(dp(6)));

        TextView desc = new TextView(this);
        desc.setText("Asegúrate de que el reloj y el móvil están en la misma red WiFi.\n\nEn el reloj: Ajustes → Opciones de desarrollador → Depuración inalámbrica → Emparejar nuevo dispositivo");
        desc.setTextColor(COL_TEXT_DIM);
        desc.setTextSize(13f);
        desc.setLineSpacing(dp(2), 1f);
        card.addView(desc);
        card.addView(spacer(dp(16)));

        TextView btn = actionButton("Iniciar búsqueda automática", COL_BLUE);
        btn.setOnClickListener(v -> startMdnsDiscovery());
        card.addView(btn);
        card.addView(spacer(dp(12)));

        TextView toggleManual = new TextView(this);
        toggleManual.setText("¿No se detecta? Introducir datos manualmente →");
        toggleManual.setTextColor(COL_BLUE);
        toggleManual.setTextSize(12f);
        toggleManual.setPadding(0, dp(4), 0, dp(4));
        toggleManual.setOnClickListener(v ->
                cardManual.setVisibility(
                        cardManual.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        card.addView(toggleManual);
        return card;
    }

    private LinearLayout buildCodeCard() {
        LinearLayout card = card();
        card.addView(stepLabel("2", "Código de emparejamiento"));
        card.addView(spacer(dp(6)));

        TextView desc = new TextView(this);
        desc.setText("Introduce el código de 6 dígitos que muestra el reloj:");
        desc.setTextColor(COL_TEXT_DIM);
        desc.setTextSize(13f);
        card.addView(desc);
        card.addView(spacer(dp(14)));

        etCode = new EditText(this);
        etCode.setHint("000000");
        etCode.setTextSize(30f);
        etCode.setGravity(Gravity.CENTER);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCode.setTypeface(Typeface.MONOSPACE);
        etCode.setTextColor(COL_TEXT);
        etCode.setHintTextColor(COL_TEXT_DIM);
        etCode.setBackground(roundRect(dp(12), COL_BLUE_BG, COL_BLUE));
        etCode.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(etCode);
        card.addView(spacer(dp(12)));

        TextView confirmBtn = actionButton("Emparejar", COL_GREEN);
        confirmBtn.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (code.length() != 6) {
                Toast.makeText(this, "El código debe tener 6 dígitos", Toast.LENGTH_SHORT).show();
                return;
            }
            if (discoveredIp == null || discoveredPairPort == 0) {
                Toast.makeText(this, "No hay reloj detectado", Toast.LENGTH_SHORT).show();
                return;
            }
            performPairing(discoveredIp, discoveredPairPort, code);
        });
        card.addView(confirmBtn);
        return card;
    }

    private LinearLayout buildManualCard() {
        LinearLayout card = card();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0F2FF"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#C0C8E8"));
        card.setBackground(bg);

        TextView label = new TextView(this);
        label.setText("Entrada manual");
        label.setTextColor(COL_TEXT_DIM);
        label.setTextSize(12f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(label);
        card.addView(spacer(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        etManualIp = new EditText(this);
        etManualIp.setHint("IP  ej. 192.168.1.X");
        etManualIp.setTextSize(13f);
        etManualIp.setTextColor(COL_TEXT);
        etManualIp.setHintTextColor(COL_TEXT_DIM);
        etManualIp.setInputType(InputType.TYPE_CLASS_TEXT);
        etManualIp.setBackground(roundRect(dp(10), COL_WHITE, COL_BORDER));
        etManualIp.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        ipLp.rightMargin = dp(8);
        etManualIp.setLayoutParams(ipLp);
        row.addView(etManualIp);

        etManualPort = new EditText(this);
        etManualPort.setHint("Puerto");
        etManualPort.setTextSize(13f);
        etManualPort.setTextColor(COL_TEXT);
        etManualPort.setHintTextColor(COL_TEXT_DIM);
        etManualPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etManualPort.setBackground(roundRect(dp(10), COL_WHITE, COL_BORDER));
        etManualPort.setPadding(dp(10), dp(10), dp(10), dp(10));
        etManualPort.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(etManualPort);
        card.addView(row);
        card.addView(spacer(dp(8)));

        etManualCode = new EditText(this);
        etManualCode.setHint("Código 6 dígitos");
        etManualCode.setTextSize(20f);
        etManualCode.setGravity(Gravity.CENTER);
        etManualCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etManualCode.setTypeface(Typeface.MONOSPACE);
        etManualCode.setTextColor(COL_TEXT);
        etManualCode.setHintTextColor(COL_TEXT_DIM);
        etManualCode.setBackground(roundRect(dp(10), COL_WHITE, COL_BORDER));
        etManualCode.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(etManualCode);
        card.addView(spacer(dp(12)));

        TextView manualBtn = actionButton("Emparejar manualmente", COL_ORANGE);
        manualBtn.setOnClickListener(v -> {
            String ip      = etManualIp.getText().toString().trim();
            String portStr = etManualPort.getText().toString().trim();
            String code    = etManualCode.getText().toString().trim();
            if (ip.isEmpty() || portStr.isEmpty() || code.length() != 6) {
                Toast.makeText(this, "Rellena todos los campos", Toast.LENGTH_SHORT).show();
                return;
            }
            discoveredIp       = ip;
            discoveredPairPort = Integer.parseInt(portStr);
            performPairing(ip, discoveredPairPort, code);
        });
        card.addView(manualBtn);
        return card;
    }

    private LinearLayout buildInstallCard() {
        LinearLayout card = card();
        card.addView(stepLabel("3", "Instalar APK en el reloj"));
        card.addView(spacer(dp(8)));

        tvConnectedDevice = new TextView(this);
        tvConnectedDevice.setText("Conectado: —");
        tvConnectedDevice.setTextColor(COL_GREEN);
        tvConnectedDevice.setTextSize(12f);
        tvConnectedDevice.setTypeface(Typeface.MONOSPACE);
        card.addView(tvConnectedDevice);
        card.addView(spacer(dp(12)));

        tvApkName = new TextView(this);
        tvApkName.setText(apkPath != null ? new File(apkPath).getName() : "Sin APK");
        tvApkName.setTextColor(COL_TEXT_DIM);
        tvApkName.setTextSize(12f);
        tvApkName.setTypeface(Typeface.MONOSPACE);
        tvApkName.setGravity(Gravity.CENTER);
        tvApkName.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#FBF7EB"));
        pillBg.setCornerRadius(dp(20));
        pillBg.setStroke(dp(1), Color.parseColor("#E8D9A0"));
        tvApkName.setBackground(pillBg);
        card.addView(tvApkName);
        card.addView(spacer(dp(14)));

        btnInstall = actionButton("⌚  Instalar en el reloj", COL_BLUE);
        btnInstall.setEnabled(apkPath != null);
        btnInstall.setAlpha(apkPath != null ? 1f : 0.4f);
        btnInstall.setOnClickListener(v -> installApk());
        card.addView(btnInstall);
        return card;
    }

    private LinearLayout buildLogCard() {
        LinearLayout card = card();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView logLabel = new TextView(this);
        logLabel.setText("Registro");
        logLabel.setTextColor(COL_TEXT_DIM);
        logLabel.setTextSize(12f);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(logLabel);

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Limpiar");
        clearBtn.setTextColor(COL_TEXT_DIM);
        clearBtn.setTextSize(11f);
        clearBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearBtn.setOnClickListener(v -> tvLog.setText(""));
        header.addView(clearBtn);
        card.addView(header);
        card.addView(spacer(dp(8)));

        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#4A5240"));
        tvLog.setTextSize(11.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setVerticalScrollBarEnabled(true);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setMinLines(5);
        tvLog.setMaxLines(12);
        tvLog.setLineSpacing(dp(4), 1f);
        card.addView(tvLog);
        return card;
    }

    // ── Helpers UI ────────────────────────────────────────────────────────────

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COL_BORDER);
        c.setBackground(bg);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private TextView stepLabel(String number, String title) {
        TextView tv = new TextView(this);
        tv.setText(number + ".  " + title);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(15f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView actionButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(COL_WHITE);
        btn.setTextSize(14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private GradientDrawable roundRect(int radius, int fill, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
