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
import okhttp3.RequestBody.Companion.asRequestBody
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

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // delete
                    dp[i][j - 1] + 1,       // insert
                    dp[i - 1][j - 1] + cost // substitute
                )
            }
        }
        return dp[a.length][b.length]
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
                    .replace("O", "0")                     // ubah huruf O jadi angka 0

                val kanban = edtNoChasisKanban.text.toString().trim().uppercase()
                var finalScan = cleaned

                // === CASE 1: ISKI4550 / ISKI0024 ===
                if (kanban.startsWith("ISKI4550") || kanban.startsWith("ISKI0024")) {
                    if (cleaned.isNotEmpty() && kanban.length >= 17) {
                        val prefix13 = kanban.substring(0, 13)
                        val last4 = if (cleaned.length >= 4) {
                            cleaned.takeLast(4)
                        } else {
                            cleaned.padStart(4, '0')
                        }
                        finalScan = prefix13 + last4
                    }
                }

                // === CASE 2: Prefix MF1E / MF2E / GC ===
                else if (
                    kanban.startsWith("MF1E") ||
                    kanban.startsWith("MF2E") ||
                    kanban.startsWith("GC")
                ) {
                    if (cleaned.isNotEmpty()) {
                        val builder = StringBuilder(cleaned)

                        // MF1E / MF2E → bandingkan 4 digit pertama
                        if (kanban.startsWith("MF1E") || kanban.startsWith("MF2E")) {
                            val prefix = kanban.take(4)
                            val scanPrefix = cleaned.take(4)

                            val dist = levenshtein(scanPrefix, prefix)
                            val similarity = 1.0 - (dist.toDouble() / prefix.length.toDouble())

                            if (similarity >= 0.5 || scanPrefix.length < 4) {
                                // force replace prefix
                                if (builder.length < 4) {
                                    builder.insert(0, prefix.substring(builder.length))
                                }
                                for (i in 0 until prefix.length) {
                                    if (i < builder.length) {
                                        builder.setCharAt(i, prefix[i])
                                    } else {
                                        builder.append(prefix[i])
                                    }
                                }
                            }
                        }

                        // GC → bandingkan 1 digit pertama
                        if (kanban.startsWith("GC")) {
                            val prefix = "GC"

                            // Normalisasi dulu cleaned ke uppercase
                            val cleanedUpper = cleaned.uppercase()

                            // Cek khusus kalau hanya kebaca sebagian (g-/ -c/ g?/ ?c)
                            if (cleanedUpper.length >= 1) {
                                val first = cleanedUpper.getOrNull(0)
                                val second = cleanedUpper.getOrNull(1)

                                val matchPartialGC =
                                    (first == 'G' && (second == null || second == '-' || second == '?' || second == 'C')) ||
                                            (second == 'C' && (first == null || first == '-' || first == '?' || first == 'G'))

                                if (matchPartialGC) {
                                    if (builder.length < 2) builder.setLength(2) // pastikan panjang minimal 2
                                    builder.setCharAt(0, 'G')
                                    builder.setCharAt(1, 'C')
                                }
                            }

                            // kalau masih salah → paksa juga jadi GC
                            if (builder.length < 2 || builder[0] != 'G' || builder[1] != 'C') {
                                if (builder.length < 2) {
                                    builder.insert(0, prefix.substring(builder.length))
                                }
                                for (i in 0 until prefix.length) {
                                    if (i < builder.length) {
                                        builder.setCharAt(i, prefix[i])
                                    } else {
                                        builder.append(prefix[i])
                                    }
                                }
                            }
                        }

                        finalScan = builder.toString()
                    }
                }

                edtNoChasisScan.setText(finalScan)
                updateBadge()
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

        val requestBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("No_Produksi", noProduksi)
            .addFormDataPart("No_Chasis_Kanban", noKanban)
            .addFormDataPart("No_Chasis_Scan", noScan)
            .addFormDataPart("Status_Record", status)

        if (status == "NG" && currentPhotoPath != null) {
            val file = File(currentPhotoPath!!)
            if (file.exists()) {
                val mediaType = "image/jpeg".toMediaType()
                requestBuilder.addFormDataPart(
                    "Photo_Ng_Path",
                    file.name,
                    file.asRequestBody(mediaType)
                )
            }
        }

        val requestBody = requestBuilder.build()

        val request = Request.Builder()
            .url("http://192.168.173.207/iseki_chadet/public/api/records/store")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "Submit failed: ${e.message}", Toast.LENGTH_SHORT).show()

                    // ✅ hapus file setelah sukses submit
                    currentPhotoPath?.let { path ->
                        val file = File(path)
                        if (file.exists()) file.delete()
                        currentPhotoPath = null
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(applicationContext, "Data submitted", Toast.LENGTH_SHORT).show()

                        // ✅ hapus file setelah sukses submit
                        currentPhotoPath?.let { path ->
                            val file = File(path)
                            if (file.exists()) file.delete()
                            currentPhotoPath = null
                        }

                        val intent = Intent(this@RecordActivity, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(applicationContext, "Error: ${response.code}", Toast.LENGTH_SHORT).show()

                        // ✅ hapus file setelah sukses submit
                        currentPhotoPath?.let { path ->
                            val file = File(path)
                            if (file.exists()) file.delete()
                            currentPhotoPath = null
                        }
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupPhoto()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        cleanupPhoto()
    }

    private fun cleanupPhoto() {
        currentPhotoPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
            currentPhotoPath = null
        }
    }

}
