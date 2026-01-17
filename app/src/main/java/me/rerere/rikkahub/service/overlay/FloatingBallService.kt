package me.rerere.rikkahub.service.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.activity.CaptureSelectActivity
import kotlin.math.abs

class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addFloatingBall()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingBall()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ballView == null) {
            addFloatingBall()
        }
        return START_STICKY
    }

    private fun addFloatingBall() {
        if (windowManager == null || ballView != null) return

        val size = resources.getDimensionPixelSize(R.dimen.floating_ball_size)
        val view = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1F1F1F.toInt())
        }
        view.background = bg
        view.elevation = resources.getDimension(R.dimen.floating_ball_elevation)

        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        view.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        view.setOnTouchListener(DragTouchListener(params))
        view.setOnClickListener {
            val intent = Intent(this, CaptureSelectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        windowManager?.addView(view, params)
        ballView = view
        layoutParams = params
    }

    private fun removeFloatingBall() {
        val view = ballView ?: return
        windowManager?.removeView(view)
        ballView = null
        layoutParams = null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, RouteActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, FLOATING_BALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_floating_ball_title))
            .setContentText(getString(R.string.notification_floating_ball_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!moved && (abs(dx) > 6 || abs(dy) > 6)) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
