package com.example.iras.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.iras.ai.PoseEstimator
import com.example.iras.ai.PoseImageAnalyzer
import com.example.iras.data.IrasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Preview kamera perangkat (CameraX) yang terintegrasi dengan MediaPipe Pose Estimation.
 */
@Composable
fun CameraPreview(
    viewModel: IrasViewModel,
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // Inisialisasi PoseEstimator (MediaPipe)
    val poseEstimator = remember { PoseEstimator(context, viewModel) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var poseReady by remember { mutableStateOf(false) }

    // Preview dan ImageAnalysis harus memakai rasio aspek yang sama agar crop
    // FILL_CENTER di layar identik dengan frame yang dianalisis MediaPipe.
    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val analysisUseCase = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    // Kamera depan ditampilkan mirror oleh PreviewView; overlay perlu tahu.
    LaunchedEffect(useFrontCamera) { poseEstimator.mirrored = useFrontCamera }

    LaunchedEffect(granted) {
        if (granted && poseEstimator.poseLandmarker == null) {
            try {
                // Memuat model tidak dilakukan di main thread agar UI tidak macet.
                withContext(Dispatchers.IO) { poseEstimator.setup() }
                poseReady = true
            } catch (e: Exception) {
                viewModel.onError(e.message ?: "Gagal memuat model MediaPipe.")
            }
        }
    }

    // Analyzer dipasang setelah model MediaPipe siap. Use case diingat agar
    // tidak bergantung pada urutan pembuatan AndroidView vs LaunchedEffect.
    LaunchedEffect(poseReady) {
        val landmarker = poseEstimator.poseLandmarker
        if (poseReady && landmarker != null) {
            analysisUseCase.setAnalyzer(
                cameraExecutor,
                PoseImageAnalyzer(landmarker, poseEstimator::updateFrameGeometry)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisUseCase.clearAnalyzer()
            poseEstimator.close()
            cameraExecutor.shutdown()
        }
    }

    if (!granted) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Izin kamera diperlukan untuk Live CV Analysis",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Izinkan Kamera")
            }
        }
        return
    }

    // Preview dibuat sekali; kamera (depan/belakang) di-bind ulang saat pilihan berubah.
    val preview = remember {
        Preview.Builder().setResolutionSelector(resolutionSelector).build()
    }
    val boundCamera = remember { arrayOfNulls<Boolean>(1) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Samakan orientasi frame analisis dengan orientasi preview.
            previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                previewView.display?.let { display ->
                    if (analysisUseCase.targetRotation != display.rotation) {
                        analysisUseCase.targetRotation = display.rotation
                    }
                }
            }
            analysisUseCase.targetRotation =
                previewView.display?.rotation ?: Surface.ROTATION_0
            previewView
        },
        update = { previewView ->
            if (boundCamera[0] != useFrontCamera) {
                boundCamera[0] = useFrontCamera
                val ctx = previewView.context
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val wantedFront = boundCamera[0] == true
                    val selector = if (wantedFront) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        if (!provider.hasCamera(selector)) return@addListener
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            preview,
                            analysisUseCase
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )
}