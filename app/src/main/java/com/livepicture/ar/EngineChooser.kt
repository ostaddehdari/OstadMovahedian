package com.livepicture.ar

import android.content.Context

/**
 * انتخاب موتور: اولویت با ARCore، در غیر این صورت OpenCV.
 * اگر ARCore در اجرای قبلی کرش کرده باشد، برای همیشه OpenCV انتخاب می‌شود.
 */
object EngineChooser {

    private const val PREFS = "engine"
    private const val KEY_AR_RUNNING = "ar_running"
    private const val KEY_AR_BROKEN = "ar_broken"

    fun markArRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AR_RUNNING, running).commit()
    }

    /** true اگر ARCore قبلاً روی این گوشی کرش کرده است. */
    fun isArBroken(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AR_RUNNING, false)) {
            // اجرای قبلی بدون onPause تمام شد = کرش
            prefs.edit().putBoolean(KEY_AR_BROKEN, true).putBoolean(KEY_AR_RUNNING, false).commit()
        }
        return prefs.getBoolean(KEY_AR_BROKEN, false)
    }
}
