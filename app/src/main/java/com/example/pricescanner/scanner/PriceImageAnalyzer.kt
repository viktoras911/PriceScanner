package com.example.pricescanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * CameraX -> physical ROI crop -> ML Kit OCR -> element-level parser -> multi-frame stabilizer.
 *
 * The ROI is mapped using CameraX crop/rotation metadata rather than hand-written FILL_CENTER
 * aspect-ratio calculations. ML Kit never receives the rest of the shelf image.
 */
class PriceImageAnalyzer(
    private val parser: PriceParser = PriceParser(),
    private val stabilizer: PriceStabilizer = PriceStabilizer(),
    private val onCandidate: (PriceReading?) -> Unit = {},
    private val onStablePrice: (StablePrice) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var locked = false

    @Volatile
    private var processing = false

    @Volatile
    private var previewGeometry: PreviewGeometry? = null

    override fun analyze(imageProxy: ImageProxy) {
        if (locked || processing) {
            imageProxy.close()
            return
        }

        processing = true

        var fullForCleanup: Bitmap? = null
        var roiForCleanup: Bitmap? = null

        try {
            val fullBitmap = imageProxy.toBitmap()
            fullForCleanup = fullBitmap
            val roi = mapScanTargetToImage(imageProxy)
            if (roi == null) {
                onCandidate(null)
                stabilizer.add(null)
                imageProxy.close()
                processing = false
                fullBitmap.recycleSafely()
                return
            }
            val safeRoi = clampRect(roi, fullBitmap.width, fullBitmap.height)

            if (safeRoi.width() < 8 || safeRoi.height() < 8) {
                onCandidate(null)
                imageProxy.close()
                processing = false
                fullBitmap.recycle()
                return
            }

            val croppedBitmap = Bitmap.createBitmap(
                fullBitmap,
                safeRoi.left,
                safeRoi.top,
                safeRoi.width(),
                safeRoi.height()
            )
            roiForCleanup = croppedBitmap

            val rotation = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromBitmap(croppedBitmap, rotation)

            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    if (locked) return@addOnSuccessListener

                    val frameSize = if (rotation == 90 || rotation == 270) {
                        FrameSize(croppedBitmap.height, croppedBitmap.width)
                    } else {
                        FrameSize(croppedBitmap.width, croppedBitmap.height)
                    }

                    val reading = parser.parse(toTokens(text), frameSize)
                    onCandidate(reading)

                    val stable = stabilizer.add(reading)
                    if (stable != null && !locked) {
                        locked = true
                        onStablePrice(stable)
                    }
                }
                .addOnFailureListener {
                    onCandidate(null)
                    stabilizer.add(null)
                }
                .addOnCompleteListener {
                    if (croppedBitmap !== fullBitmap) croppedBitmap.recycleSafely()
                    fullBitmap.recycleSafely()
                    imageProxy.close()
                    processing = false
                }
        } catch (_: Throwable) {
            if (roiForCleanup !== fullForCleanup) roiForCleanup.recycleSafely()
            fullForCleanup.recycleSafely()
            imageProxy.close()
            processing = false
            onCandidate(null)
            stabilizer.add(null)
        }
    }

    fun updatePreviewGeometry(previewWidth: Int, previewHeight: Int, targetRect: RectF) {
        if (previewWidth <= 0 || previewHeight <= 0 || targetRect.width() <= 0f || targetRect.height() <= 0f) return
        previewGeometry = PreviewGeometry(previewWidth, previewHeight, RectF(targetRect))
    }

    fun reset() {
        locked = false
        stabilizer.reset()
    }

    fun lock() {
        locked = true
    }

    override fun close() {
        locked = true
        recognizer.close()
    }

    /** Maps the UI-thread snapshot of the green target rectangle into the ImageProxy buffer. */
    private fun mapScanTargetToImage(imageProxy: ImageProxy): Rect? {
        val geometry = previewGeometry ?: return null

        val targetInPreview = RectF(geometry.targetRect)
        val imageToPreview = getImageToPreviewMatrix(imageProxy, geometry)
        val previewToImage = Matrix()
        if (!imageToPreview.invert(previewToImage)) return null

        previewToImage.mapRect(targetInPreview)

        val crop = imageProxy.cropRect
        val paddingX = targetInPreview.width() * 0.025f
        val paddingY = targetInPreview.height() * 0.06f

        return Rect(
            floor(targetInPreview.left - paddingX).toInt().coerceAtLeast(crop.left),
            floor(targetInPreview.top - paddingY).toInt().coerceAtLeast(crop.top),
            ceil(targetInPreview.right + paddingX).toInt().coerceAtMost(crop.right),
            ceil(targetInPreview.bottom + paddingY).toInt().coerceAtMost(crop.bottom)
        ).takeIf { it.width() > 1 && it.height() > 1 }
    }

    /** CameraX crop/rotation mapping from ImageAnalysis coordinates to the PreviewView snapshot. */
    private fun getImageToPreviewMatrix(imageProxy: ImageProxy, geometry: PreviewGeometry): Matrix {
        val cropRect = imageProxy.cropRect
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val matrix = Matrix()

        val source = floatArrayOf(
            cropRect.left.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.bottom.toFloat(),
            cropRect.left.toFloat(), cropRect.bottom.toFloat()
        )

        val destination = floatArrayOf(
            0f, 0f,
            geometry.width.toFloat(), 0f,
            geometry.width.toFloat(), geometry.height.toFloat(),
            0f, geometry.height.toFloat()
        )

        val shiftOffset = rotationDegrees / 90 * 2
        val temp = destination.clone()
        for (toIndex in source.indices) {
            val fromIndex = (toIndex + shiftOffset) % source.size
            destination[toIndex] = temp[fromIndex]
        }

        matrix.setPolyToPoly(source, 0, destination, 0, 4)
        return matrix
    }

    private fun toTokens(text: Text): List<OcrToken> {
        val tokens = mutableListOf<OcrToken>()

        text.textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEachIndexed { lineIndex, line ->
                val lineId = "$blockIndex:$lineIndex"
                line.elements.forEach { element ->
                    val rect = element.boundingBox ?: return@forEach
                    tokens += OcrToken(
                        text = element.text.trim(),
                        box = Box(rect.left, rect.top, rect.right, rect.bottom),
                        lineText = line.text.trim(),
                        lineId = lineId
                    )
                }
            }
        }

        return tokens
    }

    private fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, max(0, width - 1))
        val top = rect.top.coerceIn(0, max(0, height - 1))
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun Bitmap?.recycleSafely() {
        if (this != null && !isRecycled) recycle()
    }

    private data class PreviewGeometry(
        val width: Int,
        val height: Int,
        val targetRect: RectF
    )

}
