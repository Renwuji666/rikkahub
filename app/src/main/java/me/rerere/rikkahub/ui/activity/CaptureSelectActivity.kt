package me.rerere.rikkahub.ui.activity

import androidx.activity.ComponentActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.capture.MediaProjectionCaptureService
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.views.SelectionOverlayView
import me.rerere.rikkahub.utils.createChatFilesByByteArrays
import org.koin.android.ext.android.inject
import kotlin.math.max
import kotlin.math.min
import kotlin.uuid.Uuid

class CaptureSelectActivity : ComponentActivity() {
    private val chatService by inject<ChatService>()

    private var capturedBitmap: Bitmap? = null
    private var receiverRegistered = false

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MediaProjectionCaptureService.ACTION_CAPTURED) return
            val path = intent.getStringExtra(MediaProjectionCaptureService.EXTRA_IMAGE_PATH) ?: return
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap == null) {
                Toast.makeText(this@CaptureSelectActivity, "截图失败", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            capturedBitmap = bitmap
            showSelectionUi(bitmap)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MediaProjectionCaptureService.start(this, result.resultCode, data)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerCaptureReceiver()
        setContentView(
            FrameLayout(this).apply {
                addView(
                    TextView(this@CaptureSelectActivity).apply {
                        text = "准备截图…"
                        textSize = 18f
                        setPadding(32)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
        )
        requestCapturePermission()
    }

    private fun requestCapturePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun showSelectionUi(bitmap: Bitmap) {
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
                    Toast.makeText(this@CaptureSelectActivity, "请拖拽选择区域", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val crop = cropBitmapFromView(bitmap, imageView, rect)
                if (crop == null) {
                    Toast.makeText(this@CaptureSelectActivity, "裁剪失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                sendToChat(crop)
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

        root.addView(imageView)
        root.addView(overlay)
        root.addView(confirm, confirmParams)
        setContentView(root)
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
            Bitmap.createBitmap(bitmap, clampedLeft, clampedTop, clampedRight - clampedLeft, clampedBottom - clampedTop)
        }.getOrNull()
    }

    private fun sendToChat(bitmap: Bitmap) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            val uri = createChatFilesByByteArrays(listOf(bytes)).firstOrNull()
            if (uri == null) {
                Toast.makeText(this@CaptureSelectActivity, "保存图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val conversationId = readStringPreference("lastConversationId")?.let { Uuid.parse(it) } ?: Uuid.random()
            chatService.initializeConversation(conversationId)
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Image(url = uri.toString())),
                answer = true
            )
            Toast.makeText(this@CaptureSelectActivity, "已发送", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCaptureReceiver()
        capturedBitmap?.recycle()
        capturedBitmap = null
    }

    private fun registerCaptureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(MediaProjectionCaptureService.ACTION_CAPTURED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterCaptureReceiver() {
        if (!receiverRegistered) return
        unregisterReceiver(captureReceiver)
        receiverRegistered = false
    }
}
