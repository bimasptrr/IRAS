package com.example.iras.ai

import android.content.Context
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions

class PoseEstimator(private val context: Context, private val listener: PoseListener) {

    var poseLandmarker: PoseLandmarker? = null
        private set

    // Geometri frame terakhir yang dikirim analyzer. Dipakai agar overlay bisa
    // memetakan landmark dengan crop/rotasi yang sama seperti preview kamera.
    @Volatile private var frameWidth: Int = 0
    @Volatile private var frameHeight: Int = 0

    /** true bila frame berasal dari kamera depan (PreviewView me-mirror tampilannya). */
    @Volatile var mirrored: Boolean = false

    interface PoseListener {
        fun onPoseDetected(frame: PoseFrame, timestampMs: Long)
        fun onError(error: String)
    }

    fun updateFrameGeometry(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    fun setup() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinPoseDetectionConfidence(0.7f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                // Mengambil timestampMs melalui method result.timestampMs()
                val timestampMs = result.timestampMs()
                if (result.landmarks().isNotEmpty()) {
                    val rawLandmarks = result.landmarks()[0]
                    val mappedLandmarks = mutableMapOf<Int, Point3D>()

                    for (id in rawLandmarks.indices) {
                        val lm: NormalizedLandmark = rawLandmarks[id]
                        val visibility = if (lm.visibility().isPresent) lm.visibility().get() else 0f
                        mappedLandmarks[id] = Point3D(lm.x(), lm.y(), lm.z(), visibility)
                    }
                    listener.onPoseDetected(
                        PoseFrame(
                            landmarks = mappedLandmarks,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                            mirrored = mirrored
                        ),
                        timestampMs
                    )
                } else {
                    listener.onError("Pemanah tidak terdeteksi di dalam frame.")
                }
            }
            .setErrorListener { error ->
                listener.onError(error.message ?: "Terjadi kesalahan pada inisialisasi MediaPipe.")
            }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
