package com.example.livetranscription.util;

public class BackendUtils {
    private BackendUtils() {}

    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
