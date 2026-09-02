package com.hujunhan.hamsterwheeltracker.camera

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Copies CameraX RGBA_8888 output into a reusable OpenCV Mat. */
class RgbaMatReader {
    private val rgba = Mat()
    private var packed = ByteArray(0)

    fun read(image: ImageProxy): Mat {
        require(image.planes.isNotEmpty()) { "RGBA ImageProxy has no planes" }
        val plane = image.planes[0]
        require(plane.pixelStride == 4) { "Expected RGBA pixelStride=4, got ${plane.pixelStride}" }

        val width = image.width
        val height = image.height
        val packedRowBytes = width * 4
        val requiredBytes = packedRowBytes * height
        if (packed.size != requiredBytes) packed = ByteArray(requiredBytes)

        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        if (plane.rowStride == packedRowBytes) {
            buffer.get(packed, 0, requiredBytes)
        } else {
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(packed, row * packedRowBytes, packedRowBytes)
            }
        }

        rgba.create(height, width, CvType.CV_8UC4)
        rgba.put(0, 0, packed)
        return rgba
    }

    fun close() = rgba.release()
}
