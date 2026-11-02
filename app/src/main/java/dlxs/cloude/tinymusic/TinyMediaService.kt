package dlxs.cloude.tinymusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.RemoteViews
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@UnstableApi
class TinyMediaService : Service() {

    private var player: ExoPlayer? = null
    private var fallbackTrackTitle = ""
    private var playlistLoadGeneration = 0
    private var playlistLoading = false
    private var pendingPlaylistNavigation = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_PLAY_URI = "dlxs.cloude.tinymusic.action.PLAY_URI"
        const val ACTION_TOGGLE_PLAY = "dlxs.cloude.tinymusic.action.TOGGLE_PLAY"
        const val ACTION_PREV = "dlxs.cloude.tinymusic.action.PREV"
        const val ACTION_NEXT = "dlxs.cloude.tinymusic.action.NEXT"
        const val ACTION_TOGGLE_LOOP = "dlxs.cloude.tinymusic.action.TOGGLE_LOOP"
        const val ACTION_DISMISS = "dlxs.cloude.tinymusic.action.DISMISS"

        const val CHANNEL_ID = "tiny_music_channel"
        const val NOTIFICATION_ID = 1001

        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "opus")
    }

    private fun toggleLoop() {
        player?.let { p ->
            p.repeatMode = if (p.repeatMode == Player.REPEAT_MODE_ALL) {
                Player.REPEAT_MODE_OFF
            } else {
                Player.REPEAT_MODE_ALL
            }
        }
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
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updatePlaybackNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                fallbackTrackTitle = mediaItem?.localConfiguration?.uri?.lastPathSegment.orEmpty()
                updatePlaybackNotification()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackNotification()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePlaybackNotification()
            }
        })
        player = exoPlayer
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISMISS) {
            exitPlayback()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PLAY_URI -> {
                updatePlaybackNotification()
                intent?.data?.let(::loadPlaylistAndPlay)
            }
            ACTION_TOGGLE_PLAY -> player?.let { p ->
                if (p.playWhenReady) p.pause() else p.play()
            }
            ACTION_PREV -> navigatePlaylist(-1)
            ACTION_NEXT -> navigatePlaylist(1)
            ACTION_TOGGLE_LOOP -> toggleLoop()
        }

        updatePlaybackNotification()
        return START_NOT_STICKY
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TinyMediaService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun skipToPrevious(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count > 1) {
            val index = (player.currentMediaItemIndex - 1 + count) % count
            player.seekTo(index, 0)
        }
    }

    private fun skipToNext(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count > 1) {
            val index = (player.currentMediaItemIndex + 1) % count
            player.seekTo(index, 0)
        }
    }

    private fun navigatePlaylist(direction: Int) {
        val currentPlayer = player ?: return
        if (playlistLoading) {
            pendingPlaylistNavigation += direction
        } else if (direction < 0) {
            skipToPrevious(currentPlayer)
        } else {
            skipToNext(currentPlayer)
        }
    }

    private fun exitPlayback() {
        player?.run {
            stop()
            clearMediaItems()
            release()
        }
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun loadPlaylistAndPlay(targetUri: Uri) {
        val p = player ?: return
        val generation = ++playlistLoadGeneration
        playlistLoading = true
        pendingPlaylistNavigation = 0

        val displayName = DocumentFile.fromSingleUri(this, targetUri)?.name
            ?: targetUri.lastPathSegment
            ?: "Track"
        fallbackTrackTitle = displayName
        val initialTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "Track" }
        val initialItem = MediaItem.Builder()
            .setUri(targetUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(initialTitle)
                    .setDisplayTitle(initialTitle)
                    .build()
            )
            .build()

        p.setMediaItem(initialItem)
        p.prepare()
        p.play()

        Thread {
            val (mediaItems, targetIndex) = resolvePlaylist(targetUri)
            mainHandler.post {
                if (generation != playlistLoadGeneration || player !== p) return@post

                playlistLoading = false
                val navigation = pendingPlaylistNavigation
                pendingPlaylistNavigation = 0
                if (mediaItems.isNotEmpty()) {
                    val startIndex = targetIndex.coerceIn(mediaItems.indices)
                    val selectedIndex = if (mediaItems.size > 1) {
                        (startIndex + navigation % mediaItems.size + mediaItems.size) % mediaItems.size
                    } else {
                        0
                    }
                    p.setMediaItems(
                        mediaItems,
                        selectedIndex,
                        if (navigation == 0) p.currentPosition else 0
                    )
                }
                updatePlaybackNotification()
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
                var relativePath: String? = null
                var targetName: String? = null
                var targetId: Long? = null

                contentResolver.query(
                    targetUri,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.BUCKET_ID,
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        MediaStore.Audio.Media.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                        if (idCol != -1) targetId = cursor.getLong(idCol)
                        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (dataCol != -1) targetData = cursor.getString(dataCol)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_ID)
                        if (bucketCol != -1) bucketId = cursor.getString(bucketCol)
                        val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                        if (pathCol != -1) relativePath = cursor.getString(pathCol)
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

                val folderSelection = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrEmpty() ->
                        "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
                    !bucketId.isNullOrEmpty() ->
                        "${MediaStore.Audio.Media.BUCKET_ID} = ?"
                    else -> null
                }
                val folderSelectionArgs = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrEmpty() ->
                        arrayOf(relativePath)
                    !bucketId.isNullOrEmpty() -> arrayOf(bucketId)
                    else -> null
                }

                if (folderSelection != null && folderSelectionArgs != null) {
                    val items = mutableListOf<MediaItem>()
                    var targetIndex = 0

                    contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.DISPLAY_NAME,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.RELATIVE_PATH
                        ),
                        folderSelection,
                        folderSelectionArgs,
                        "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                        val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                        var idx = 0
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: ""
                            val itemPath = if (pathCol != -1) cursor.getString(pathCol) else null
                            val title = if (titleCol != -1) cursor.getString(titleCol) ?: name.substringBeforeLast('.') else name.substringBeforeLast('.')
                            val ext = name.substringAfterLast('.', "").lowercase()

                            if (ext in SUPPORTED_EXTENSIONS &&
                                (relativePath == null || itemPath == relativePath)
                            ) {
                                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                if (id == targetId || name.equals(targetName, ignoreCase = true) || itemUri == targetUri) {
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

    private fun buildNotification(p: ExoPlayer): Notification {
        val metadata = p.mediaMetadata
        val title = metadata.displayTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackTrackTitle.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val isLooping = p.repeatMode == Player.REPEAT_MODE_ALL
        val contentText = when {
            isLooping && p.playWhenReady -> getString(R.string.playing_looping)
            isLooping -> getString(R.string.paused_looping)
            p.playWhenReady -> getString(R.string.playing)
            else -> getString(R.string.paused)
        }

        val compactView = RemoteViews(packageName, R.layout.notification_controls_compact)
        val expandedView = RemoteViews(packageName, R.layout.notification_controls_expanded)
        val textColor = if (
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) Color.WHITE else Color.BLACK
        val loopLabel = getString(if (isLooping) R.string.action_loop_on else R.string.action_loop_off)
        val playLabel = getString(if (p.playWhenReady) R.string.action_pause else R.string.action_play)
        val actions = listOf(
            Triple(R.id.action_prev, getString(R.string.action_prev), ACTION_PREV),
            Triple(R.id.action_play_pause, playLabel, ACTION_TOGGLE_PLAY),
            Triple(R.id.action_next, getString(R.string.action_next), ACTION_NEXT),
            Triple(R.id.action_loop, loopLabel, ACTION_TOGGLE_LOOP),
            Triple(R.id.action_exit, getString(R.string.action_exit), ACTION_DISMISS)
        )
        for ((viewId, label, action) in actions) {
            for (remoteView in listOf(compactView, expandedView)) {
                remoteView.setTextViewText(viewId, label)
                remoteView.setTextColor(viewId, textColor)
                remoteView.setContentDescription(viewId, label)
                remoteView.setOnClickPendingIntent(
                    viewId,
                    createActionPendingIntent(action, when (action) {
                        ACTION_PREV -> 1
                        ACTION_TOGGLE_PLAY -> 0
                        ACTION_NEXT -> 2
                        ACTION_TOGGLE_LOOP -> 3
                        else -> 4
                    })
                )
            }
        }
        expandedView.setTextViewText(R.id.notification_title, title)
        expandedView.setTextViewText(R.id.notification_status, contentText)
        expandedView.setTextColor(R.id.notification_title, textColor)
        expandedView.setTextColor(R.id.notification_status, textColor)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .setDeleteIntent(createActionPendingIntent(ACTION_DISMISS, 4))
            .build()
    }

    private fun updatePlaybackNotification() {
        val currentPlayer = player ?: return
        val notification = buildNotification(currentPlayer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service alive in foreground. Tiny Music is headless and controlled via notifications.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
