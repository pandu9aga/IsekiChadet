package com.example.isekichadet

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class YoloThresholdActivity : AppCompatActivity() {

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

    // Threshold buttons
    private lateinit var thresholdButtonsLayout: LinearLayout
    private lateinit var btnWhite: Button
    private lateinit var btnBlack: Button
    private lateinit var btnGray: Button
    private lateinit var btnReset: Button
    private lateinit var tvThresholdInfo: TextView

    private var currentPhotoPath: String? = null
    private var originalBitmap: Bitmap? = null
    private var photoTaken: Boolean = false

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>

    private val client = OkHttpClient()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val KEY_PHOTO_PATH = "current_photo_path"
        private const val KEY_PHOTO_TAKEN = "photo_taken"
        private const val CHECK_PREREQUISITES_URL = "http://192.168.173.207/iseki_chadet/public/api/records/check-prerequisites"
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO_PATH, currentPhotoPath)
        outState.putBoolean(KEY_PHOTO_TAKEN, photoTaken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yolo_threshold)

        if (savedInstanceState != null) {
            currentPhotoPath = savedInstanceState.getString(KEY_PHOTO_PATH)
            photoTaken = savedInstanceState.getBoolean(KEY_PHOTO_TAKEN)
        }

        initUI()
        setupBottomNav()
        setupCameraLaunchers()

        // Initialize OpenCV
        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "OpenCV initialization failed!")
            Toast.makeText(this, "OpenCV gagal dimuat", Toast.LENGTH_SHORT).show()
        } else {
            Log.d("OpenCV", "OpenCV initialized successfully")
        }

        // Restore image if activity was recreated
        if (photoTaken && currentPhotoPath != null) {
            val imageFile = File(currentPhotoPath!!)
            if (imageFile.exists()) {
                var bitmap = BitmapFactory.decodeFile(currentPhotoPath!!)
                if (bitmap != null) {
                    bitmap = rotateBitmapIfRequired(bitmap, currentPhotoPath!!)
                    originalBitmap = bitmap
                    cameraImage.setImageBitmap(bitmap)
                    thresholdButtonsLayout.visibility = View.VISIBLE
                    tvThresholdInfo.visibility = View.VISIBLE
                } else {
                    photoTaken = false
                }
            } else {
                photoTaken = false
            }
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

        // Threshold controls
        thresholdButtonsLayout = findViewById(R.id.thresholdButtonsLayout)
        btnWhite = findViewById(R.id.btnWhite)
        btnBlack = findViewById(R.id.btnBlack)
        btnGray = findViewById(R.id.btnGray)
        btnReset = findViewById(R.id.btnReset)
        tvThresholdInfo = findViewById(R.id.tvThresholdInfo)

        captureImgBtn.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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

        // Threshold button listeners
        btnWhite.setOnClickListener {
            originalBitmap?.let { bmp ->
                val processed = applyAdaptiveThreshold(bmp, forceWhiteBackground = true)
                cameraImage.setImageBitmap(processed)
                tvThresholdInfo.text = "Putih"
                tvThresholdInfo.visibility = View.VISIBLE
                runOCR(processed)
            }
        }

        btnBlack.setOnClickListener {
            originalBitmap?.let { bmp ->
                val processed = applyAdaptiveThreshold(bmp, forceWhiteBackground = false)
                cameraImage.setImageBitmap(processed)
                tvThresholdInfo.text = "Hitam"
                tvThresholdInfo.visibility = View.VISIBLE
                runOCR(processed)
            }
        }

        btnGray.setOnClickListener {
            originalBitmap?.let { bmp ->
                val processed = applyEnhancedGrayscale(bmp)
                cameraImage.setImageBitmap(processed)
                tvThresholdInfo.text = "Abu-abu"
                tvThresholdInfo.visibility = View.VISIBLE
                runOCR(processed)
            }
        }

        btnReset.setOnClickListener {
            originalBitmap?.let { bmp ->
                cameraImage.setImageBitmap(bmp)
                tvThresholdInfo.text = "Warna"
                tvThresholdInfo.visibility = View.VISIBLE
                runOCR(bmp)
            }
        }
    }

    private fun setupCameraLaunchers() {
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
                            bitmap = rotateBitmapIfRequired(bitmap, path)
                            originalBitmap = bitmap
                            cameraImage.setImageBitmap(bitmap)
                            photoTaken = true

                            // Show threshold buttons
                            thresholdButtonsLayout.visibility = View.VISIBLE
                            tvThresholdInfo.visibility = View.VISIBLE
                            tvThresholdInfo.text = "Pilih mode threshold untuk OCR"

                            // Run OCR on the original image first
                            runOCR(bitmap)
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
            photoTaken = false
            takePictureLauncher.launch(photoUri)
        }
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    // --- Image Processing using OpenCV ---

    /**
     * Full adaptive threshold pipeline using OpenCV:
     * 1. Grayscale (cvtColor)
     * 2. CLAHE (clipLimit=2.0, tileGridSize=2x2)
     * 3. fastNlMeansDenoising (h=10, templateWindowSize=7, searchWindowSize=21)
     * 4. Adaptive Gaussian Thresholding (blockSize=21, C=2)
     * 5. Morphological Opening (3x3 kernel) to remove small noise
     * 6. Ensure black text on white/black background
     */
    private fun applyAdaptiveThreshold(source: Bitmap, forceWhiteBackground: Boolean): Bitmap {
        // Convert Bitmap to Mat
        val srcMat = Mat()
        Utils.bitmapToMat(source, srcMat)

        // Step 1: Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Step 2: CLAHE
        val clahe = Imgproc.createCLAHE(2.0, Size(2.0, 2.0))
        val claheResult = Mat()
        clahe.apply(gray, claheResult)

        // Step 3: fastNlMeansDenoising (replaces bilateral filter for better noise removal)
        val denoised = Mat()
        Photo.fastNlMeansDenoising(claheResult, denoised, 10f, 7, 21)

        // Step 4: Adaptive Gaussian Thresholding
        val thresholded = Mat()
        Imgproc.adaptiveThreshold(
            denoised, thresholded, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 21, 2.0
        )

        // Step 5: Morphological Opening to remove small noise dots
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val opened = Mat()
        Imgproc.morphologyEx(thresholded, opened, Imgproc.MORPH_OPEN, kernel)

        // Step 6: Ensure correct text/background contrast
        val whitePixels = Core.countNonZero(opened)
        val totalPixels = opened.rows() * opened.cols()
        val whiteRatio = whitePixels.toDouble() / totalPixels.toDouble()

        val finalMat = if (forceWhiteBackground) {
            if (whiteRatio < 0.5) {
                val inverted = Mat()
                Core.bitwise_not(opened, inverted)
                inverted
            } else {
                opened
            }
        } else {
            if (whiteRatio >= 0.5) {
                val inverted = Mat()
                Core.bitwise_not(opened, inverted)
                inverted
            } else {
                opened
            }
        }

        // Convert Mat back to Bitmap
        val resultBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        // Convert single-channel to RGBA for Bitmap
        val rgbaMat = Mat()
        Imgproc.cvtColor(finalMat, rgbaMat, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgbaMat, resultBitmap)

        // Release Mats
        srcMat.release()
        gray.release()
        claheResult.release()
        denoised.release()
        thresholded.release()
        kernel.release()
        opened.release()
        rgbaMat.release()
        if (finalMat !== opened) finalMat.release()

        return resultBitmap
    }

    /**
     * Enhanced grayscale: Grayscale + CLAHE + Bilateral filter (no thresholding)
     */
    private fun applyEnhancedGrayscale(source: Bitmap): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(source, srcMat)

        // Grayscale
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

        // CLAHE
        val clahe = Imgproc.createCLAHE(8.0, Size(48.0, 48.0))
        val claheResult = Mat()
        clahe.apply(gray, claheResult)

        // Bilateral Filter
        val denoised = Mat()
        Imgproc.bilateralFilter(claheResult, denoised, 9, 75.0, 75.0)

        // Convert back to Bitmap
        val resultBitmap = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
        val rgbaMat = Mat()
        Imgproc.cvtColor(denoised, rgbaMat, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgbaMat, resultBitmap)

        // Release
        srcMat.release()
        gray.release()
        claheResult.release()
        denoised.release()
        rgbaMat.release()

        return resultBitmap
    }

    // --- OCR ---

    private fun runOCR(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val resultText = visionText.text
                        .replace("\n", "")
                        .replace(" ", "")
                        .replace(Regex("[^a-zA-Z0-9]"), "")
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

    // --- Navigation ---

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

    // --- Prerequisites Check ---

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

    // --- Badge ---

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

    // --- Submit ---

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
                runOnUiThread { Toast.makeText(this@YoloThresholdActivity, "Submit failed", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@YoloThresholdActivity, "Data submitted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@YoloThresholdActivity, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    // --- EXIF Rotation ---

    private fun rotateBitmapIfRequired(bitmap: Bitmap, path: String): Bitmap {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
            else -> bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // --- Cleanup ---

    override fun onDestroy() {
        super.onDestroy()
        cleanupPhoto()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        cleanupPhoto()
    }

    private fun cleanupPhoto() {
        if (photoTaken) {
            currentPhotoPath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
        }
        currentPhotoPath = null
        photoTaken = false
    }
}
