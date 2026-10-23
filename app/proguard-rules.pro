# Tiviate R8 rules. Libraries (Media3, Room, OkHttp, Coil, Compose) ship their own consumer rules.
# XmlPullParser is provided by the Android platform at runtime.
-dontwarn org.xmlpull.v1.**
-dontwarn org.kxml2.**

# Media3 finds the FFmpeg audio renderer by its class name (DefaultRenderersFactory, extension mode ON):
# keep the name and the constructor it calls, or release builds silently lose AC3/E-AC3/DTS audio.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
