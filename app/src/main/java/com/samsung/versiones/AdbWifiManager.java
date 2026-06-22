package com.samsung.versiones;

import android.content.Context;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AdbWifiManager {

    private final Context context;
    private final File adbFile;

    public AdbWifiManager(Context context) {
        this.context = context.getApplicationContext();
        // nativeLibraryDir es ejecutable y está en el namespace de linker permitido
        this.adbFile = new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so");
    }

    public void extractAdbBinary() {
        // Todas las librerías van en jniLibs → el package manager las extrae a nativeLibraryDir
    }

    /** Emparejar vía ADB TLS Pairing (ADB ≥ 31).
     *  El código se pasa SOLO como argumento CLI; enviarlo también por stdin
     *  causa 'protocol fault' en versiones recientes del daemon. */
    public AdbResult pair(String ip, int port, String code) {
        AdbResult result = runAdb(null, "pair", ip + ":" + port, code);
        boolean success = result.output.contains("Successfully paired");
        return new AdbResult(success, result.output);
    }

    public AdbResult connect(String ip, int port) {
        AdbResult result = runAdb(null, "connect", ip + ":" + port);
        boolean success = result.output.contains("connected to")
                || result.output.contains("already connected");
        return new AdbResult(success, result.output);
    }

    public AdbResult install(String apkPath) {
        AdbResult result = runAdb(null, "install", "-r", apkPath);
        boolean success = result.output.contains("Success");
        return new AdbResult(success, result.output);
    }

    public AdbResult launchApp(String packageName) {
        AdbResult result = runAdb(null, "shell", "monkey", "-p", packageName,
                "-c", "android.intent.category.LAUNCHER", "1");
        boolean success = result.output.contains("Events injected: 1");
        return new AdbResult(success, result.output);
    }

    public AdbResult disconnect(String ip, int port) {
        return runAdb(null, "disconnect", ip + ":" + port);
    }

    /** Detiene el servidor ADB en background (puerto 5037). Llamar en onDestroy. */
    public AdbResult killServer() {
        return runAdb(null, "kill-server");
    }

    private AdbResult runAdb(String stdinInput, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbFile.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            // ADB necesita HOME para leer/escribir las claves RSA en ~/.android/adbkey.
            String homeDir = context.getFilesDir().getAbsolutePath();
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            pb.environment().put("HOME", homeDir);
            pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            pb.environment().put("ANDROID_SDK_HOME", homeDir);
            pb.environment().put("LD_LIBRARY_PATH", nativeDir);

            Process process = pb.start();

            if (stdinInput != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write((stdinInput + "\n").getBytes());
                    os.flush();
                }
            }

            byte[] bytes = process.getInputStream().readAllBytes();
            String output = new String(bytes).trim();
            process.waitFor(60, TimeUnit.SECONDS);
            return new AdbResult(true, output);
        } catch (Exception e) {
            return new AdbResult(false, "Error ejecutando adb: " + e.getMessage());
        }
    }
}
