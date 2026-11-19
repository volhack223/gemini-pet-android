package com.geminipet.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 内存级单帧截屏辅助类（阅后即焚，0 字节写入手机存储）
 */
class ScreenCaptureHelper(
    private val context: Context,
    private val projection: MediaProjection
) {
    private val handler = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 系统停止投屏授权时由系统自动回收
        }
    }

    init {
        try {
            projection.registerCallback(projectionCallback, handler)
        } catch (_: Exception) {
        }
    }

    suspend fun captureJpegDataUrl(quality: Int = 48): String? = suspendCancellableCoroutine { cont ->
        val dm = context.resources.displayMetrics
        val targetWidth = 720
        val scale = targetWidth.toFloat() / dm.widthPixels.coerceAtLeast(1)
        val targetHeight = (dm.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = dm.densityDpi

        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var finished = false

        fun cleanup() {
            try { display?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            display = null
            reader = null
        }

        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                cleanup()
                if (cont.isActive) cont.resume(null)
            }
        }
        handler.postDelayed(timeoutRunnable, 2200)

        try {
            val imgReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            reader = imgReader
            imgReader.setOnImageAvailableListener({ r ->
                if (finished) return@setOnImageAvailableListener
                val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                var dataUrl: String? = null
                try {
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
                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    dataUrl = "data:image/jpeg;base64,$b64"
                } catch (_: Exception) {
                    dataUrl = null
                } finally {
                    image.close()
                    cleanup()
                }
                if (cont.isActive) cont.resume(dataUrl)
            }, handler)

            display = projection.createVirtualDisplay(
                "GeminiPetCapture",
                targetWidth,
                targetHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imgReader.surface,
                null,
                handler
            )
        } catch (_: Exception) {
            finished = true
            handler.removeCallbacks(timeoutRunnable)
            cleanup()
            if (cont.isActive) cont.resume(null)
        }

        cont.invokeOnCancellation {
            finished = true
            handler.removeCallbacks(timeoutRunnable)
            cleanup()
        }
    }

    fun release() {
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
    }
}
