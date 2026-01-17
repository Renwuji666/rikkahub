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
import java.io.File

class MediaProjectionCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("MediaProjectionCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(NOTIFICATION_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Give system time to return to the previous app after the permission activity finishes.
        handler?.postDelayed({
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            val virtualDisplay = mediaProjection?.createVirtualDisplay(
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
            mediaProjection?.stop()
            stopSelf()
        }, handler)
        }, DELAY_BEFORE_CAPTURE_MS)
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
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val NOTIFICATION_ID = 2002
        private const val DELAY_BEFORE_CAPTURE_MS = 700L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
