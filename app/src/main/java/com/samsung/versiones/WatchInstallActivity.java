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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WatchInstallActivity extends AppCompatActivity {

    public static final String EXTRA_APK_PATH = "apk_path";

    // ── Paleta (coherente con MainActivity UJA) ───────────────────────────────
    private static final int COL_BG        = Color.parseColor("#F5F5F0");
    private static final int COL_WHITE     = Color.parseColor("#FFFFFF");
    private static final int COL_TEXT      = Color.parseColor("#1C2019");
    private static final int COL_TEXT_DIM  = Color.parseColor("#8A9180");
    private static final int COL_GREEN     = Color.parseColor("#3D5A34");
    private static final int COL_BORDER    = Color.parseColor("#E2E5DC");
    private static final int COL_BLUE      = Color.parseColor("#1A4FA3");
    private static final int COL_BLUE_BG   = Color.parseColor("#EBF0FB");
    private static final int COL_ERROR     = Color.parseColor("#C04040");
    private static final int COL_ERROR_BG  = Color.parseColor("#FAF0F0");
    private static final int COL_ORANGE    = Color.parseColor("#B06820");

    // ── Estado ────────────────────────────────────────────────────────────────
    private String apkPath;
    private String discoveredIp;
    private int    discoveredPairPort;
    private int    discoveredConnectPort;
    private AppState state = AppState.IDLE;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;

    private AdbWifiManager adbManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Views ────────────────────────────────────────────────────────────────
    private LinearLayout contentFrame;
    private TextView     tvStatus;
    private ProgressBar  progressBar;

    // Sección código
    private LinearLayout cardCode;
    private EditText     etCode;

    // Sección manual
    private LinearLayout cardManual;
    private EditText     etManualIp;
    private EditText     etManualPort;
    private EditText     etManualCode;

    // Sección conectado
    private LinearLayout cardConnected;
    private TextView     tvConnectedDevice;
    private TextView     tvApkName;
    private TextView     btnInstall;

    // Log
    private TextView tvLog;

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        apkPath    = getIntent().getStringExtra(EXTRA_APK_PATH);
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        adbManager = new AdbWifiManager(this);

        setContentView(buildLayout());
        setState(AppState.IDLE);

        // Extraer binario adb en background
        executor.execute(() -> adbManager.extractAdbBinary());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        executor.shutdownNow();
    }

    // ── mDNS Discovery ────────────────────────────────────────────────────────

    private void startMdnsDiscovery() {
        setState(AppState.DISCOVERING);
        log("🔍 Buscando reloj en la red WiFi...");
        log("   Activa «Emparejar nuevo dispositivo» en el reloj");

        // Escuchar puerto de emparejamiento
        pairingListener = createDiscoveryListener("_adb-tls-pairing._tcp.", info -> {
            String ip   = info.getHost() != null ? info.getHost().getHostAddress() : null;
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

        // Escuchar puerto de conexión (diferente al de emparejamiento)
        connectListener = createDiscoveryListener("_adb-tls-connect._tcp.", info -> {
            int port = info.getPort();
            mainHandler.post(() -> {
                discoveredConnectPort = port;
                log("📡 Puerto de conexión: " + port);
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
                mainHandler.post(() -> log("⚠️ Error al iniciar mDNS: " + e));
            }
            @Override public void onStopDiscoveryFailed(String s, int e) {
                mainHandler.post(() -> log("⚠️ Error al detener mDNS: " + e));
            }
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
                    log("⚡ Conectando automáticamente...");
                    stopDiscovery();
                    autoConnect(ip);
                } else {
                    log("❌ Error: " + result.output);
                    setState(AppState.WAITING_CODE);
                }
            });
        });
    }

    // ── Auto-connect (igual que Android Studio) ───────────────────────────────

    private void autoConnect(String ip) {
        executor.execute(() -> {
            int connectPort = discoveredConnectPort;

            // Si no tenemos el puerto de conexión aún, esperar hasta 6 seg a que mDNS lo anuncie
            if (connectPort <= 0) {
                mainHandler.post(() -> log("🔍 Esperando puerto de conexión por mDNS..."));
                CountDownLatch latch = new CountDownLatch(1);

                // Relanzar listener de conexión
                NsdManager.DiscoveryListener tmpListener = createDiscoveryListener(
                        "_adb-tls-connect._tcp.", info -> {
                            if (ip.equals(info.getHost() != null
                                    ? info.getHost().getHostAddress() : "")) {
                                discoveredConnectPort = info.getPort();
                                latch.countDown();
                            }
                        });
                mainHandler.post(() ->
                        nsdManager.discoverServices("_adb-tls-connect._tcp.",
                                NsdManager.PROTOCOL_DNS_SD, tmpListener));

                try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

                try { nsdManager.stopServiceDiscovery(tmpListener); } catch (Exception ignored) {}
                connectPort = discoveredConnectPort;
            }

            if (connectPort <= 0) {
                mainHandler.post(() -> {
                    log("⚠️ No se detectó el puerto de conexión automáticamente");
                    log("💡 Introduce la IP y puerto que muestra el reloj en «Depuración inalámbrica»");
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
                    log("❌ Error conexión: " + result.output);
                    setState(AppState.ERROR);
                }
            });
        });
    }

    // ── Instalación ───────────────────────────────────────────────────────────

    private void installApk() {
        if (apkPath == null) return;
        setState(AppState.INSTALLING);
        log("📲 Instalando " + new File(apkPath).getName() + " en el reloj...");
        executor.execute(() -> {
            AdbResult result = adbManager.install(apkPath);
            mainHandler.post(() -> {
                if (result.success) {
                    log("🎉 ¡Instalación completada con éxito!");
                    setState(AppState.DONE);
                } else {
                    log("❌ Error instalación: " + result.output);
                    setState(AppState.CONNECTED);
                    btnInstall.setAlpha(1f);
                    btnInstall.setEnabled(true);
                }
            });
        });
    }

    // ── Estado visual ─────────────────────────────────────────────────────────

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
        // Auto-scroll
        if (tvLog.getLayout() != null) {
            int scroll = tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        }
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);

        // TopBar
        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);

        contentFrame = new LinearLayout(this);
        contentFrame.setOrientation(LinearLayout.VERTICAL);
        contentFrame.setPadding(dp(20), dp(20), dp(20), dp(32));

        // Status
        tvStatus = new TextView(this);
        tvStatus.setTextSize(13f);
        tvStatus.setPadding(0, 0, 0, dp(4));
        contentFrame.addView(tvStatus);

        // ProgressBar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.topMargin = dp(6);
        pbLp.bottomMargin = dp(16);
        progressBar.setLayoutParams(pbLp);
        contentFrame.addView(progressBar);

        // Card 1: Buscar reloj
        contentFrame.addView(buildStepCard());
        contentFrame.addView(spacer(dp(14)));

        // Card 2: Código (oculta inicialmente)
        cardCode = buildCodeCard();
        cardCode.setVisibility(View.GONE);
        contentFrame.addView(cardCode);
        contentFrame.addView(spacer(dp(14)));

        // Card manual (desplegable)
        cardManual = buildManualCard();
        cardManual.setVisibility(View.GONE);
        contentFrame.addView(cardManual);
        contentFrame.addView(spacer(dp(14)));

        // Card 3: Conectado + instalar (oculta inicialmente)
        cardConnected = buildInstallCard();
        cardConnected.setVisibility(View.GONE);
        contentFrame.addView(cardConnected);
        contentFrame.addView(spacer(dp(14)));

        // Log
        contentFrame.addView(buildLogCard());

        scroll.addView(contentFrame);
        root.addView(scroll);
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COL_WHITE);
        bar.setPadding(dp(20), dp(48), dp(20), dp(16));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(COL_WHITE);
        barBg.setStroke(dp(1), COL_BORDER);
        bar.setBackground(barBg);

        // Botón atrás
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
        sub.setText("Samsung Galaxy Watch 5 · ADB WiFi");
        sub.setTextColor(COL_TEXT_DIM);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);

        bar.addView(col);
        return bar;
    }

    private LinearLayout buildStepCard() {
        LinearLayout card = card();

        TextView label = stepLabel("1", "Buscar reloj");
        card.addView(label);
        card.addView(spacer(dp(6)));

        TextView desc = new TextView(this);
        desc.setText("Asegúrate de que el reloj y este móvil estén en la misma red WiFi.\n\nEn el reloj: Ajustes → Opciones de desarrollador → Depuración inalámbrica → Emparejar nuevo dispositivo");
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
        desc.setText("Introduce el código de 6 dígitos que muestra la pantalla del reloj:");
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
        etManualIp.setLayoutParams(ipLp);
        row.addView(etManualIp);

        row.addView(spacer(dp(8)));

        etManualPort = new EditText(this);
        etManualPort.setHint("Puerto");
        etManualPort.setTextSize(13f);
        etManualPort.setTextColor(COL_TEXT);
        etManualPort.setHintTextColor(COL_TEXT_DIM);
        etManualPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etManualPort.setBackground(roundRect(dp(10), COL_WHITE, COL_BORDER));
        etManualPort.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etManualPort.setLayoutParams(portLp);
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
            String ip   = etManualIp.getText().toString().trim();
            String portStr = etManualPort.getText().toString().trim();
            String code = etManualCode.getText().toString().trim();
            int port = portStr.isEmpty() ? 0 : Integer.parseInt(portStr);
            if (ip.isEmpty() || port <= 0 || code.length() != 6) {
                Toast.makeText(this, "Rellena todos los campos", Toast.LENGTH_SHORT).show();
                return;
            }
            discoveredIp       = ip;
            discoveredPairPort = port;
            performPairing(ip, port, code);
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

        // Mostrar nombre APK que viene de MainActivity
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

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView logLabel = new TextView(this);
        logLabel.setText("Registro");
        logLabel.setTextColor(COL_TEXT_DIM);
        logLabel.setTextSize(12f);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        logLabel.setLayoutParams(llLp);
        headerRow.addView(logLabel);

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Limpiar");
        clearBtn.setTextColor(COL_TEXT_DIM);
        clearBtn.setTextSize(11f);
        clearBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearBtn.setOnClickListener(v -> tvLog.setText(""));
        headerRow.addView(clearBtn);
        card.addView(headerRow);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView stepLabel(String number, String title) {
        // Devolvemos solo el título con número prefijado para simplificar
        // (se puede hacer un row si se prefiere)
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
