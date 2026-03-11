package com.example.isekichadet

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class YoloRecordActivity : AppCompatActivity() {

    private lateinit var edtNoProduksi: EditText
    private lateinit var edtTglProduksi: EditText
    private lateinit var edtNoChasisKanban: EditText
    private lateinit var edtNoChasisScan: EditText
    private lateinit var statusBadge: TextView
    private lateinit var cameraImage: ImageView
    private lateinit var captureImgBtn: Button
    private lateinit var scanQRBtn: Button
    private lateinit var submitBtn: Button
    private lateinit var validationErrorDiv: LinearLayout
    private lateinit var validationErrorText: TextView

    private var scannedImageUri: Uri? = null
    private var currentPhotoPath: String? = null

    private val client = OkHttpClient()

    // YOLOv8 TFLite properties
    private lateinit var tflite: Interpreter
    private lateinit var labels: List<String>
    private val modelPath = "model/chasis_model.tflite"
    private val labelPath = "model/labels.txt"
    private val confidenceThreshold = 0.5f

    companion object {
        private const val CHECK_PREREQUISITES_URL = "http://192.168.173.207/iseki_chadet/public/api/records/check-prerequisites"
    }

    // Document Scanner
    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(false)
        .setPageLimit(1)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    private val scanner = GmsDocumentScanning.getClient(scannerOptions)

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pages?.let { pages ->
                if (pages.isNotEmpty()) {
                    val imageUri = pages[0].imageUri
                    scannedImageUri = imageUri
                    
                    // Save path for submission
                    currentPhotoPath = getFilePathFromUri(imageUri)
                    
                    // Show scanned image in preview
                    cameraImage.setImageURI(imageUri)
                    
                    // Run YOLOv8 inference instead of OCR
                    runInference(imageUri)
                }
            }
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val parts = result.contents.split(";")
            if (parts.size > 4) {
                edtNoProduksi.setText(parts[0])
                edtTglProduksi.setText(parts[1])
                edtNoChasisKanban.setText(parts[4])
                checkPrerequisites(parts[0].trim(), parts[1])
                updateBadge()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yolo_record)

        initTFLite()
        initUI()
        setupBottomNav()
    }

    private fun initTFLite() {
        try {
            val model = FileUtil.loadMappedFile(this, modelPath)
            val options = Interpreter.Options()
            tflite = Interpreter(model, options)

            // Load labels (format: "index label")
            val labelLines = FileUtil.loadLabels(this, labelPath)
            labels = labelLines.map { it.split(" ").last() }
            
            Log.d("YOLO", "TFLite model loaded successfully: ${labels.size} labels.")
        } catch (e: Exception) {
            Log.e("YOLO", "Error loading TFLite model", e)
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show()
        }
    }

    private fun initUI() {
        edtNoProduksi = findViewById(R.id.edtNoProduksi)
        edtTglProduksi = findViewById(R.id.edtTglProduksi)
        edtNoChasisKanban = findViewById(R.id.edtNoChasisKanban)
        edtNoChasisScan = findViewById(R.id.edtNoChasisScan)
        statusBadge = findViewById(R.id.statusBadge)
        cameraImage = findViewById(R.id.cameraImage)
        captureImgBtn = findViewById(R.id.captureImgBtn)
        scanQRBtn = findViewById(R.id.scanQRBtn)
        submitBtn = findViewById(R.id.submitBtn)
        validationErrorDiv = findViewById(R.id.validationErrorDiv)
        validationErrorText = findViewById(R.id.validationErrorText)

        captureImgBtn.setOnClickListener {
            launchDocumentScanner()
        }

        scanQRBtn.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan QR Code")
                setBeepEnabled(true)
            }
            qrLauncher.launch(options)
        }

        submitBtn.setOnClickListener {
            val scanText = edtNoChasisScan.text.toString().trim()
            if (scanText.isEmpty()) {
                Toast.makeText(this, "Lakukan prediksi OCR terlebih dahulu", Toast.LENGTH_SHORT).show()
            } else {
                submitData()
            }
        }
    }

    private fun launchDocumentScanner() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.e("DocScanner", "Failed to start scanner", e)
                Toast.makeText(this, "Scanner error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun runInference(imageUri: Uri) {
        try {
            val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(imageUri)) ?: return

            // YOLOv8 input size is typically 640x640
            val inputSize = 640
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()

            var tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            val numClasses = labels.size
            val outputShape = tflite.getOutputTensor(0).shape() // e.g., [1, 42, 8400]
            val probabilityBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

            tflite.run(tensorImage.buffer, probabilityBuffer.buffer)

            val output = probabilityBuffer.floatArray
            val detections = mutableListOf<Detection>()

            val numBoxes = outputShape[2] // 8400

            for (i in 0 until numBoxes) {
                var maxClassScore = 0f
                var classId = -1
                
                for (c in 0 until numClasses) {
                    val score = output[numBoxes * (c + 4) + i]
                    if (score > maxClassScore) {
                        maxClassScore = score
                        classId = c
                    }
                }

                if (maxClassScore > confidenceThreshold) {
                    val x_center = output[i] * bitmap.width
                    val w = output[numBoxes * 2 + i] * bitmap.width
                    val x1 = x_center - w / 2
                    detections.add(Detection(x1, classId, maxClassScore))
                }
            }

            // Sort left-to-right
            val sortedDetections = detections.sortedBy { it.x }
            
            // Simple overlap filter (NMS-lite)
            val filteredResults = mutableListOf<Detection>()
            if (sortedDetections.isNotEmpty()) {
                filteredResults.add(sortedDetections[0])
                for (i in 1 until sortedDetections.size) {
                    val last = filteredResults.last()
                    // If detections are too close horizontally (e.g. within 10 pixels), keep highest confidence
                    if (sortedDetections[i].x - last.x < 10) { 
                        if (sortedDetections[i].confidence > last.confidence) {
                            filteredResults[filteredResults.size - 1] = sortedDetections[i]
                        }
                    } else {
                        filteredResults.add(sortedDetections[i])
                    }
                }
            }

            // Map class IDs to labels and format string
            val rawText = filteredResults.joinToString("") { labels[it.classId] }
            
            val formattedText = rawText
                .replace(" ", "")
                .replace("<", "")
                .replace(">", "")
                .uppercase()
                .replace("O", "0")
                .trim()

            runOnUiThread {
                edtNoChasisScan.setText(formattedText)
                submitBtn.isEnabled = formattedText.isNotEmpty()
                if (formattedText.isEmpty()) {
                    Toast.makeText(this, "Tidak ada karakter terdeteksi", Toast.LENGTH_SHORT).show()
                }
                updateBadge()
            }
        } catch (e: Exception) {
            Log.e("YOLO", "Error running inference", e)
            runOnUiThread {
                Toast.makeText(this, "Inference error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class Detection(val x: Float, val classId: Int, val confidence: Float)

    private fun getFilePathFromUri(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File.createTempFile("scanned_", ".jpg", cacheDir)
            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("DocScanner", "Failed to get file path from URI", e)
            null
        }
    }

    // --- REUSED METHODS FROM RecordActivity ---

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.selectedItemId = R.id.nav_record
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                    true
                }
                R.id.nav_record -> true
                else -> false
            }
        }
    }

    private fun checkPrerequisites(sequenceNo: String, dateProduction: String) {
        validationErrorDiv.visibility = View.GONE
        captureImgBtn.isEnabled = false
        submitBtn.isEnabled = false

        val requestBody = JSONObject(mapOf("sequence_no" to sequenceNo, "date_production" to dateProduction)).toString()
        val request = Request.Builder()
            .url(CHECK_PREREQUISITES_URL)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    validationErrorText.text = "Connection Error: ${e.message}"
                    validationErrorDiv.visibility = View.VISIBLE
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        val json = JSONObject(responseBody)
                        if (json.optBoolean("success") && json.optBoolean("prerequisites_met")) {
                            captureImgBtn.isEnabled = true
                            validationErrorDiv.visibility = View.GONE
                        } else {
                            validationErrorText.text = json.optString("message", "Prerequisites not met")
                            validationErrorDiv.visibility = View.VISIBLE
                        }
                    } else {
                        validationErrorText.text = "Server Error: ${response.code}"
                        validationErrorDiv.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    private fun updateBadge() {
        val kanban = edtNoChasisKanban.text.toString().trim()
        val scan = edtNoChasisScan.text.toString().trim()
        if (kanban.isNotEmpty() && scan.isNotEmpty()) {
            statusBadge.visibility = View.VISIBLE
            if (kanban == scan) {
                statusBadge.text = "✅ OK"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_ok)
            } else {
                statusBadge.text = "❌ NG"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_ng)
            }
        } else {
            statusBadge.visibility = View.GONE
        }
    }

    private fun submitData() {
        val noProduksi = edtNoProduksi.text.toString().trim()
        val tglProduksi = edtTglProduksi.text.toString().trim()
        val noKanban = edtNoChasisKanban.text.toString().trim()
        val noScan = edtNoChasisScan.text.toString().trim()
        val status = if (noKanban == noScan) "OK" else "NG"

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("No_Produksi", noProduksi)
            .addFormDataPart("Tgl_Produksi", tglProduksi)
            .addFormDataPart("No_Chasis_Kanban", noKanban)
            .addFormDataPart("No_Chasis_Scan", noScan)
            .addFormDataPart("Status_Record", status)

        currentPhotoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                builder.addFormDataPart("Photo_Ng_Path", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            }
        }

        val request = Request.Builder()
            .url("http://192.168.173.207/iseki_chadet/public/api/records/storenew")
            .post(builder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@YoloRecordActivity, "Submit failed", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@YoloRecordActivity, "Data submitted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@YoloRecordActivity, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
}
