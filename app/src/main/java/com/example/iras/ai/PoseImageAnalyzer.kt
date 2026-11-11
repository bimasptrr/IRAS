package com.example.iras.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

class PoseImageAnalyzer(
    private val poseLandmarker: PoseLandmarker,
    private val onFrameGeometry: (width: Int, height: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val rotationMatrix = Matrix()

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()

            // Putar frame ke orientasi layar agar koordinat landmark sejajar
            // dengan gambar kamera yang ditampilkan PreviewView.
            val uprightBitmap = if (rotationDegrees == 0) {
                bitmap
            } else {
                rotationMatrix.reset()
                rotationMatrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    rotationMatrix,
                    true
                )
            }

            // MediaPipe menormalkan koordinat terhadap bitmap yang diputar ini.
            onFrameGeometry(uprightBitmap.width, uprightBitmap.height)

            val mpImage = BitmapImageBuilder(uprightBitmap).build()
            val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000

            poseLandmarker.detectAsync(mpImage, timestampMs)
        } finally {
            imageProxy.close()
        }
    }
}
