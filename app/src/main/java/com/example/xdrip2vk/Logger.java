package com.example.xdrip2vk;

import android.content.Context;
import android.util.Log;

public class Logger {

    public static void init(Context context) {
        // Ничего не делаем, логи только в Logcat
    }

    public static void d(String tag, String message) {
        Log.d(tag, message);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
    }

    public static void clear() {
        // Ничего не делаем
    }
}
