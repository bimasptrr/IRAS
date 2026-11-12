package com.example.iras.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merekam video kamera Monitor (tanpa suara) ke file sementara di cache aplikasi.
 * Video baru masuk ke Galeri jika pengguna menekan Simpan (lihat [saveVideoToGallery]).
 */
class MonitorRecorder {

    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
    )

    private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    /** Mulai merekam. [onFinished] dipanggil setelah file selesai ditulis (null bila gagal/kosong). */
    fun start(context: Context, onFinished: (File?) -> Unit) {
        if (recording != null) return
        val appContext = context.applicationContext
        val file = File(appContext.cacheDir, "rekaman_${System.currentTimeMillis()}.mp4")
        recording = videoCapture.output
            .prepareRecording(appContext, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(appContext)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (file.exists() && file.length() > 0L) {
                        onFinished(file)
                    } else {
                        file.delete()
                        onFinished(null)
                    }
                }
            }
    }

    fun stop() {
        recording?.stop()
    }
}

/** Hasil penyimpanan: [uri] dipakai untuk memutar video, [where] teks lokasi untuk pengguna. */
data class SavedVideo(val uri: Uri?, val where: String)

/** Menyalin video sementara ke Galeri (Movies/IRAS). Mengembalikan null jika gagal. */
suspend fun saveVideoToGallery(context: Context, source: File): SavedVideo? =
    withContext(Dispatchers.IO) {
        val name = "IRAS_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/IRAS")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                try {
                    val out = resolver.openOutputStream(uri)
                    if (out == null) {
                        resolver.delete(uri, null, null)
                        return@withContext null
                    }
                    out.use { o -> source.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    source.delete()
                    SavedVideo(uri, "Galeri > folder Movies/IRAS ($name)")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    null
                }
            } else {
                // Android 8-9: simpan di folder aplikasi (tidak muncul di Galeri)
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: return@withContext null
                dir.mkdirs()
                val target = File(dir, name)
                source.copyTo(target, overwrite = true)
                source.delete()
                SavedVideo(null, "Android/data/${context.packageName}/files/Movies/$name")
            }
        } catch (e: Exception) {
            null
        }
    }

/** Memutar video di aplikasi pemutar/Galeri bawaan HP. */
fun playVideo(context: Context, uri: Uri) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (_: Exception) {
        // Tidak ada aplikasi pemutar video
    }
}