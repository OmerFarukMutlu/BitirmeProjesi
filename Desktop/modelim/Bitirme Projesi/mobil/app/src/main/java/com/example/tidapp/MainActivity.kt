package com.example.tidapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnLetter: Button
    private lateinit var btnWord: Button
    private lateinit var btnNumber: Button

    private lateinit var llAdayHarflerContainer: LinearLayout
    private lateinit var tvOlusturulanKelime: TextView

    private lateinit var btnDeleteLastChar: Button
    private lateinit var btnSaveWord: Button
    private lateinit var btnExportCloudJson: Button
    private lateinit var tvSavedRecords: TextView
    private lateinit var svSavedList: ScrollView

    private var kelimeHafizasi = ""
    private var kaydedilenMetinler = ArrayList<String>()

    private lateinit var letterInterpreter: Interpreter
    private lateinit var wordInterpreter: Interpreter
    private lateinit var numberInterpreter: Interpreter
    private lateinit var letterClasses: List<String>
    private lateinit var wordClasses: List<String>
    private lateinit var numberClasses: List<String>
    private lateinit var featureExtractor: HandFeatureExtractor

    private lateinit var cameraExecutor: ExecutorService
    private var currentMode = "letter"   // "letter" | "word" | "number"

    private val PREDICT_INTERVAL_MS    = 800L
    private val CONFIDENCE_THRESHOLD   = 0.55f
    private var lastPredictTime        = 0L
    private var isProcessing           = false

    private var sonButonEtkilesimZamani = 0L
    private val BUTON_TUTMA_SURESI_MS   = 8000L

    private var kameraMolaBitisZamani   = 0L
    private val KAMERA_MOLA_SURESI_MS   = 2500L

    companion object {
        private const val TAG              = "TIDApp"
        private const val CAMERA_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView            = findViewById(R.id.previewView)
        btnLetter              = findViewById(R.id.btnLetter)
        btnWord                = findViewById(R.id.btnWord)
        btnNumber              = findViewById(R.id.btnNumber)
        llAdayHarflerContainer = findViewById(R.id.llAdayHarflerContainer)
        tvOlusturulanKelime    = findViewById(R.id.tvOluşturulanKelime)
        btnDeleteLastChar      = findViewById(R.id.btnDeleteLastChar)
        btnSaveWord            = findViewById(R.id.btnSaveWord)
        btnExportCloudJson     = findViewById(R.id.btnExportCloudJson)
        tvSavedRecords         = findViewById(R.id.tvSavedRecords)
        svSavedList            = findViewById(R.id.svSavedList)

        btnLetter.setOnClickListener { setMode("letter") }
        btnWord.setOnClickListener   { setMode("word") }
        btnNumber.setOnClickListener { setMode("number") }

        btnDeleteLastChar.setOnClickListener {
            if (kelimeHafizasi.isNotEmpty()) {
                kelimeHafizasi = kelimeHafizasi.substring(0, kelimeHafizasi.length - 1)
                tvOlusturulanKelime.text = kelimeHafizasi
            }
            sonButonEtkilesimZamani = System.currentTimeMillis()
        }

        btnSaveWord.setOnClickListener {
            val mevcutMetin = tvOlusturulanKelime.text.toString().trim()
            if (mevcutMetin.isNotEmpty()) {
                kaydedilenMetinler.add(mevcutMetin)
                guncelleEkranListesi()
                kelimeHafizasi = ""
                tvOlusturulanKelime.text = ""
                sonButonEtkilesimZamani = 0L
                kameraMolaBitisZamani = 0L
                llAdayHarflerContainer.removeAllViews()
                svSavedList.post { svSavedList.fullScroll(ScrollView.FOCUS_DOWN) }
            } else {
                Toast.makeText(this, "⚠️ Önce kelime oluşturup listeye ekleyin!", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportCloudJson.setOnClickListener {
            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

            if (currentUserId == null) {
                Toast.makeText(this, "❌ Oturum Açık Kullanıcı Bulunamadı! Tekrar Giriş Yapın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (kaydedilenMetinler.isEmpty()) {
                Toast.makeText(this, "⚠️ Buluta gönderilecek veri yok! Önce disketle (💾) ekleyin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "☁️ Bulut sunucusuna bağlanılıyor...", Toast.LENGTH_SHORT).show()

            try {
                val dataMap = HashMap<String, Any>()
                dataMap["kullanici_id"] = currentUserId
                dataMap["kayit_zamani_ms"] = System.currentTimeMillis()
                dataMap["veriler"] = kaydedilenMetinler

                val databaseRef = FirebaseDatabase.getInstance("https://bitirmeprojesi-df808-default-rtdb.europe-west1.firebasedatabase.app/")
                    .getReference("kaydedilen_metinler")

                databaseRef.child(currentUserId).push().setValue(dataMap)
                    .addOnSuccessListener {
                        Toast.makeText(this, "✅ Veriler JSON yapısında buluta başarıyla yedeklendi!", Toast.LENGTH_LONG).show()
                        kaydedilenMetinler.clear()
                        tvSavedRecords.text = ""
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "❌ Bulut kaydı başarısız: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        Log.e(TAG, "Firebase hatası: ", e)
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "💥 Sistem hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        loadModels()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun guncelleEkranListesi() {
        val sb = StringBuilder()
        for (index in kaydedilenMetinler.indices) {
            var kelimeText = kaydedilenMetinler[index]
            if (kaydedilenMetinler.size >= 2 && index == 0 && !kelimeText.endsWith(".")) {
                kelimeText += "."
            }
            sb.append("${index + 1}. $kelimeText\n")
        }
        tvSavedRecords.text = sb.toString().trim()
    }

    private fun loadModels() {
        try {
            // ✅ Harf modeli: 2 EL (156-dim)
            letterInterpreter = Interpreter(loadModelFile("letter_model_2hands.tflite"))
            // Kelime ve sayı: TEK EL (78-dim) — eski modeller
            wordInterpreter   = Interpreter(loadModelFile("word_model.tflite"))
            numberInterpreter = Interpreter(loadModelFile("number_model.tflite"))

            letterClasses  = loadClasses("letter_classes.json")
            wordClasses    = loadClasses("word_classes.json")
            numberClasses  = loadClasses("number_classes.json")

            featureExtractor = HandFeatureExtractor(this)

            Log.d(TAG, "✅ Modeller yüklendi (Harf: 2 el, Kelime/Sayı: tek el)")
            Toast.makeText(this, "✅ Modeller yüklendi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model yükleme hatası: ${e.message}", e)
            Toast.makeText(this, "❌ HATA: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadModelFile(fileName: String): ByteBuffer {
        val afd = assets.openFd(fileName)
        return FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    private fun loadClasses(fileName: String): List<String> {
        val json = assets.open(fileName).bufferedReader().readText()
        return try {
            // Önce düz JSON array dene: ["A", "B", "C", ...]
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            // Eski format: {"classes": ["A", "B", ...]}
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("classes")
            (0 until arr.length()).map { arr.getString(it) }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Kamera hatası: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastPredictTime < PREDICT_INTERVAL_MS || isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true
        lastPredictTime = now
        try {
            val bitmap = toBitmap(imageProxy)
            imageProxy.close()
            predict(bitmap)
        } catch (e: Exception) {
            imageProxy.close()
        } finally {
            isProcessing = false
        }
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return if (rotated.config == Bitmap.Config.ARGB_8888) rotated
        else rotated.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun predict(bitmap: Bitmap) {
        // ✅ Moda göre feature boyutu ve extractor seç
        val features: FloatArray?
        val featureSize: Int

        if (currentMode == "letter") {
            // Harf modu: 2 el → 156-dim
            features = featureExtractor.extract2Hands(bitmap)
            featureSize = 156
        } else {
            // Kelime/Sayı modu: tek el → 78-dim
            features = featureExtractor.extract(bitmap)
            featureSize = 78
        }

        if (features == null) return

        val interpreter = when (currentMode) {
            "word"   -> wordInterpreter
            "number" -> numberInterpreter
            else     -> letterInterpreter
        }
        val classes = when (currentMode) {
            "word"   -> wordClasses
            "number" -> numberClasses
            else     -> letterClasses
        }

        val input = ByteBuffer.allocateDirect(featureSize * 4).apply {
            order(ByteOrder.nativeOrder())
            features.forEach { putFloat(it) }
        }

        val output = Array(1) { FloatArray(classes.size) }
        interpreter.run(input, output)
        val proba = output[0]

        val top3 = proba.indices
            .sortedByDescending { proba[it] }
            .take(3)
            .map { Pair(classes[it], proba[it]) }

        runOnUiThread { createAdayHarfButonlari(top3) }
    }

    private fun createAdayHarfButonlari(top3: List<Pair<String, Float>>) {
        val simdi = System.currentTimeMillis()
        if (simdi < kameraMolaBitisZamani) return
        if (simdi - sonButonEtkilesimZamani < BUTON_TUTMA_SURESI_MS) return

        llAdayHarflerContainer.removeAllViews()
        if (top3.isEmpty()) return

        for (aday in top3) {
            val btnAday = Button(this).apply {
                text = if (aday.first.lowercase() == "space") "[ BOŞLUK ]"
                else "${aday.first} (%${"%.0f".format(aday.second * 100)})"
                textSize = 13f
                isAllCaps = false
                setPadding(24, 0, 24, 0)
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#00BCD4"))

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
                params.setMargins(0, 0, 16, 0)
                layoutParams = params

                setOnClickListener {
                    if (aday.first.lowercase() == "space") {
                        kelimeHafizasi += " "
                    } else {
                        if (currentMode == "word") {
                            kelimeHafizasi += (if (kelimeHafizasi.isNotEmpty()) " " else "") + aday.first
                        } else {
                            kelimeHafizasi += aday.first
                        }
                    }
                    tvOlusturulanKelime.text = kelimeHafizasi
                    llAdayHarflerContainer.removeAllViews()
                    kameraMolaBitisZamani = System.currentTimeMillis() + KAMERA_MOLA_SURESI_MS
                    sonButonEtkilesimZamani = 0L
                }
            }
            llAdayHarflerContainer.addView(btnAday)
        }
    }

    private fun setMode(mode: String) {
        currentMode = mode

        val active   = "#00BCD4"
        val inactive = "#555555"
        val activeText   = "#000000"
        val inactiveText = "#FFFFFF"

        btnLetter.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (mode == "letter") active else inactive))
        btnLetter.setTextColor(
            android.graphics.Color.parseColor(if (mode == "letter") activeText else inactiveText))

        btnWord.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (mode == "word") active else inactive))
        btnWord.setTextColor(
            android.graphics.Color.parseColor(if (mode == "word") activeText else inactiveText))

        btnNumber.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (mode == "number") active else inactive))
        btnNumber.setTextColor(
            android.graphics.Color.parseColor(if (mode == "number") activeText else inactiveText))

        sonButonEtkilesimZamani = 0L
        kameraMolaBitisZamani = 0L
        llAdayHarflerContainer.removeAllViews()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Kamera izni gerekli!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::featureExtractor.isInitialized) featureExtractor.close()
    }
}