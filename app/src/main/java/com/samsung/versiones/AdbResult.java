package com.samsung.versiones;

public class AdbResult {
    public final boolean success;
    public final String output;

    public AdbResult(boolean success, String output) {
        this.success = success;
        this.output = output;
    }
}
