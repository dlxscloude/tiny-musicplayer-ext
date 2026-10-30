# Jetpack Media3 keep rules
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# Keep application entry points
-keep class dlxs.cloude.tinymusic.TinyMediaService { *; }
-keep class dlxs.cloude.tinymusic.MediaLauncherActivity { *; }

# Aggressive shrinking and optimization
-allowaccessmodification
