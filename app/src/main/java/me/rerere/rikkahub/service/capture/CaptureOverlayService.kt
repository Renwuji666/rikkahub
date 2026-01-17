package me.rerere.rikkahub.service.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.setPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.views.SelectionOverlayView
import me.rerere.rikkahub.utils.createChatFilesByByteArrays
import org.koin.android.ext.android.inject
import kotlin.math.max
import kotlin.math.min
import kotlin.uuid.Uuid

class CaptureOverlayService : Service() {
    private val chatService by inject<ChatService>()
    private val appScope by inject<AppScope>()

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null

    private var screenshotBitmap: Bitmap? = null
    private var screenshotImageView: ImageView? = null
    private var selectionView: SelectionOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_IMAGE_PATH)
        if (path.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(path)
        return START_NOT_STICKY
    }

    private fun showOverlay(path: String) {
        removeOverlay()

        val bitmap = BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            Toast.makeText(this, "截图解码失败", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        screenshotBitmap = bitmap

        val root = FrameLayout(this)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val overlay = SelectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val confirm = Button(this).apply {
            text = "确认"
            setPadding(24)
            setOnClickListener {
                val rect = overlay.getSelectionRect()
                if (rect == null) {
                    Toast.makeText(this@CaptureOverlayService, "请拖拽选择区域", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val crop = cropBitmapFromView(bitmap, imageView, rect)
                if (crop == null) {
                    Toast.makeText(this@CaptureOverlayService, "裁剪失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                sendToChat(crop)
            }
        }
        val cancel = Button(this).apply {
            text = "取消"
            setPadding(24)
            setOnClickListener {
                stopSelf()
            }
        }

        val confirmParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 32
            rightMargin = 32
        }
        val cancelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 32
            leftMargin = 32
        }

        root.addView(imageView)
        root.addView(overlay)
        root.addView(confirm, confirmParams)
        root.addView(cancel, cancelParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager?.addView(root, params)
        rootView = root
        screenshotImageView = imageView
        selectionView = overlay
    }

    private fun cropBitmapFromView(bitmap: Bitmap, imageView: ImageView, rect: android.graphics.RectF): Bitmap? {
        val values = FloatArray(9)
        imageView.imageMatrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]

        if (scaleX == 0f || scaleY == 0f) return null

        val left = ((rect.left - transX) / scaleX).toInt()
        val top = ((rect.top - transY) / scaleY).toInt()
        val right = ((rect.right - transX) / scaleX).toInt()
        val bottom = ((rect.bottom - transY) / scaleY).toInt()

        val clampedLeft = max(0, min(bitmap.width - 1, left))
        val clampedTop = max(0, min(bitmap.height - 1, top))
        val clampedRight = max(clampedLeft + 1, min(bitmap.width, right))
        val clampedBottom = max(clampedTop + 1, min(bitmap.height, bottom))

        return runCatching {
            Bitmap.createBitmap(
                bitmap,
                clampedLeft,
                clampedTop,
                clampedRight - clampedLeft,
                clampedBottom - clampedTop
            )
        }.getOrNull()
    }

    private fun sendToChat(bitmap: Bitmap) {
        appScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            val uri: Uri? = createChatFilesByByteArrays(listOf(bytes)).firstOrNull()
            if (uri == null) {
                Toast.makeText(this@CaptureOverlayService, "保存图片失败", Toast.LENGTH_SHORT).show()
                stopSelf()
                return@launch
            }
            val conversationId = readStringPreference("lastConversationId")?.let { Uuid.parse(it) } ?: Uuid.random()
            chatService.initializeConversation(conversationId)
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Image(url = uri.toString())),
                answer = true
            )
            Toast.makeText(this@CaptureOverlayService, "已发送", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        windowManager?.removeView(view)
        rootView = null
        screenshotImageView = null
        selectionView = null
        screenshotBitmap = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "imagePath"

        fun start(context: Context, imagePath: String) {
            context.startService(
                Intent(context, CaptureOverlayService::class.java).apply {
                    putExtra(EXTRA_IMAGE_PATH, imagePath)
                }
            )
        }
    }
}
