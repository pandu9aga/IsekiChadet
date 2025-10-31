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
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.graphics.Bitmap

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

    private var photoTaken: Boolean = false // Tambahkan flag

    companion object {
        private const val KEY_PHOTO_PATH = "current_photo_path"
        private const val KEY_PHOTO_TAKEN = "photo_taken" // Tambahkan key untuk flag
    }

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

    // Simpan nilai currentPhotoPath saat aktivitas akan dihancurkan karena konfigurasi berubah
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO_PATH, currentPhotoPath)
        outState.putBoolean(KEY_PHOTO_TAKEN, photoTaken) // Simpan flag
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        // Pulihkan currentPhotoPath dan flag photoTaken jika savedInstanceState ada
        if (savedInstanceState != null) {
            currentPhotoPath = savedInstanceState.getString(KEY_PHOTO_PATH)
            photoTaken = savedInstanceState.getBoolean(KEY_PHOTO_TAKEN) // Pulihkan flag
        }

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
                    val imageFile = File(path)
                    if (imageFile.exists()) {
                        var bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            // Terapkan rotasi berdasarkan EXIF
                            bitmap = rotateBitmapIfRequired(bitmap, path)

                            cameraImage.setImageBitmap(bitmap)
                            recognizeText(bitmap)
                            photoTaken = true
                        } else {
                            Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
                            photoTaken = false
                        }
                    } else {
                        Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
                        photoTaken = false
                    }
                }
            } else {
                photoTaken = false
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

        // Setelah aktivitas dibuat ulang, jika foto sebelumnya telah diambil,
        // coba tampilkan kembali
        if (photoTaken && currentPhotoPath != null) {
            val imageFile = File(currentPhotoPath!!)
            if (imageFile.exists()) {
                var bitmap = BitmapFactory.decodeFile(currentPhotoPath!!)
                if (bitmap != null) {
                    // Terapkan rotasi berdasarkan EXIF saat menampilkan ulang
                    bitmap = rotateBitmapIfRequired(bitmap, currentPhotoPath!!)
                    cameraImage.setImageBitmap(bitmap)
                } else {
                    photoTaken = false
                }
            } else {
                photoTaken = false
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
            photoTaken = false // Reset flag sebelum mengambil foto baru
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

    private fun normalizeScanBasedOnKanban(scan: String, kanban: String): String {
        if (scan.isEmpty() || kanban.isEmpty()) {
            return scan
        }

        // Ambil panjang minimum untuk perbandingan
        val minLength = minOf(scan.length, kanban.length)
        val normalizedBuilder = StringBuilder()

        for (i in 0 until minLength) {
            val scanChar = scan[i]
            val kanbanChar = kanban[i].uppercaseChar() // Bandingkan dalam bentuk kapital

            // Pasangan karakter yang sering tertukar
            val isPotentialMisread = when (kanbanChar) {
                '1' -> scanChar.uppercaseChar() in "I1"
                'I' -> scanChar.uppercaseChar() in "I1"
                '5' -> scanChar.uppercaseChar() in "S53"
                'S' -> scanChar.uppercaseChar() in "S5"
                '3' -> scanChar.uppercaseChar() in "E3F5"
                'E' -> scanChar.uppercaseChar() in "E3F"
                'F' -> scanChar.uppercaseChar() in "E3F"
                '4' -> scanChar.uppercaseChar() in "A4"
                'A' -> scanChar.uppercaseChar() in "A4"
                else -> false
            }

            if (isPotentialMisread) {
                // Jika karakter scan adalah pasangan yang tertukar, gunakan karakter dari kanban
                normalizedBuilder.append(kanbanChar)
            } else {
                // Jika tidak, gunakan karakter scan asli (sudah di upper case)
                normalizedBuilder.append(scanChar.uppercaseChar())
            }
        }

        // Tambahkan sisa karakter dari scan jika lebih panjang dari kanban
        if (scan.length > kanban.length) {
            normalizedBuilder.append(scan.substring(kanban.length).uppercase())
        }

        return normalizedBuilder.toString()
    }

    private fun recognizeText(bitmap: android.graphics.Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { ocrText ->
                // 1. Lakukan pembersihan dasar
                val cleaned = ocrText.text
                    .replace("\\s".toRegex(), "")          // hapus spasi & tab
                    .replace("[^A-Za-z0-9]".toRegex(), "") // hapus karakter non huruf/angka
                    .uppercase()                           // ubah ke kapital semua
                    .replace("O", "0")                     // ubah huruf O jadi angka 0

                val kanban = edtNoChasisKanban.text.toString().trim().uppercase()
                // 2. Normalisasi hasil scan berdasarkan referensi kanban
                var finalScan = normalizeScanBasedOnKanban(cleaned, kanban)

                // 3. Lanjutkan dengan logika CASE 1 dan CASE 2 yang sudah ada
                // Perhatikan bahwa 'cleaned' diganti dengan 'finalScan' atau 'cleaned' yang sudah dinormalisasi
                // dalam logika CASE 2, terutama untuk take(4) dan takeLast(4) dll.

                // === CASE 1: ISKI4550 / ISKI0024 / ISKI3410 / ISKM2E56 (Tentukan berdasarkan kemiripan) ===
                if (kanban.startsWith("ISKI4550") || // Tetap gunakan ini untuk men-trigger CASE 1 secara umum
                    kanban.startsWith("ISKI0024") ||
                    kanban.startsWith("ISKI3410") ||
                    kanban.startsWith("ISKM2E56"))
                {
                    if (finalScan.isNotEmpty()) { // Cek jika scan tidak kosong
                        // Daftar prefix yang valid untuk CASE 1
                        val validPrefixes = listOf("ISKI4550", "ISKI0024", "ISKI3410", "ISKM2E56")

                        // Hitung jarak Levenshtein antara awal dari finalScan dan setiap valid prefix
                        val distances = validPrefixes.map { prefix ->
                            val scanPrefixForComparison = finalScan.take(prefix.length) // Ambil bagian awal scan sepanjang prefix
                            Pair(prefix, levenshtein(scanPrefixForComparison.uppercase(), prefix))
                        }

                        // Temukan prefix dengan jarak terkecil (paling mirip)
                        val bestMatch = distances.minByOrNull { it.second }

                        // Gunakan prefix dari best match, bukan dari kanban
                        val matchedPrefix13 = if (bestMatch != null && bestMatch.second < 3) { // Threshold bisa disesuaikan, misalnya 3 karakter berbeda
                            // Ambil 13 karakter dari prefix yang paling mirip, tambahkan nol jika kurang
                            bestMatch.first.padEnd(13, '0').take(13)
                        } else {
                            // Jika tidak ada yang mirip (jarak terlalu jauh), gunakan 13 karakter pertama dari kanban sebagai fallback
                            // Atau bisa juga memilih untuk tidak mengganti finalScan sama sekali
                            kanban.padEnd(13, '0').take(13)
                        }

                        // Ambil 4 digit terakhir dari finalScan (sudah dinormalisasi berdasarkan kanban sebelumnya)
                        val last4FromScan = if (finalScan.length >= 4) {
                            finalScan.takeLast(4)
                        } else {
                            finalScan.padStart(4, '0') // Jika scan kurang dari 4, isi dengan nol di depan
                        }

                        // Gabungkan prefix yang paling mirip (13 karakter) dengan 4 digit terakhir dari scan
                        finalScan = matchedPrefix13 + last4FromScan
                    }
                }

                // === CASE 2: Prefix MF1E / MF2E / GC ===
                else if (
                    kanban.startsWith("MF1E") ||
                    kanban.startsWith("MF2E") ||
                    kanban.startsWith("GC")
                ) {
                    if (finalScan.isNotEmpty()) { // Gunakan finalScan
                        val builder = StringBuilder(finalScan) // Gunakan finalScan

                        // MF1E / MF2E → bandingkan 4 digit pertama
                        if (kanban.startsWith("MF1E") || kanban.startsWith("MF2E")) {
                            val prefix = kanban.take(4)
                            val scanPrefix = finalScan.take(4) // Gunakan finalScan

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

                            // Normalisasi dulu cleaned ke uppercase (finalScan sudah uppercase)
                            val cleanedUpper = finalScan.uppercase() // finalScan sudah uppercase

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

        if (currentPhotoPath != null) {
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
        if (photoTaken) { // Hanya hapus jika foto benar-benar diambil
            currentPhotoPath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
        }
        currentPhotoPath = null
        photoTaken = false // Reset flag saat membersihkan
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, path: String): Bitmap {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
            // ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> // Tidak umum untuk kamera
            // ExifInterface.ORIENTATION_FLIP_VERTICAL -> // Tidak umum untuk kamera
            else -> bitmap // Tidak perlu diputar
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
