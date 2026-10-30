package dlxs.cloude.tinymusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MediaLauncherActivity : ComponentActivity() {

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::playUri)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MAIN -> filePicker.launch("audio/*")
            Intent.ACTION_VIEW -> {
                intent.data?.let(::playUri)
                finish()
                overridePendingTransition(0, 0)
            }
            else -> {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun playUri(uri: Uri) {
        val serviceIntent = Intent(this, TinyMediaService::class.java).apply {
            action = TinyMediaService.ACTION_PLAY_URI
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
