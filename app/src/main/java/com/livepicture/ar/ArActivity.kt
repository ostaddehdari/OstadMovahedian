package com.livepicture.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.livepicture.ar.cv.CvTrackActivity
import com.livepicture.ar.rendering.BackgroundRenderer
import com.livepicture.ar.rendering.VideoQuadRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * موتور ARCore (Augmented Images) — برای ردیابی پایدار و دقیق روی گوشی‌های پشتیبانی‌شده.
 *
 * بهینه‌سازی‌ها برای ردیابی عالی:
 *  ۱. دوربین با بیشترین وضوح تصویر پردازشی (تا Full HD) → تشخیص از فاصلهٔ دورتر و زاویهٔ تندتر
 *  ۲. خاموش کردن قابلیت‌های بی‌استفاده (سطح‌یابی، نور، عمق) → پردازنده فقط صرف ردیابی عکس می‌شود
 *  ۳. عرض واقعی چاپ (اختیاری، assets/print_width_cm.txt) → تشخیص سریع‌تر و اندازهٔ دقیق
 *  ۴. پایگاه تصویر یک بار ساخته و ذخیره می‌شود → شروع سریع در دفعات بعد
 *  ۵. فیلتر ضدلرزش تطبیقی روی موقعیت عکس → فیلم کاملاً ساکن وقتی گوشی ثابت است، بدون تأخیر هنگام حرکت
 *  ۶. وقتی عکس لحظه‌ای از دید ARCore خارج شود (لبهٔ کادر، بازتاب نور، زاویهٔ تند) فیلم با موقعیت
 *     ثبت‌شده در فضا روی عکس می‌ماند و قطع و وصل نمی‌شود
 */
