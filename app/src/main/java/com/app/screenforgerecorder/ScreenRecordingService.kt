package com.app.screenforgerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.ContentValues
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordingService : Service() {
  companion object {
    const val ACTION_START = "com.app.screenforgerecorder.START"
    const val ACTION_STOP = "com.app.screenforgerecorder.STOP"
    const val ACTION_PAUSE = "com.app.screenforgerecorder.PAUSE"
    const val ACTION_RESUME = "com.app.screenforgerecorder.RESUME"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_RESULT_DATA = "resultData"
    const val EXTRA_MICROPHONE = "microphone"
    const val EXTRA_QUALITY = "quality"
    const val EXTRA_FRAME_RATE = "frameRate"
    const val ACTION_ERROR = "com.app.screenforgerecorder.ERROR"
    private const val CHANNEL = "screenforge-recording"
    private const val NOTIFICATION_ID = 401
  }
  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var recorder: MediaRecorder? = null
  private var output: File? = null
  private var paused = false
  private var overlay: View? = null
  private var windowManager: WindowManager? = null
  private var cleaningUp = false

  override fun onCreate() { super.onCreate(); createChannel() }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startRecording(intent)
      ACTION_STOP -> stopRecording()
      ACTION_PAUSE -> if (Build.VERSION.SDK_INT >= 24 && !paused) { recorder?.pause(); paused = true; updateNotification("Capture paused") }
      ACTION_RESUME -> if (Build.VERSION.SDK_INT >= 24 && paused) { recorder?.resume(); paused = false; updateNotification("Recording screen") }
    }
    return START_NOT_STICKY
  }

  private fun startRecording(intent: Intent) {
    if (projection != null) return
    val wantsMicrophone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
    try {
      startForeground(NOTIFICATION_ID, notification("ScreenForge is recording"), foregroundType(wantsMicrophone))
      val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
      val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: error("Screen capture consent data is missing")
      val manager = getSystemService(MediaProjectionManager::class.java)
      projection = manager.getMediaProjection(resultCode, data) ?: error("Android did not return a MediaProjection instance")
      projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { if (!cleaningUp) stopRecording() } }, null)
      val metrics = resources.displayMetrics
      val requestedQuality = intent.getStringExtra(EXTRA_QUALITY) ?: "High"
      val preset = when (requestedQuality) { "Low", "720p" -> Triple(1280, 720, 8_000_000); "Medium" -> Triple(1920, 1080, 14_000_000); "1440p" -> Triple(2560, 1440, 30_000_000); else -> Triple(1920, 1080, 20_000_000) }
      val requestedFps = when (requestedQuality) { "High", "1440p" -> 60; "Medium", "Low", "720p" -> 30; else -> intent.getIntExtra(EXTRA_FRAME_RATE, 60).coerceIn(30, 60) }
      val portrait = metrics.heightPixels > metrics.widthPixels
      val targetWidth = if (portrait) preset.second else preset.first
      val targetHeight = if (portrait) preset.first else preset.second
      val displayShort = minOf(metrics.widthPixels, metrics.heightPixels)
      val displayLong = maxOf(metrics.widthPixels, metrics.heightPixels)
      val scale = minOf(1f, displayShort.toFloat() / minOf(targetWidth, targetHeight), displayLong.toFloat() / maxOf(targetWidth, targetHeight))
      val width = ((targetWidth * scale).toInt()).let { it - (it % 2) }
      val height = ((targetHeight * scale).toInt()).let { it - (it % 2) }
      val density = metrics.densityDpi
      val videoBitrate = if (scale < 1f) (preset.third * scale * scale).toInt().coerceAtLeast(4_000_000) else preset.third
      val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenForge").apply { mkdirs() }
      output = File(dir, "ScreenForge_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
      if (output!!.parentFile?.usableSpace ?: 0L < 50L * 1024L * 1024L) error("Not enough storage to start recording")
      recorder = MediaRecorder(this).apply {
        if (intent.getBooleanExtra(EXTRA_MICROPHONE, false)) setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (intent.getBooleanExtra(EXTRA_MICROPHONE, false)) {
          setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
          setAudioEncodingBitRate(192_000)
          setAudioSamplingRate(48_000)
        }
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (Build.VERSION.SDK_INT >= 26) { try { setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel4) } catch (profileError: Exception) { Log.w("ScreenForgeRecorder", "H.264 High Profile unavailable; using encoder default", profileError) } }
        setVideoEncodingBitRate(videoBitrate)
        setVideoFrameRate(requestedFps)
        setVideoSize(width, height)
        setOutputFile(output!!.absolutePath)
        prepare()
      }
      display = projection!!.createVirtualDisplay("ScreenForge", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder!!.surface, null, null)
      try {
        recorder!!.start()
      } catch (error: RuntimeException) {
        Log.e("ScreenForgeRecorder", "MediaRecorder.start() failed", error)
        throw error
      }
      showControls()
    } catch (error: Exception) {
      Log.e("ScreenForgeRecorder", "Recording service failed to start", error)
      sendBroadcast(Intent(ACTION_ERROR).putExtra("message", error.message ?: "The recorder could not start."))
      cleanup(deleteOutput = true)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun stopRecording() {
    var finalized = false
    try { recorder?.stop(); finalized = true } catch (error: RuntimeException) { Log.e("ScreenForgeRecorder", "MediaRecorder.stop() failed", error); output?.delete() }
    if (finalized) output?.let { publishToPublicMovies(it) }
    cleanup(deleteOutput = false)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun publishToPublicMovies(source: File) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, source.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenForge"); put(MediaStore.Video.Media.IS_PENDING, 1) }
        val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MediaStore did not create a public video entry")
        try { contentResolver.openOutputStream(uri)?.use { destination -> source.inputStream().use { it.copyTo(destination) } } ?: error("MediaStore output stream unavailable"); contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
      } else {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenForge").apply { mkdirs() }; val target = File(publicDir, source.name); source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }; MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
      }
    } catch (error: Exception) { Log.e("ScreenForgeRecorder", "Public MediaStore copy failed; private recording remains available", error) }
  }

  private fun cleanup(deleteOutput: Boolean) {
    cleaningUp = true
    if (deleteOutput) output?.delete()
    removeControls()
    display?.release(); display = null
    recorder?.reset(); recorder?.release(); recorder = null
    val activeProjection = projection
    projection = null
    activeProjection?.stop()
    output = null; paused = false
    cleaningUp = false
  }

  private fun showControls() {
    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(10, 6, 10, 6)
      background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.argb(235, 28, 26, 45)) }
    }
    val pause = TextView(this).apply { text = "Ⅱ"; textSize = 18f; setTextColor(Color.WHITE); setPadding(12, 4, 12, 4); setOnClickListener { if (paused) { recorder?.resume(); paused = false; text = "Ⅱ"; updateNotification("Recording screen") } else { if (Build.VERSION.SDK_INT >= 24) recorder?.pause(); paused = true; text = "▶"; updateNotification("Capture paused") } } }
    val stop = TextView(this).apply { text = "■"; textSize = 18f; setTextColor(Color.rgb(251, 113, 133)); setPadding(12, 4, 12, 4); setOnClickListener { stopRecording() } }
    panel.addView(pause); panel.addView(stop)
    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 80 }
    try { windowManager?.addView(panel, params); overlay = panel } catch (_: Exception) { overlay = null }
  }

  private fun removeControls() { try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {} ; overlay = null }

  private fun foregroundType(microphone: Boolean): Int = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) else 0
  private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)) }
  private fun notification(text: String): Notification { val stopIntent = Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP); val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0); val stop = PendingIntent.getService(this, 402, stopIntent, flags); return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("ScreenForge Recorder").setContentText(text).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(android.R.drawable.ic_media_pause, "Stop", stop).build() }
  private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onDestroy() { if (projection != null) stopRecording(); super.onDestroy() }
}
