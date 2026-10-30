package dlxs.cloude.tinymusic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

class MediaLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        handleIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val data: Uri? = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            val serviceIntent = Intent(this, TinyMediaService::class.java).apply {
                this.action = TinyMediaService.ACTION_PLAY_URI
                this.data = data
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else if (Intent.ACTION_MAIN == action) {
            Toast.makeText(this, R.string.toast_open_file, Toast.LENGTH_SHORT).show()
        }
    }
}
