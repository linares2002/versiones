package com.samsung.versiones;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AdbWifiManager {

    private final Context context;
    private final File adbFile;

    public AdbWifiManager(Context context) {
        this.context = context;
        this.adbFile = new File(context.getFilesDir(), "adb");
    }

    // ─── Extrae el binario adb desde assets/ ──────────────────────────────────
    // Coloca en app/src/main/assets/adb el binario ARM64 para tu móvil
    // (ver README para obtenerlo con Termux o desde platform-tools)
    public void extractAdbBinary() {
        if (adbFile.exists()) return;
        try (InputStream in = context.getAssets().open("adb");
             FileOutputStream out = new FileOutputStream(adbFile)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            adbFile.setExecutable(true);
        } catch (IOException e) {
            // El binario adb no está en assets — ver README
        }
    }

    // ─── adb pair IP:PUERTO CODIGO ────────────────────────────────────────────
    public AdbResult pair(String ip, int port, String code) {
        AdbResult result = runAdb("pair", ip + ":" + port, code);
        boolean success = result.output.contains("Successfully paired");
        return new AdbResult(success, result.output);
    }

    // ─── adb connect IP:PUERTO ────────────────────────────────────────────────
    public AdbResult connect(String ip, int port) {
        AdbResult result = runAdb("connect", ip + ":" + port);
        boolean success = result.output.contains("connected to")
                || result.output.contains("already connected");
        return new AdbResult(success, result.output);
    }

    // ─── adb install -r APK ───────────────────────────────────────────────────
    public AdbResult install(String apkPath) {
        AdbResult result = runAdb("install", "-r", apkPath);
        boolean success = result.output.contains("Success");
        return new AdbResult(success, result.output);
    }

    // ─── adb disconnect ───────────────────────────────────────────────────────
    public AdbResult disconnect(String ip, int port) {
        return runAdb("disconnect", ip + ":" + port);
    }

    // ─── Ejecutor genérico ────────────────────────────────────────────────────
    private AdbResult runAdb(String... args) {
        String adbPath = adbFile.exists() ? adbFile.getAbsolutePath() : "adb";

        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Leer salida completa
            byte[] bytes = process.getInputStream().readAllBytes();
            String output = new String(bytes).trim();
            process.waitFor();

            return new AdbResult(true, output);
        } catch (Exception e) {
            return new AdbResult(false, "Error ejecutando adb: " + e.getMessage());
        }
    }
}
