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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
                    
                    // Run ML Kit OCR
                    runOCR(imageUri)
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

        initUI()
        setupBottomNav()
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

    private fun runOCR(imageUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, imageUri)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val resultText = visionText.text
                        .replace("\n", "")
                        .replace(" ", "")
                        .replace("<", "")
                        .replace(">", "")
                        .uppercase()
                        .replace("O", "0")
                        .trim()
                    edtNoChasisScan.setText(resultText)

                    submitBtn.isEnabled = resultText.isNotEmpty()

                    if (resultText.isEmpty()) {
                        Toast.makeText(this, "Tidak ada teks terdeteksi", Toast.LENGTH_SHORT).show()
                    }

                    updateBadge()
                }
                .addOnFailureListener { e ->
                    Log.e("OCR", "Text recognition failed", e)
                    Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e("OCR", "Error creating InputImage", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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
