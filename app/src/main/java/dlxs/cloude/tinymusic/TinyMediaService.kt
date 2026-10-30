package dlxs.cloude.tinymusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

@UnstableApi
class TinyMediaService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_PLAY_URI = "dlxs.cloude.tinymusic.action.PLAY_URI"
        const val ACTION_CUSTOM_ACTION = "dlxs.cloude.tinymusic.action.CUSTOM_ACTION"
        const val EXTRA_ACTION = "extra_action"

        const val ACTION_CLOSE = "dlxs.cloude.tinymusic.command.CLOSE"
        const val ACTION_LOOP = "dlxs.cloude.tinymusic.command.LOOP"
        const val ACTION_SHUFFLE = "dlxs.cloude.tinymusic.command.SHUFFLE"

        const val CHANNEL_ID = "tiny_music_channel"
        const val NOTIFICATION_ID = 1001

        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "opus")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                updateCustomCommands()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCustomCommands()
            }
        })
        player = exoPlayer

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_LOOP, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setCustomLayout(buildCustomLayout())
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                handleCustomAction(customCommand.customAction)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(sessionCallback)
            .setCustomLayout(buildCustomLayout())
            .build()

        // Configure MediaNotificationProvider
        setMediaNotificationProvider(createNotificationProvider())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Loading...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (ignored: Exception) {
        }

        intent?.let {
            when (it.action) {
                ACTION_PLAY_URI -> {
                    it.data?.let { uri ->
                        loadPlaylistAndPlay(uri)
                    }
                }
                ACTION_CUSTOM_ACTION -> {
                    it.getStringExtra(EXTRA_ACTION)?.let { action ->
                        handleCustomAction(action)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val p = player ?: return ImmutableList.of()

        val shuffleButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_shuffle))
            .setIconResId(if (p.shuffleModeEnabled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off)
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .build()

        val loopIcon = when (p.repeatMode) {
            Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat_off
        }
        val loopButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_loop))
            .setIconResId(loopIcon)
            .setSessionCommand(SessionCommand(ACTION_LOOP, Bundle.EMPTY))
            .build()

        val closeButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_close))
            .setIconResId(R.drawable.ic_close)
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .build()

        return ImmutableList.of(shuffleButton, loopButton, closeButton)
    }

    private fun updateCustomCommands() {
        mediaSession?.setCustomLayout(buildCustomLayout())
    }

    private fun handleCustomAction(action: String) {
        val p = player ?: return
        when (action) {
            ACTION_CLOSE -> {
                p.stop()
                p.clearMediaItems()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_LOOP -> {
                p.repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                updateCustomCommands()
            }
            ACTION_SHUFFLE -> {
                p.shuffleModeEnabled = !p.shuffleModeEnabled
                updateCustomCommands()
            }
        }
    }

    private fun loadPlaylistAndPlay(targetUri: Uri) {
        val p = player ?: return

        // 1. Instantly start playback of the selected file so playback is instant and service stays alive
        val initialTitle = targetUri.lastPathSegment?.substringBeforeLast('.') ?: "Track"
        val initialItem = MediaItem.Builder()
            .setUri(targetUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(initialTitle)
                    .build()
            )
            .build()

        p.setMediaItem(initialItem)
        p.prepare()
        p.play()

        // 2. Scan parent folder for siblings and update playlist queue seamlessly
        Thread {
            val (mediaItems, targetIndex) = resolvePlaylist(targetUri)
            if (mediaItems.size > 1) {
                mainHandler.post {
                    val currentPos = p.currentPosition
                    p.setMediaItems(mediaItems, targetIndex, currentPos)
                }
            }
        }.start()
    }

    private fun resolvePlaylist(targetUri: Uri): Pair<List<MediaItem>, Int> {
        val scheme = targetUri.scheme

        // 1. If file:// scheme
        if (ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) || targetUri.path?.startsWith("/") == true) {
            val targetFile = File(targetUri.path ?: "")
            val parent = targetFile.parentFile
            if (parent != null && parent.isDirectory) {
                val files = parent.listFiles { f ->
                    !f.isDirectory && f.extension.lowercase() in SUPPORTED_EXTENSIONS
                }?.sortedBy { it.name.lowercase() }
                if (!files.isNullOrEmpty()) {
                    val items = files.map { file ->
                        MediaItem.Builder()
                            .setUri(Uri.fromFile(file))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(file.nameWithoutExtension)
                                    .build()
                            )
                            .build()
                    }
                    val index = files.indexOfFirst { it.absolutePath == targetFile.absolutePath }
                    return Pair(items, index.coerceAtLeast(0))
                }
            }
        }

        // 2. If content:// scheme, try MediaStore
        if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
            try {
                var targetData: String? = null
                var bucketId: String? = null
                var targetName: String? = null

                contentResolver.query(
                    targetUri,
                    arrayOf(
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.BUCKET_ID,
                        MediaStore.Audio.Media.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (dataCol != -1) targetData = cursor.getString(dataCol)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_ID)
                        if (bucketCol != -1) bucketId = cursor.getString(bucketCol)
                        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                        if (nameCol != -1) targetName = cursor.getString(nameCol)
                    }
                }

                // If filesystem path is accessible from DATA
                if (!targetData.isNullOrEmpty()) {
                    val targetFile = File(targetData!!)
                    val parent = targetFile.parentFile
                    if (parent != null && parent.isDirectory && parent.canRead()) {
                        val files = parent.listFiles { f ->
                            !f.isDirectory && f.extension.lowercase() in SUPPORTED_EXTENSIONS
                        }?.sortedBy { it.name.lowercase() }
                        if (!files.isNullOrEmpty()) {
                            val items = files.map { file ->
                                MediaItem.Builder()
                                    .setUri(Uri.fromFile(file))
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(file.nameWithoutExtension)
                                            .build()
                                    )
                                    .build()
                            }
                            val index = files.indexOfFirst { it.absolutePath == targetFile.absolutePath }
                            return Pair(items, index.coerceAtLeast(0))
                        }
                    }
                }

                // If we have bucketId, query all audio tracks in that bucket
                if (!bucketId.isNullOrEmpty()) {
                    val items = mutableListOf<MediaItem>()
                    var targetIndex = 0

                    contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.DISPLAY_NAME,
                            MediaStore.Audio.Media.TITLE
                        ),
                        "${MediaStore.Audio.Media.BUCKET_ID} = ?",
                        arrayOf(bucketId),
                        "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)

                        var idx = 0
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: ""
                            val title = if (titleCol != -1) cursor.getString(titleCol) ?: name.substringBeforeLast('.') else name.substringBeforeLast('.')
                            val ext = name.substringAfterLast('.', "").lowercase()

                            if (ext in SUPPORTED_EXTENSIONS) {
                                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                if (name.equals(targetName, ignoreCase = true) || itemUri == targetUri) {
                                    targetIndex = idx
                                }
                                items.add(
                                    MediaItem.Builder()
                                        .setUri(itemUri)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(title)
                                                .build()
                                        )
                                        .build()
                                )
                                idx++
                            }
                        }
                    }

                    if (items.isNotEmpty()) {
                        return Pair(items, targetIndex)
                    }
                }
            } catch (ignored: Exception) {
                // MediaStore query fallback
            }

            // 3. DocumentFile parent resolution
            try {
                val doc = DocumentFile.fromSingleUri(this, targetUri)
                val parentDoc = doc?.parentFile
                if (parentDoc != null && parentDoc.isDirectory) {
                    val docFiles = parentDoc.listFiles().filter { f ->
                        !f.isDirectory && f.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS
                    }.sortedBy { it.name?.lowercase() ?: "" }

                    if (docFiles.isNotEmpty()) {
                        val items = docFiles.map { f ->
                            val title = f.name?.substringBeforeLast('.') ?: "Audio"
                            MediaItem.Builder()
                                .setUri(f.uri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(title)
                                        .build()
                                )
                                .build()
                        }
                        val index = docFiles.indexOfFirst { it.uri == targetUri }
                        return Pair(items, index.coerceAtLeast(0))
                    }
                }
            } catch (ignored: Exception) {
                // DocumentFile fallback
            }
        }

        // 4. Single-item fallback
        val doc = DocumentFile.fromSingleUri(this, targetUri)
        val title = doc?.name?.substringBeforeLast('.') ?: targetUri.lastPathSegment ?: "Track"
        val singleItem = MediaItem.Builder()
            .setUri(targetUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()
        return Pair(listOf(singleItem), 0)
    }

    private fun createNotificationProvider(): MediaNotification.Provider {
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        return provider
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service alive in foreground. Tiny Music is headless and controlled via notifications.
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
