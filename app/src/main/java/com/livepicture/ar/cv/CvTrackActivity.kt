package com.livepicture.ar.cv

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.livepicture.ar.ArTarget
import com.livepicture.ar.BundledTarget
import com.livepicture.ar.R
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * صفحهٔ اصلی «قاب من» (بدون نیاز به ARCore / Google AR):
 * CameraX تصویر دوربین را می‌دهد، PlanarTracker (OpenCV) عکس را پیدا و ردیابی می‌کند،
 * و ویدیو در یک TextureView با تبدیل پرسپکتیو دقیقاً روی چهار گوشهٔ عکس قرار می‌گیرد.
 */
class CvTrackActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var videoView: TextureView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var muteButton: MaterialButton

    private var controller: LifecycleCameraController? = null
    private var analysisExecutor: ExecutorService? = null

    // --- فقط روی ترد آنالیز ---
    private var tracker: PlanarTracker? = null
    private var trackerFailed = false
    private var frameBytes = ByteArray(0)
    private var fullGray: Mat? = null
    private val scaledTmp by lazy { Mat() }
    private val detectGray by lazy { Mat() }
    private val smallGray by lazy { Mat() }
    private var frameCount = 0

    // ابعاد نمای دوربین روی صفحه (از ترد اصلی نوشته می‌شود)
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    // --- فقط روی ترد اصلی ---
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var currentTarget: ArTarget? = null
    private var prepared = false
    private var wantPlay = false
    private var muted = false
    private var lastSeenMs = 0L
    private var smoothed: FloatArray? = null
    private val viewTransform = Matrix()
    private var lastStatus: String? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        setContentView(R.layout.activity_cv)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        videoView = findViewById(R.id.videoView)
        statusText = findViewById(R.id.statusText)
        debugText = findViewById(R.id.debugText)
        muteButton = findViewById(R.id.btnMute)
        videoView.alpha = 0f
        videoView.isOpaque = false

        // اگر دفعهٔ قبل برنامه بسته شد، علت را نشان بده (برای عیب‌یابی)
        // خط عیب‌یابی پنهان است؛ با نگه‌داشتن انگشت روی صفحه نمایش داده می‌شود
        debugText.visibility = View.GONE
        previewView.setOnLongClickListener {
            debugText.visibility = if (debugText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        val crash = crashFile()
        if (crash.exists()) {
            showError("خطای اجرای قبل:\n" + crash.readText().take(600))
            crash.delete()
        }

        previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewW = v.width
            viewH = v.height
        }

        muteButton.setOnClickListener {
            muted = !muted
            muteButton.setText(if (muted) R.string.unmute else R.string.mute)
            applyVolume()
        }

        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                player?.setSurface(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        setStatus(getString(R.string.status_loading))

        val cvOk = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV load failed", t)
            false
        }
        if (!cvOk) {
            setStatus(getString(R.string.opencv_failed))
            return
        }

        // ویدیو را از همان اول آماده کن تا لحظهٔ دیدن عکس، بی‌درنگ پخش شود
        try {
            switchVideo(BundledTarget.get(this))
        } catch (t: Throwable) {
            Log.e(TAG, "video init failed", t)
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val executor = analysisExecutor ?: return
        val c = LifecycleCameraController(this)
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        // پیش‌نمایش و آنالیز هر دو ۱۶:۹ تا میدان دید یکسان باشد و ویدیو دقیق روی عکس بنشیند
        c.previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        c.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        c.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        c.setImageAnalysisAnalyzer(executor) { image ->
            try {
                analyzeFrame(image)
            } catch (t: Throwable) {
                Log.e(TAG, "analyze failed", t)
                runOnUiThread { showError("analyze: ${t.javaClass.simpleName}: ${t.message}") }
            } finally {
                image.close()
            }
        }
        previewView.controller = c
        c.bindToLifecycle(this)
        controller = c
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (trackerFailed) return
        val t = tracker ?: try {
            PlanarTracker(listOf(BundledTarget.get(this))).also {
                tracker = it
                if (!it.hasTargets) runOnUiThread { setStatus(getString(R.string.status_skipped, "")) }
            }
        } catch (e: Throwable) {
            trackerFailed = true
            Log.e(TAG, "tracker init failed", e)
            runOnUiThread { showError("tracker: ${e.javaClass.simpleName}: ${e.message}") }
            return
        }

        val vw = viewW
        val vh = viewH
        if (vw <= 0 || vh <= 0) return

        // ۱) صفحهٔ Y (روشنایی) = تصویر خاکستری
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        if (frameBytes.size != w * h) frameBytes = ByteArray(w * h)
        if (rowStride == w) {
            buf.rewind(); buf.get(frameBytes, 0, w * h)
        } else {
            for (r in 0 until h) {
                buf.position(r * rowStride)
                buf.get(frameBytes, r * w, min(w, buf.remaining()))
            }
        }
        val full = fullGray?.takeIf { it.rows() == h && it.cols() == w }
            ?: Mat(h, w, CvType.CV_8UC1).also { fullGray?.release(); fullGray = it }
        full.put(0, 0, frameBytes)

        // ۲) اول کوچک‌سازی (ارزان‌تر)، بعد چرخاندن به جهت صفحه
        val rotation = image.imageInfo.rotationDegrees
        val uw = (if (rotation == 90 || rotation == 270) h else w).toDouble()
        val uh = (if (rotation == 90 || rotation == 270) w else h).toDouble()
        val scale = min(1.0, TRACK_MAX_DIM / max(uw, uh))
        val detectScale = min(1.0, DETECT_MAX_DIM / max(uw, uh))
        downscaleUpright(full, scale, rotation, smallGray)

        // فریم بزرگ‌تر فقط وقتی جستجوی کامل لازم است ساخته می‌شود
        val result = t.process(smallGray) {
            downscaleUpright(full, detectScale, rotation, detectGray)
            detectGray to (scale / detectScale)
        }
        frameCount++
        if (frameCount % 10 == 0) {
            val dbg = "frames=$frameCount  matches=${t.lastMatches}  img=${w}x$h rot=${image.imageInfo.rotationDegrees}"
            runOnUiThread { if (!hasError) debugText.text = dbg }
        }

        if (result == null) {
            runOnUiThread { onTrack(null, null) }
            return
        }

        // ۴) مختصات تصویر کوچک → تصویر ایستاده → نمای صفحه (PreviewView در حالت FILL_CENTER)
        val fill = max(vw / uw, vh / uh)
        val ox = (vw - uw * fill) / 2
        val oy = (vh - uh * fill) / 2
        val pts = FloatArray(8)
        for (i in 0 until 4) {
            pts[i * 2] = (result.corners[i].x / scale * fill + ox).toFloat()
            pts[i * 2 + 1] = (result.corners[i].y / scale * fill + oy).toFloat()
        }
        val target = result.target
        runOnUiThread { onTrack(target, pts) }
    }

    private fun downscaleUpright(src: Mat, s: Double, rotation: Int, dst: Mat) {
        Imgproc.resize(src, scaledTmp, org.opencv.core.Size(), s, s, Imgproc.INTER_AREA)
        when (rotation) {
            90 -> Core.rotate(scaledTmp, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(scaledTmp, dst, Core.ROTATE_180)
            270 -> Core.rotate(scaledTmp, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> scaledTmp.copyTo(dst)
        }
    }

    // ----------------------------------------------------------- ترد اصلی

    private fun onTrack(target: ArTarget?, corners: FloatArray?) {
        val now = SystemClock.uptimeMillis()
        if (target != null && corners != null) {
            val recentlySeen = now - lastSeenMs < LOST_GRACE_MS
            lastSeenMs = now
            if (currentTarget?.id != target.id) {
                switchVideo(target)
                smoothed = null
            }
            val s = smoothed
            smoothed = if (s == null || !recentlySeen) corners.copyOf()
            else {
                // هموارسازی فقط برای لرزش‌های ریز؛ در حرکت واقعی بدون تأخیر دنبال کن
                var moved = 0f
                for (i in 0 until 4) {
                    val dx = corners[i * 2] - s[i * 2]
                    val dy = corners[i * 2 + 1] - s[i * 2 + 1]
                    moved = max(moved, kotlin.math.sqrt(dx * dx + dy * dy))
                }
                val a = if (moved > JITTER_PX) 1f else SMOOTHING
                FloatArray(8) { i -> s[i] + a * (corners[i] - s[i]) }
            }

            applyCorners(smoothed!!)
            videoView.alpha = min(1f, videoView.alpha + 0.25f)
            play()
            setStatus("")
        } else if (now - lastSeenMs > LOST_GRACE_MS) {
            videoView.alpha = 0f
            pause()
            setStatus(getString(R.string.status_point))
        }
    }

    private fun applyCorners(c: FloatArray) {
        val w = videoView.width.toFloat()
        val h = videoView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        if (viewTransform.setPolyToPoly(src, 0, c, 0, 4)) {
            videoView.setTransform(viewTransform)
            videoView.invalidate()
        }
    }

    private fun switchVideo(target: ArTarget) {
        player?.release()
        prepared = false
        currentTarget = target
        player = MediaPlayer().apply {
            try {
                setDataSource(target.videoFile.absolutePath)
                surface?.let { setSurface(it) }
                isLooping = true
                setOnPreparedListener {
                    prepared = true
                    applyVolume()
                    if (wantPlay) it.start()
                }
                setOnErrorListener { _, what, extra ->
                    showError("video error $what/$extra")
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "video failed", e)
                showError("video: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun play() {
        wantPlay = true
        val p = player ?: return
        if (prepared && !p.isPlaying) p.start()
    }

    private fun pause() {
        wantPlay = false
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
    }

    private fun applyVolume() {
        val v = if (muted) 0f else 1f
        if (prepared) player?.setVolume(v, v)
    }

    private var hasError = false

    private fun showError(msg: String) {
        hasError = true
        debugText.text = msg
        debugText.visibility = View.VISIBLE
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        statusText.text = text
        statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ----------------------------------------------------------- عیب‌یابی

    private fun crashFile() = File(filesDir, "last_crash.txt")

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(crashFile(), previous))
    }

    private class CrashLogger(
        private val file: File,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                file.writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    override fun onPause() {
        super.onPause()
        pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        analysisExecutor?.let { ex ->
            ex.execute {
                tracker?.release()
                fullGray?.release()
            }
            ex.shutdown()
        }
    }

    companion object {
        private const val TAG = "QabeMan"
        private const val TRACK_MAX_DIM = 640.0    // ردیابی سریع فریم‌به‌فریم
        private const val DETECT_MAX_DIM = 960.0   // جستجوی کامل (فقط گاهی)
        private const val JITTER_PX = 12f
        private const val LOST_GRACE_MS = 400L
        private const val SMOOTHING = 0.5f
    }
}
