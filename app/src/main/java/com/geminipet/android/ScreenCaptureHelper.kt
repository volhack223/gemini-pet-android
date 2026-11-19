package com.geminipet.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 持久化单 VirtualDisplay 内存级截屏辅助类（适配 Android 14+ 不因重复创建而掉线，0 字节写入磁盘）
 */
class ScreenCaptureHelper(
    private val context: Context,
    private val projection: MediaProjection
) {
    private val handler = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var targetWidth = 720
    private var targetHeight = 1280
    @Volatile
    private var isAlive = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            isAlive = false
            cleanupDisplay()
        }
    }

    init {
        try {
            projection.registerCallback(projectionCallback, handler)
            setupPersistentDisplay()
        } catch (_: Exception) {
            isAlive = false
        }
    }

    private fun setupPersistentDisplay() {
        val dm = context.resources.displayMetrics
        targetWidth = 720
        val scale = targetWidth.toFloat() / dm.widthPixels.coerceAtLeast(1)
        targetHeight = (dm.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = dm.densityDpi

        val imgReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        reader = imgReader
        virtualDisplay = projection.createVirtualDisplay(
            "GeminiPetPersistentCapture",
            targetWidth,
            targetHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imgReader.surface,
            null,
            handler
        )
    }

    fun isAvailable(): Boolean = isAlive && reader != null && virtualDisplay != null

    suspend fun captureJpegDataUrl(quality: Int = 48): String? = suspendCancellableCoroutine { cont ->
        val imgReader = reader
        if (!isAlive || imgReader == null) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 先尝试直接读取当前缓冲帧
        val existing = try { imgReader.acquireLatestImage() } catch (_: Exception) { null }
        if (existing != null) {
            val encoded = encodeImageToJpegDataUrl(existing, quality)
            if (cont.isActive) cont.resume(encoded)
            return@suspendCancellableCoroutine
        }

        // 如果缓冲为空，短暂监听下一帧
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                imgReader.setOnImageAvailableListener(null, null)
                if (cont.isActive) cont.resume(null)
            }
        }
        handler.postDelayed(timeout, 1500L)

        imgReader.setOnImageAvailableListener({ r ->
            if (finished) return@setOnImageAvailableListener
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            finished = true
            handler.removeCallbacks(timeout)
            r.setOnImageAvailableListener(null, null)
            val encoded = encodeImageToJpegDataUrl(img, quality)
            if (cont.isActive) cont.resume(encoded)
        }, handler)

        cont.invokeOnCancellation {
            finished = true
            handler.removeCallbacks(timeout)
            imgReader.setOnImageAvailableListener(null, null)
        }
    }

    private fun encodeImageToJpegDataUrl(image: Image, quality: Int): String? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * targetWidth
            val rawBitmap = Bitmap.createBitmap(
                targetWidth + rowPadding / pixelStride,
                targetHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, targetWidth, targetHeight)
            rawBitmap.recycle()

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            cropped.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun cleanupDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        reader = null
    }

    fun release() {
        isAlive = false
        cleanupDisplay()
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
    }
}
