package com.example.isekichadet

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    private lateinit var edtNoProduksi: EditText
    private lateinit var edtNoChasisKanban: EditText
    private lateinit var edtNoChasisScan: EditText
    private lateinit var statusBadge: TextView
    private lateinit var cameraImage: ImageView
    private lateinit var captureImgBtn: Button
    private lateinit var scanQRBtn: Button
    private lateinit var submitBtn: Button

    private var currentPhotoPath: String? = null
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private val client = OkHttpClient()

    // QR Scanner launcher
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val parts = result.contents.split(";")
            if (parts.size > 4) {
                edtNoProduksi.setText(parts[0])
                edtNoChasisKanban.setText(parts[4])
                updateBadge()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        edtNoProduksi = findViewById(R.id.edtNoProduksi)
        edtNoChasisKanban = findViewById(R.id.edtNoChasisKanban)
        edtNoChasisScan = findViewById(R.id.edtNoChasisScan)
        statusBadge = findViewById(R.id.statusBadge)
        cameraImage = findViewById(R.id.cameraImage)
        captureImgBtn = findViewById(R.id.captureImgBtn)
        scanQRBtn = findViewById(R.id.scanQRBtn)
        submitBtn = findViewById(R.id.submitBtn)

        // Ambil gambar kamera
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) captureImage()
            else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }

        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                currentPhotoPath?.let { path ->
                    val bitmap = BitmapFactory.decodeFile(path)
                    cameraImage.setImageBitmap(bitmap)
                    recognizeText(bitmap)
                }
            }
        }

        captureImgBtn.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Scan QR
        scanQRBtn.setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Scan QR Code")
            options.setCameraId(0)
            options.setBeepEnabled(true)
            options.setBarcodeImageEnabled(false)
            qrLauncher.launch(options)
        }

        // Submit API
        submitBtn.setOnClickListener {
            submitData()
        }

        // Bottom Navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.selectedItemId = R.id.nav_record
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_record -> true
                else -> false
            }
        }
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun captureImage() {
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
            null
        }
        photoFile?.also {
            val photoUri: Uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", it)
            takePictureLauncher.launch(photoUri)
        }
    }

    private fun recognizeText(bitmap: android.graphics.Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { ocrText ->
                val cleaned = ocrText.text
                    .replace("\\s".toRegex(), "")          // hapus spasi & tab
                    .replace("[^A-Za-z0-9]".toRegex(), "") // hapus karakter non huruf/angka
                    .uppercase()                           // ubah ke kapital semua

                edtNoChasisScan.setText(cleaned)
                updateBadge()

                currentPhotoPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                    currentPhotoPath = null
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateBadge() {
        val kanban = edtNoChasisKanban.text.toString().trim()
        val scan = edtNoChasisScan.text.toString().trim()

        if (kanban.isNotEmpty() && scan.isNotEmpty()) {
            if (kanban == scan) {
                statusBadge.visibility = View.VISIBLE
                statusBadge.text = "✅ OK"
                statusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_ok)
            } else {
                statusBadge.visibility = View.VISIBLE
                statusBadge.text = "❌ NG"
                statusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_ng)
            }
        } else {
            statusBadge.text = ""
            statusBadge.setBackgroundColor(getColor(android.R.color.transparent))
        }
    }

    private fun submitData() {
        val noProduksi = edtNoProduksi.text.toString().trim()
        val noKanban = edtNoChasisKanban.text.toString().trim()
        val noScan = edtNoChasisScan.text.toString().trim()
        val status = if (noKanban == noScan) "OK" else "NG"

        val json = JSONObject()
        json.put("No_Produksi", noProduksi)
        json.put("No_Chasis_Kanban", noKanban)
        json.put("No_Chasis_Scan", noScan)
        json.put("Status_Record", status)

        //val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString())
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("http://192.168.173.207/iseki_chadet/public/api/records/store")
            .post(body)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "Submit failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(applicationContext, "Data submitted", Toast.LENGTH_SHORT).show()

                        val intent = Intent(this@RecordActivity, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(applicationContext, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
}