class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var muteButton: MaterialButton
    private lateinit var displayRotationHelper: DisplayRotationHelper

    private var session: Session? = null
    private var permissionDenied = false

    private val background = BackgroundRenderer()
    private val videoRenderer = VideoQuadRenderer()
    private var cameraTextureSet = false

    /** ایندکس تصویر در پایگاه ARCore → قاب */
    @Volatile private var indexToTarget: Map<Int, ArTarget> = emptyMap()
    @Volatile private var targetsLoaded = false
    private val pendingConfig = AtomicReference<Config?>(null)

    /** پخش‌کننده‌های فعال؛ کلید = ایندکس تصویر */
    private val players = ConcurrentHashMap<Int, VideoPlayback>()
    /** وضعیت فیلتر و دیده‌شدن هر عکس (فقط روی ترد GL) */
    private val imageStates = HashMap<Int, ImageState>()
    @Volatile private var muted = false

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tmpIn = FloatArray(4)
    private val tmpOut = FloatArray(4)
    private var lastStatus: String? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            permissionDenied = true
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.statusText)
        muteButton = findViewById(R.id.btnMute)
        displayRotationHelper = DisplayRotationHelper(this)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        muteButton.setOnClickListener {
            muted = !muted
            muteButton.setText(if (muted) R.string.unmute else R.string.mute)
            players.values.forEach { it.setMuted(muted) }
        }
        setStatus(getString(R.string.status_loading))
    }

    override fun onResume() {
        super.onResume()
        if (permissionDenied) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
            return
        }

        if (session == null) {
            try {
                // هرگز کاربر را به نصب/به‌روزرسانی Google AR نفرست؛ اگر آماده نیست، موتور OpenCV
                if (ArCoreApk.getInstance().checkAvailability(this) != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                    throw UnavailableDeviceNotCompatibleException()
                }
                val s = Session(this)
                chooseBestCameraConfig(s)
                s.configure(baseConfig(s))
                session = s
                loadTargets(s)
            } catch (e: Throwable) {
                fallbackToCv("init", e)
                return
            }
        }

        try {
            session?.resume()
        } catch (e: Throwable) {
            fallbackToCv("resume", e)
            return
        }
        surfaceView.onResume()
        displayRotationHelper.onResume()
        // اگر ARCore در حین اجرا (کد بومی) کرش کند، این علامت باقی می‌ماند و دفعهٔ بعد مستقیم OpenCV اجرا می‌شود
        EngineChooser.markArRunning(this, true)
    }

    override fun onPause() {
        super.onPause()
        EngineChooser.markArRunning(this, false)
        session?.let {
            displayRotationHelper.onPause()
            surfaceView.onPause()   // تا توقف کامل ترد GL صبر می‌کند
            it.pause()
        }
        players.values.forEach { it.pause() }
    }

    override fun onDestroy() {
        players.values.forEach { it.release(deleteTexture = false) }
        players.clear()
        session?.close()
        session = null
        super.onDestroy()
    }

    private var fellBack = false

    /** هر مشکلی در ARCore → رفتن به موتور OpenCV. */
    private fun fallbackToCv(reason: String, e: Throwable?) {
        if (fellBack) return
        fellBack = true
        Log.w(TAG, "ARCore -> OpenCV ($reason)", e)
        startActivity(Intent(this, CvTrackActivity::class.java))
        finish()
    }

    /**
     * بیشترین وضوح تصویر پردازشی (CPU image) با ۳۰ فریم در ثانیه، بدون حسگر عمق.
     * پیش‌فرض ARCore کمترین وضوح (۶۴۰×۴۸۰) است که عکس را فقط از نزدیک پیدا می‌کند.
     * سقف Full HD تا گوشی‌های ضعیف‌تر کند نشوند.
     */
    private fun chooseBestCameraConfig(s: Session) {
        try {
            val filter = CameraConfigFilter(s)
            filter.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            filter.setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
            val configs = s.getSupportedCameraConfigs(filter)
            val best = configs
                .filter { it.imageSize.width <= 1920 && it.imageSize.height <= 1920 }
                .maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                ?: return
            s.cameraConfig = best
            Log.i(TAG, "Camera config: CPU ${best.imageSize}, GPU ${best.textureSize}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not choose camera config, using default", t)
        }
    }

    /** فقط ردیابی عکس؛ هر چیز دیگری خاموش تا پردازنده آزاد بماند. */
    private fun baseConfig(s: Session) = Config(s).apply {
        focusMode = Config.FocusMode.AUTO
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        planeFindingMode = Config.PlaneFindingMode.DISABLED
        lightEstimationMode = Config.LightEstimationMode.DISABLED
        depthMode = Config.DepthMode.DISABLED
        instantPlacementMode = Config.InstantPlacementMode.DISABLED
        cloudAnchorMode = Config.CloudAnchorMode.DISABLED
    }

    /**
     * ساخت پایگاه تصاویر ARCore در پس‌زمینه. بار اول ساخته و در حافظه ذخیره می‌شود؛
     * دفعات بعد مستقیم خوانده می‌شود (شروع تقریباً فوری).
     */
    private fun loadTargets(s: Session) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val target = BundledTarget.get(this@ArActivity)
                    val cacheFile = File(target.dir, "arcore_${target.imageFile.lastModified()}_${target.widthMeters}.imgdb")

                    val cached = if (cacheFile.exists()) {
                        try {
                            cacheFile.inputStream().use { AugmentedImageDatabase.deserialize(s, it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Cached image database invalid, rebuilding", e)
                            null
                        }
                    } else null

                    val db = cached ?: buildDatabase(s, target).also { db ->
                        target.dir.listFiles { f -> f.name.endsWith(".imgdb") }?.forEach { it.delete() }
                        try {
                            cacheFile.outputStream().use { db.serialize(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not cache image database", e)
                        }
                    }
                    if (db.numImages == 0) null
                    else mapOf(0 to target) to baseConfig(s).apply { augmentedImageDatabase = db }
                }
            } catch (e: Throwable) {
                fallbackToCv("database", e)
                return@launch
            }
            if (result == null) {
                // ARCore این عکس را قبول نکرد (جزئیات کم) → OpenCV امتحان کند
                fallbackToCv("image rejected", null)
                return@launch
            }
            indexToTarget = result.first
            pendingConfig.set(result.second)   // روی ترد GL اعمال می‌شود
            targetsLoaded = true
        }
    }

    private fun buildDatabase(s: Session, t: ArTarget): AugmentedImageDatabase {
        val db = AugmentedImageDatabase(s)
        val bmp = BitmapFactory.decodeFile(t.imageFile.absolutePath) ?: return db
        try {
            if (t.widthMeters > 0f) db.addImage(t.id, bmp, t.widthMeters) else db.addImage(t.id, bmp)
        } catch (e: ImageInsufficientQualityException) {
            Log.w(TAG, "ARCore: image has too few features", e)
        } finally {
            bmp.recycle()
        }
        return db
    }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // زمینهٔ GL تازه است؛ بافت‌های قبلی دیگر معتبر نیستند
        players.values.forEach { it.release(deleteTexture = false) }
        players.clear()
        background.createOnGlThread()
        videoRenderer.createOnGlThread()
        cameraTextureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        pendingConfig.getAndSet(null)?.let { s.configure(it) }
        if (!cameraTextureSet) {
            s.setCameraTextureName(background.textureId)
            cameraTextureSet = true
        }
        displayRotationHelper.updateSessionIfNeeded(s)

        try {
            val frame = s.update()
            val camera = frame.camera
            background.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) {
                pauseAllExcept(emptySet())
                setStatus(getString(if (targetsLoaded) R.string.status_tracking_lost else R.string.status_loading))
                return
            }

            camera.getProjectionMatrix(projMatrix, 0, 0.02f, 100f)
            camera.getViewMatrix(viewMatrix, 0)
            Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)

            val visible = HashSet<Int>()
            val now = System.currentTimeMillis()

            for (image in s.getAllTrackables(AugmentedImage::class.java)) {
                if (image.trackingState != TrackingState.TRACKING) continue
                val target = indexToTarget[image.index] ?: continue
                val st = imageStates.getOrPut(image.index) { ImageState() }

                val full = image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                if (full) {
                    st.lastFullMs = now
                    st.update(image.centerPose, image.extentX, image.extentZ)
                } else if (!st.initialized || now - st.lastFullMs > HOLD_LAST_POSE_MS) {
                    // مدت زیادی دیده نشده؛ ممکن است عکس جابه‌جا شده باشد
                    continue
                }

                // فیلم فقط وقتی پخش می‌شود که دست‌کم بخشی از عکس روی صفحه باشد
                st.toMatrix(modelMatrix)
                if (!isOnScreen(modelMatrix, st.extentX, st.extentZ)) continue

                visible += image.index
                val playback = players.getOrPut(image.index) { VideoPlayback(target.videoFile, muted) }
                playback.lastSeenMs = now
                playback.play()

                if (playback.updateTexture()) {
                    playback.alpha = min(1f, playback.alpha + FADE_STEP)
                    videoRenderer.draw(
                        modelMatrix, viewMatrix, projMatrix,
                        st.extentX, st.extentZ,
                        playback.stMatrix, playback.textureId, playback.alpha
                    )
                }
            }

            pauseAllExcept(visible)
            releaseStalePlayers(now, visible)

            setStatus(
                when {
                    !targetsLoaded -> getString(R.string.status_loading)
                    visible.isNotEmpty() -> ""   // هنگام پخش، پیام پنهان می‌شود
                    else -> getString(R.string.status_point)
                }
            )
        } catch (e: CameraNotAvailableException) {
            setStatus(getString(R.string.camera_unavailable))
        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    /** آیا مرکز یا یکی از چهار گوشهٔ عکس داخل صفحه (با کمی حاشیه) دیده می‌شود؟ */
    private fun isOnScreen(model: FloatArray, ex: Float, ez: Float): Boolean {
        val hx = ex / 2f
        val hz = ez / 2f
        val pts = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(-hx, -hz), floatArrayOf(hx, -hz),
            floatArrayOf(hx, hz), floatArrayOf(-hx, hz),
        )
        for (p in pts) {
            tmpIn[0] = p[0]; tmpIn[1] = 0f; tmpIn[2] = p[1]; tmpIn[3] = 1f
            Matrix.multiplyMV(tmpOut, 0, model, 0, tmpIn, 0)
            System.arraycopy(tmpOut, 0, tmpIn, 0, 4)
            Matrix.multiplyMV(tmpOut, 0, viewProj, 0, tmpIn, 0)
            val w = tmpOut[3]
            if (w <= 0f) continue
            val x = tmpOut[0] / w
            val y = tmpOut[1] / w
            if (abs(x) <= SCREEN_MARGIN && abs(y) <= SCREEN_MARGIN) return true
        }
        return false
    }

    private fun pauseAllExcept(visible: Set<Int>) {
        for ((index, p) in players) {
            if (index !in visible) {
                p.pause()
                p.alpha = 0f
            }
        }
    }

    /** برای صرفه‌جویی حافظه، پخش‌کننده‌هایی که مدتی دیده نشده‌اند آزاد می‌شوند. */
    private fun releaseStalePlayers(now: Long, visible: Set<Int>) {
        val it = players.entries.iterator()
        while (it.hasNext()) {
            val (index, p) = it.next()
            val tooMany = players.size > MAX_PLAYERS
            if (index !in visible && (now - p.lastSeenMs > STALE_MS || tooMany)) {
                p.release(deleteTexture = true)
                it.remove()
            }
        }
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        runOnUiThread {
            statusText.text = text
            statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * فیلتر ضدلرزش تطبیقی (شبیه One-Euro):
     * تغییرات خیلی کوچک (لرزش تخمین) تقریباً حذف می‌شوند، ولی حرکت واقعی عکس فوراً دنبال می‌شود.
     * چون موقعیت عکس در مختصات «جهان» است، حرکت گوشی هیچ تأخیری ایجاد نمی‌کند.
     */
    private class ImageState {
        var initialized = false
        var lastFullMs = 0L
        private val t = FloatArray(3)
        private val q = FloatArray(4)   // x, y, z, w
        var extentX = 0f
        var extentZ = 0f

        fun update(pose: Pose, ex: Float, ez: Float) {
            val nt = pose.translation
            val nq = pose.rotationQuaternion
            if (!initialized) {
                System.arraycopy(nt, 0, t, 0, 3)
                System.arraycopy(nq, 0, q, 0, 4)
                extentX = ex; extentZ = ez
                initialized = true
                return
            }
            val dx = nt[0] - t[0]; val dy = nt[1] - t[1]; val dz = nt[2] - t[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)

            // هم‌جهت کردن کواترنیون‌ها (q و -q یک چرخش‌اند)
            var dot = q[0] * nq[0] + q[1] * nq[1] + q[2] * nq[2] + q[3] * nq[3]
            val sign = if (dot < 0f) -1f else 1f
            dot = abs(dot).coerceAtMost(1f)
            val angle = 2f * acos(dot)   // رادیان

            val a = when {
                dist > SNAP_DISTANCE_M || angle > SNAP_ANGLE_RAD -> 1f   // جابه‌جایی واقعی → فوراً
                else -> (MIN_ALPHA + dist * DIST_GAIN + angle * ANGLE_GAIN).coerceAtMost(1f)
            }
            for (i in 0 until 3) t[i] += a * (nt[i] - t[i])
            var n = 0f
            for (i in 0 until 4) {
                q[i] += a * (sign * nq[i] - q[i])
                n += q[i] * q[i]
            }
            n = sqrt(n)
            for (i in 0 until 4) q[i] /= n

            // اندازهٔ تخمینی عکس آرام‌تر تنظیم می‌شود
            extentX += EXTENT_ALPHA * (ex - extentX)
            extentZ += EXTENT_ALPHA * (ez - extentZ)
        }

        fun toMatrix(out: FloatArray) {
            Pose(t, q).toMatrix(out, 0)
        }

        companion object {
            private const val SNAP_DISTANCE_M = 0.05f
            private const val SNAP_ANGLE_RAD = 0.26f      // ≈۱۵ درجه
            private const val MIN_ALPHA = 0.12f
            private const val DIST_GAIN = 25f             // ۱ سانتی‌متر → +۰٫۲۵
            private const val ANGLE_GAIN = 4f             // ۳ درجه → +۰٫۲۱
            private const val EXTENT_ALPHA = 0.1f
        }
    }

    companion object {
        private const val TAG = "LivePictureAR"
        private const val MAX_PLAYERS = 3
        private const val STALE_MS = 30_000L
        /** مدتی که فیلم پس از گم شدن موقت عکس، با موقعیت ثبت‌شده روی آن می‌ماند */
        private const val HOLD_LAST_POSE_MS = 4_000L
        private const val SCREEN_MARGIN = 1.05f
        private const val FADE_STEP = 0.12f
    }
}
