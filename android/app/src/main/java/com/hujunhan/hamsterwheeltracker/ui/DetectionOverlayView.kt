package com.hujunhan.hamsterwheeltracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import kotlin.math.min

class DetectionOverlayView(context: Context) : View(context) {
    @Volatile
    private var frameResult: MarkerFrameResult? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val annulusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val rejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    fun update(result: MarkerFrameResult) {
        frameResult = result
        postInvalidateOnAnimation()
    }

    fun viewToFrame(x: Float, y: Float): PointF? {
        val result = frameResult ?: return null
        val transform = transformFor(result) ?: return null
        val rotatedX = (x - transform.offsetX) / transform.scale
        val rotatedY = (y - transform.offsetY) / transform.scale
        if (rotatedX !in 0f..transform.rotatedWidth || rotatedY !in 0f..transform.rotatedHeight) return null

        val fw = result.frameWidth.toFloat()
        val fh = result.frameHeight.toFloat()
        return when (normalizeRotation(result.rotationDegrees)) {
            0 -> PointF(rotatedX, rotatedY)
            90 -> PointF(rotatedY, fh - rotatedX)
            180 -> PointF(fw - rotatedX, fh - rotatedY)
            270 -> PointF(fw - rotatedY, rotatedX)
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = frameResult ?: return
        val transform = transformFor(result) ?: return
        val calibration = result.resolvedCalibration

        val center = mapFramePoint(calibration.centerX, calibration.centerY, result, transform)
        val scale = transform.scale
        canvas.drawCircle(center.x, center.y, calibration.wheelRadiusPx * scale, wheelPaint)
        canvas.drawCircle(
            center.x,
            center.y,
            (calibration.expectedMarkerRadiusPx - calibration.radiusTolerancePx).coerceAtLeast(0f) * scale,
            annulusPaint,
        )
        canvas.drawCircle(
            center.x,
            center.y,
            (calibration.expectedMarkerRadiusPx + calibration.radiusTolerancePx) * scale,
            annulusPaint,
        )

        for (candidate in result.candidates) {
            if (candidate.accepted) continue
            val point = mapFramePoint(candidate.xPx, candidate.yPx, result, transform)
            canvas.drawCircle(point.x, point.y, 9f, rejectedPaint)
        }

        val detection = result.detection
        if (detection != null) {
            val point = mapFramePoint(detection.xPx, detection.yPx, result, transform)
            canvas.drawCircle(point.x, point.y, 15f, markerPaint)
            canvas.drawLine(point.x - 22f, point.y, point.x + 22f, point.y, markerPaint)
            canvas.drawLine(point.x, point.y - 22f, point.x, point.y + 22f, markerPaint)
            canvas.drawText(
                "marker %.2f".format(detection.score),
                point.x + 18f,
                point.y - 18f,
                textPaint,
            )
        }
    }

    private fun transformFor(result: MarkerFrameResult): ViewTransform? {
        if (width == 0 || height == 0) return null
        val rotation = normalizeRotation(result.rotationDegrees)
        val rotatedWidth = if (rotation == 90 || rotation == 270) result.frameHeight.toFloat() else result.frameWidth.toFloat()
        val rotatedHeight = if (rotation == 90 || rotation == 270) result.frameWidth.toFloat() else result.frameHeight.toFloat()
        val scale = min(width / rotatedWidth, height / rotatedHeight)
        return ViewTransform(
            scale = scale,
            offsetX = (width - rotatedWidth * scale) / 2f,
            offsetY = (height - rotatedHeight * scale) / 2f,
            rotatedWidth = rotatedWidth,
            rotatedHeight = rotatedHeight,
        )
    }

    private fun mapFramePoint(
        x: Float,
        y: Float,
        result: MarkerFrameResult,
        transform: ViewTransform,
    ): PointF {
        val fw = result.frameWidth.toFloat()
        val fh = result.frameHeight.toFloat()
        val rotated = when (normalizeRotation(result.rotationDegrees)) {
            0 -> PointF(x, y)
            90 -> PointF(fh - y, x)
            180 -> PointF(fw - x, fh - y)
            270 -> PointF(y, fw - x)
            else -> PointF(x, y)
        }
        return PointF(
            transform.offsetX + rotated.x * transform.scale,
            transform.offsetY + rotated.y * transform.scale,
        )
    }

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private data class ViewTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rotatedWidth: Float,
        val rotatedHeight: Float,
    )
}
