package com.livepicture.ar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.ar.core.ArCoreApk
import com.livepicture.ar.cv.CvTrackActivity

/**
 * نقطهٔ شروع بدون رابط کاربری: بلافاصله دوربین را باز می‌کند.
 * اولویت با ARCore است؛ اگر گوشی ARCore را پشتیبانی نکند، نصب نباشد، یا قبلاً کرش کرده باشد → OpenCV.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decide()
    }

    private fun decide() {
        if (EngineChooser.isArBroken(this)) {
            open(useAr = false)
            return
        }
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(this)
        } catch (t: Throwable) {
            null
        }
        when {
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> open(useAr = true)
            // اولین بررسی گاهی «در حال بررسی» برمی‌گرداند؛ کمی صبر کن (حداکثر ۲ ثانیه)
            availability != null && availability.isTransient && attempts < 10 -> {
                attempts++
                handler.postDelayed({ decide() }, 200)
            }
            else -> open(useAr = false)
        }
    }

    private fun open(useAr: Boolean) {
        val screen = if (useAr) ArActivity::class.java else CvTrackActivity::class.java
        startActivity(Intent(this, screen))
        overridePendingTransition(0, 0)
        finish()
    }
}
