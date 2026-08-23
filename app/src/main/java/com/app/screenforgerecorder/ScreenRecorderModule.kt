package com.app.screenforgerecorder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import java.io.File
import android.media.projection.MediaProjectionManager
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class ScreenRecorderModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context), ActivityEventListener {
  companion object { private const val REQUEST_CAPTURE = 7351; private const val REQUEST_CAPTURE_PERMISSION = 7352 }
  private var pending: Promise? = null
  private var pendingMicrophone = false
  private var pendingQuality = "1080p"
  private var pendingFrameRate = 60
  private var permissionPending: Promise? = null

  init { context.addActivityEventListener(this) }
  override fun getName() = "ScreenForgeRecorder"

  @ReactMethod
  fun requestScreenCapturePermission(promise: Promise) {
    val activity = context.currentActivity
    if (activity == null) { promise.reject("NO_ACTIVITY", "ScreenForge must be visible before requesting screen capture permission."); return }
    if (permissionPending != null) { promise.reject("PERMISSION_PENDING", "A screen capture permission request is already in progress."); return }
    permissionPending = promise
    activity.startActivityForResult(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQUEST_CAPTURE_PERMISSION)
  }

  @ReactMethod
  fun startCapture(options: ReadableMap, promise: Promise) {
    val activity = context.currentActivity
    if (activity == null) { promise.reject("NO_ACTIVITY", "ScreenForge must be visible before screen capture can start."); return }
    if (pending != null) { promise.reject("CAPTURE_PENDING", "A screen capture permission request is already in progress."); return }
    pending = promise
    pendingMicrophone = options.hasKey("microphone") && options.getBoolean("microphone")
    pendingQuality = if (options.hasKey("quality")) options.getString("quality") ?: "1080p" else "1080p"
    pendingFrameRate = if (options.hasKey("frameRate")) options.getInt("frameRate") else 60
    val manager = activity.getSystemService(MediaProjectionManager::class.java)
    activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
  }

  @ReactMethod
  fun stopCapture(promise: Promise) {
    context.startService(Intent(context, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_STOP))
    promise.resolve(true)
  }

  @ReactMethod
  fun pauseCapture(promise: Promise) {
    context.startService(Intent(context, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_PAUSE))
    promise.resolve(true)
  }

  @ReactMethod
  fun resumeCapture(promise: Promise) {
    context.startService(Intent(context, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_RESUME))
    promise.resolve(true)
  }

  @ReactMethod
  fun listRecordings(promise: Promise) {
    val array = com.facebook.react.bridge.WritableNativeArray()
    val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "ScreenForge")
    dir.listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }?.sortedByDescending { it.lastModified() }?.forEach { file ->
      val item = WritableNativeMap()
      item.putString("uri", file.toURI().toString())
      item.putString("path", file.absolutePath)
      item.putString("name", file.nameWithoutExtension)
      item.putDouble("size", file.length().toDouble())
      item.putDouble("modified", file.lastModified().toDouble())
      array.pushMap(item)
    }
    promise.resolve(array)
  }

  @ReactMethod
  fun deleteRecording(path: String, promise: Promise) {
    try { promise.resolve(File(path).delete()) } catch (error: Exception) { promise.reject("DELETE_FAILED", error.message, error) }
  }

  @ReactMethod
  fun renameRecording(path: String, name: String, promise: Promise) {
    try {
      val input = File(path)
      val safeName = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").removeSuffix(".mp4") + ".mp4"
      val target = File(input.parentFile, safeName)
      require(!target.exists()) { "A recording with that name already exists." }
      require(input.renameTo(target)) { "Android could not rename the recording." }
      promise.resolve(target.absolutePath)
    } catch (error: Exception) { promise.reject("RENAME_FAILED", error.message, error) }
  }

  @ReactMethod
  fun trimRecording(path: String, startMs: Double, endMs: Double, promise: Promise) {
    Thread {
      try {
        val input = File(path)
        require(input.exists()) { "The source recording no longer exists." }
        require(endMs > startMs) { "Trim end must be after trim start." }
        val output = File(input.parentFile, input.nameWithoutExtension + "_trimmed_" + System.currentTimeMillis() + ".mp4")
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        for (index in 0 until extractor.trackCount) trackMap[index] = muxer.addTrack(extractor.getTrackFormat(index))
        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        for (index in 0 until extractor.trackCount) {
          extractor.selectTrack(index)
          extractor.seekTo((startMs * 1000.0).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
          while (true) {
            val timeUs = extractor.sampleTime
            if (timeUs < 0 || timeUs > (endMs * 1000.0).toLong()) break
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = (timeUs - (startMs * 1000.0).toLong()).coerceAtLeast(0)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackMap[index]!!, buffer, info)
            extractor.advance()
          }
          extractor.unselectTrack(index)
        }
        muxer.stop(); muxer.release(); extractor.release()
        promise.resolve(output.absolutePath)
      } catch (error: Exception) { promise.reject("TRIM_FAILED", error.message, error) }
    }.start()
  }

  @ReactMethod
  fun shareRecording(path: String, promise: Promise) {
    try {
      val file = File(path)
      val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
      val share = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
      context.currentActivity?.startActivity(Intent.createChooser(share, "Share ScreenForge capture"))
      promise.resolve(true)
    } catch (error: Exception) { promise.reject("SHARE_FAILED", error.message, error) }
  }

  @ReactMethod
  fun openOverlaySettings(promise: Promise) {
    try { context.currentActivity?.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))); promise.resolve(true) } catch (error: Exception) { promise.reject("OVERLAY_SETTINGS_FAILED", error.message, error) }
  }

  @ReactMethod
  fun getCapabilities(promise: Promise) {
    val result = WritableNativeMap()
    result.putBoolean("screenCapture", true)
    result.putBoolean("microphone", true)
    result.putBoolean("internalAudio", false)
    result.putBoolean("facecamComposite", false)
    result.putBoolean("overlayControls", if (android.os.Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true)
    promise.resolve(result)
  }

  override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_CAPTURE_PERMISSION) { val request = permissionPending; permissionPending = null; if (resultCode == Activity.RESULT_OK && data != null) request?.resolve(true) else request?.reject("CAPTURE_DENIED", "Screen capture permission was denied."); return }
    if (requestCode != REQUEST_CAPTURE) return
    val request = pending
    pending = null
    if (resultCode != Activity.RESULT_OK || data == null) { request?.reject("CAPTURE_DENIED", "Screen capture permission was denied. No recording was started."); return }
    val intent = Intent(context, ScreenRecordingService::class.java).apply {
      action = ScreenRecordingService.ACTION_START
      putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
      putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
      putExtra(ScreenRecordingService.EXTRA_MICROPHONE, pendingMicrophone)
      putExtra(ScreenRecordingService.EXTRA_QUALITY, pendingQuality)
      putExtra(ScreenRecordingService.EXTRA_FRAME_RATE, pendingFrameRate)
    }
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
      request?.resolve(true)
    } catch (error: Exception) { request?.reject("CAPTURE_START_FAILED", error.message, error) }
  }
  override fun onNewIntent(intent: Intent) = Unit
  fun emit(event: String, payload: WritableNativeMap) { context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, payload) }
}
