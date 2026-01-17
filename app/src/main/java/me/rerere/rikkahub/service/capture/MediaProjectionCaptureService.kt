package me.rerere.rikkahub.service.capture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.activity.CapturePermissionActivity
import java.io.File

class MediaProjectionCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var projectionResultCode: Int? = null
    private var projectionResultData: Intent? = null

    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isCapturing = false
    private var isRequestingPermission = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("MediaProjectionCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        startForegroundCompat(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    saveProjection(resultCode, resultData)
                    if (intent.getBooleanExtra(EXTRA_CAPTURE_AFTER_SET, false)) {
                        requestCaptureInternal(DELAY_AFTER_PERMISSION_MS)
                    }
                }
            }

            ACTION_CAPTURE -> {
                requestCaptureInternal(DELAY_DEFAULT_MS)
            }

            ACTION_WARMUP -> {
                // Keep service alive; nothing else to do.
            }

            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun saveProjection(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionResultData = data
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        isRequestingPermission = false
    }

    private fun requestCaptureInternal(delayMs: Long) {
        if (isCapturing) return

        val projection = mediaProjection
        if (projection == null) {
            if (!isRequestingPermission) {
                isRequestingPermission = true
                // We don't have permission yet; request it once via a transparent activity.
                val intent = Intent(this, CapturePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            return
        }

        isCapturing = true
        handler?.postDelayed({ doCapture(projection) }, delayMs)
    }

    private fun doCapture(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val virtualDisplay = projection.createVirtualDisplay(
            "RikkaHubCapture",
            width,
            height,
            density,
            0,
            reader.surface,
            null,
            handler
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            image.close()

            val file = File(cacheDir, "capture_${System.currentTimeMillis()}.png")
            file.outputStream().use { os ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, os)
            }

            CaptureOverlayService.start(this, file.absolutePath)

            cropped.recycle()
            bitmap.recycle()

            r.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            imageReader = null
            isCapturing = false
        }, handler)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, FLOATING_BALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_floating_ball_title))
            .setContentText(getString(R.string.notification_floating_ball_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(id, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        mediaProjection = null
        projectionResultData = null
        projectionResultCode = null
        isRequestingPermission = false
    }

    companion object {
        const val ACTION_CAPTURE = "me.rerere.rikkahub.action.CAPTURE"
        const val ACTION_SET_PROJECTION = "me.rerere.rikkahub.action.SET_PROJECTION"
        const val ACTION_STOP = "me.rerere.rikkahub.action.STOP_CAPTURE_SERVICE"
        const val ACTION_WARMUP = "me.rerere.rikkahub.action.WARMUP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_CAPTURE_AFTER_SET = "captureAfterSet"

        private const val NOTIFICATION_ID = 2002
        private const val DELAY_AFTER_PERMISSION_MS = 700L
        private const val DELAY_DEFAULT_MS = 200L

        fun requestCapture(context: Context) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun warmup(context: Context) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_WARMUP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun setProjection(context: Context, resultCode: Int, data: Intent, captureAfterSet: Boolean) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_SET_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_CAPTURE_AFTER_SET, captureAfterSet)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
