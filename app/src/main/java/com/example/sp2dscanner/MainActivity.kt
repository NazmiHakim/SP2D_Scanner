package com.example.sp2dscanner // Pastikan package ini sesuai project Anda

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var btnCopy: Button
    private lateinit var cameraExecutor: ExecutorService

    // Variabel untuk menyimpan hasil gabungan
    private var finalCombinedText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvResult = findViewById(R.id.tvResult)
        btnCopy = findViewById(R.id.btnCopy)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnCopy.setOnClickListener {
            if (finalCombinedText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SP2D Data", finalCombinedText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Teks disalin ke clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Belum ada data yang terdeteksi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, SP2DAnalyzer { result ->
                        runOnUiThread {
                            if (result.isNotEmpty()) {
                                finalCombinedText = result
                                tvResult.text = result
                            }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("CameraX", "Gagal memuat kamera", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class SP2DAnalyzer(private val listener: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        // Proses teks hasil OCR
                        val extracted = processSP2DText(visionText.text)
                        if (extracted.isNotEmpty()) {
                            listener(extracted)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        /**
         * LOGIKA BARU: Lebih pintar menangkap Nomor SP2D dan Keperluan multiline
         */
        private fun processSP2DText(rawText: String): String {
            var nomorSP2D = ""
            var keperluan = ""

            // 1. CARI NOMOR SP2D
            // Regex ini mencari angka, diikuti /SP2D/, diikuti karakter kode lainnya
            // Menggunakan \s* agar jika OCR membaca "05861 / SP2D /..." (ada spasi), tetap terdeteksi
            val regexNomor = Pattern.compile("(\\d{4,6}\\s*/\\s*SP2D\\s*/\\s*[A-Za-z0-9./-]+)", Pattern.CASE_INSENSITIVE)
            val matcherNomor = regexNomor.matcher(rawText)

            if (matcherNomor.find()) {
                // Hapus spasi berlebih dalam nomor hasil deteksi agar rapi
                nomorSP2D = matcherNomor.group(1)?.replace("\\s".toRegex(), "") ?: ""
            }

            // 2. CARI KEPERLUAN UNTUK
            // Kita ganti semua baris baru (\n) dengan spasi dulu agar teks menjadi satu baris panjang
            // Ini memudahkan pengambilan deskripsi yang panjang.
            val flatText = rawText.replace("\n", " ").replace("\r", " ")

            // Cari posisi kata kunci "Keperluan Untuk"
            val startKeyword = "Keperluan Untuk"
            val startIndex = flatText.indexOf(startKeyword, ignoreCase = true)

            if (startIndex != -1) {
                // Kita cari batas akhirnya. Biasanya setelah deskripsi keperluan,
                // akan ada Header Tabel seperti "NO.", "REKENING", atau "URAIAN"
                // Kita cari posisi kata-kata tersebut yang muncul SETELAH "Keperluan Untuk"
                val textAfterKeyword = flatText.substring(startIndex + startKeyword.length)

                // Cari kata pemutus (Stop Words)
                val stopWords = listOf("NO.", "REKENING", "URAIAN", "JUMLAH", "Potongan")
                var minStopIndex = textAfterKeyword.length

                for (word in stopWords) {
                    val idx = textAfterKeyword.indexOf(word, ignoreCase = false) // Case sensitive untuk "NO." agar tidak tertukar kata lain
                    if (idx != -1 && idx < minStopIndex) {
                        minStopIndex = idx
                    }
                }

                // Ambil teks di antaranya
                var rawKeperluan = textAfterKeyword.substring(0, minStopIndex)

                // Bersihkan karakter sampah (seperti titik dua ':', spasi berlebih)
                rawKeperluan = rawKeperluan.replace(":", "").trim()

                // Jika hasil bersihnya cukup panjang (valid), simpan
                if (rawKeperluan.length > 5) {
                    keperluan = rawKeperluan
                }
            }

            // 3. GABUNGKAN HASIL
            return if (nomorSP2D.isNotEmpty() && keperluan.isNotEmpty()) {
                "$nomorSP2D $keperluan"
            } else {
                "" // Jangan update UI kalau belum ketemu lengkap
            }
        }
    }

    // --- Bagian Permission (Tidak berubah) ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startCamera() else Toast.makeText(this, "Izin ditolak", Toast.LENGTH_SHORT).show()
        }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}