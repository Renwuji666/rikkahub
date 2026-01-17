package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import me.rerere.rikkahub.service.capture.MediaProjectionCaptureService

class CapturePermissionActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // Start foreground capture service and immediately finish so user returns to previous app.
            MediaProjectionCaptureService.start(this, result.resultCode, data)
        }
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
