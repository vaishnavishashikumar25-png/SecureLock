package com.vaish.applock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.vaish.applock.databinding.ActivityLockBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var mode: String = "VERIFY"
    private var isOwnerRegistered: Boolean = false
    private var isProcessing = false
    private lateinit var faceNetModel: FaceNetModel
    private lateinit var riskEngine: RiskEngine
    private var riskLevel: String = "LOW"
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var failSafeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LockActivity", "onCreate called with mode: ${intent.getStringExtra("MODE")}")
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        faceNetModel = FaceNetModel(this)
        riskEngine = RiskEngine(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        mode = intent.getStringExtra("MODE") ?: "VERIFY"
        val isTestMode = intent.getBooleanExtra("TEST_MODE", false)
        riskLevel = intent.getStringExtra("RISK_LEVEL") ?: "LOW"
        isOwnerRegistered = File(getExternalFilesDir(null), "owner_face.dat").exists()

        if (isTestMode) {
            Toast.makeText(this, "TEST MODE: Capture will trigger regardless of face", Toast.LENGTH_LONG).show()
        }

        updateUIForMode()
        applyRiskLevelAuth()

        startScannerAnimation()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.btnUnlock.setOnClickListener {
            if (mode == "REGISTER") {
                saveOwnerPattern()
            } else {
                goHome()
            }
        }

        binding.btnUsePin.setOnClickListener {
            if (binding.tilPin.visibility == View.GONE) {
                binding.tilPin.visibility = View.VISIBLE
                binding.tvMessage.text = "Enter PIN to Unlock"
                binding.btnUsePin.text = "Verify"
                binding.cameraCard.alpha = 0.3f
            } else {
                verifyPin()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mode = intent.getStringExtra("MODE") ?: "VERIFY"
        
        // Cancel any pending fail-safe timers from previous session
        failSafeRunnable?.let { handler.removeCallbacks(it) }
        
        isProcessing = false
        isOwnerRegistered = File(getExternalFilesDir(null), "owner_face.dat").exists()
        
        Log.d("LockActivity", "New intent received. Mode: $mode. Resetting state.")
        updateUIForMode()
        
        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun updateUIForMode() {
        if (mode == "STEALTH") {
            // Make window transparent and tiny but technically visible to keep camera active
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val params = window.attributes
            params.alpha = 0.01f
            params.width = 1
            params.height = 1
            window.attributes = params
            binding.root.visibility = View.VISIBLE
            window.setDimAmount(0f)
        } else {
            window.setBackgroundDrawableResource(android.R.color.black)
            val params = window.attributes
            params.alpha = 1.0f
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            window.attributes = params
            binding.root.visibility = View.VISIBLE
            window.setDimAmount(0.5f)
            binding.cameraCard.alpha = 1.0f
            binding.tilPin.visibility = View.GONE
            binding.etPinEntry.text?.clear()
            

            when (mode) {
                "REGISTER" -> {
                    binding.tvMessage.text = getString(R.string.update_face)
                    binding.btnUsePin.visibility = View.GONE
                    binding.btnUnlock.text = getString(R.string.update_face) // or a new string like "Capture"
                }
                "APP_UNLOCK" -> {
                    binding.tvMessage.text = getString(R.string.app_name)
                    binding.btnUnlock.text = getString(R.string.cancel)
                    binding.btnUsePin.text = getString(R.string.use_pin)
                    binding.btnUsePin.visibility = View.VISIBLE
                }
                else -> { // VERIFY
                    binding.tvMessage.text = getString(R.string.auth_face)
                    binding.btnUnlock.text = getString(R.string.cancel)
                    binding.btnUsePin.text = getString(R.string.use_pin)
                    binding.btnUsePin.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startScannerAnimation() {
        val animation = TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 1f
        )
        animation.duration = 2000
        animation.repeatCount = Animation.INFINITE
        animation.repeatMode = Animation.REVERSE
        binding.scannerLine.startAnimation(animation)
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun verifyPin() {
        if (isProcessing) return
        val enteredPin = binding.etPinEntry.text.toString()
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val savedPin = sharedPrefs.getString("OwnerPin", sharedPrefs.getString("SecurityPin", null))

        if (savedPin == null) {
            Toast.makeText(this, getString(R.string.no_pin_set), Toast.LENGTH_SHORT).show()
        } else if (enteredPin == savedPin) {
            // PIN is the ultimate override, but we log the success to reset risk
            isProcessing = true
            Toast.makeText(this, getString(R.string.pin_verified), Toast.LENGTH_SHORT).show()
            
            if (mode == "APP_UNLOCK") {
                setResult(RESULT_OK)
                finish()
                return
            }

            checkIfOwnerUsingPin()
            unlockSuccess()
        } else {
            binding.tilPin.error = "Incorrect PIN"
            riskEngine.recordFailedAttempt() // Increment risk on failure
            captureIntruderPhoto() 
        }
    }

    private fun checkIfOwnerUsingPin() {
        val face = lastDetectedFace
        val bitmap = lastDetectedBitmap
        
        if (face != null && bitmap != null) {
            if (!verifyFace(face, bitmap)) {
                // Not the owner! Flag this session.
                Log.d("LockActivity", "PIN used by non-owner. Starting tracking.")
                val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putBoolean("IsIntruderSession", true).apply()
                captureIntruderPhoto()
            } else {
                Log.d("LockActivity", "PIN used by owner.")
                val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putBoolean("IsIntruderSession", false).apply()
            }
        } else {
            // No face detected when PIN entered - assume non-owner for safety or just flag
            val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("IsIntruderSession", true).apply()
            Log.d("LockActivity", "PIN used, but no face detected to verify owner.")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val rotation = try {
                binding.viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0
            } catch (e: Exception) {
                android.view.Surface.ROTATION_0
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(rotation)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { face, bitmap, rotation ->
                        onFaceDetected(face, bitmap, rotation)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                
                if (mode == "STEALTH") {
                    // Start stealth monitoring
                    cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture, imageAnalyzer)
                    
                    // FAIL-SAFE: If no owner verified within 4 seconds, capture whatever is there
                    failSafeRunnable = Runnable {
                        if (!isProcessing && !isFinishing) {
                            Log.d("LockActivity", "Stealth timeout: Capturing evidence anyway.")
                            isProcessing = true
                            
                            // Capture the last frame we had
                            captureIntruderPhoto(lastDetectedBitmap, 0)
                            
                            handler.postDelayed({
                                finish()
                                overridePendingTransition(0, 0)
                            }, 1000)
                        }
                    }
                    handler.postDelayed(failSafeRunnable!!, 4000)
                } else {
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)
                }
            } catch (exc: Exception) {
                Log.e("LockActivity", "Use case binding failed", exc)
                finish()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private var lastDetectedFace: Face? = null
    private var lastDetectedBitmap: Bitmap? = null

    private fun onFaceDetected(face: Face?, bitmap: Bitmap?, rotation: Int) {
        if (bitmap != null) {
            lastDetectedBitmap = bitmap
        }
        if (face != null) {
            lastDetectedFace = face
        }
        
        if (mode == "REGISTER" || isProcessing || isFinishing) {
            return
        }

        if (bitmap != null) {
            if (face != null) {
                val isVerified = verifyFace(face, bitmap)
                Log.d("LockActivity", "Face detected. Verified: $isVerified")
                
                if (isVerified) {
                    isProcessing = true
                    failSafeRunnable?.let { handler.removeCallbacks(it) }
                    
                    if (mode == "APP_UNLOCK") {
                        runOnUiThread {
                            setResult(RESULT_OK)
                            finish()
                        }
                        return
                    }

                    if (mode != "STEALTH") {
                        runOnUiThread {
                            Toast.makeText(this, "Owner Verified", Toast.LENGTH_SHORT).show()
                            unlockSuccess()
                        }
                    } else {
                        finish()
                        overridePendingTransition(0, 0)
                    }
                } else {
                    Log.d("LockActivity", "Intruder detected. Capture triggered.")
                    isProcessing = true
                    failSafeRunnable?.let { handler.removeCallbacks(it) }
                    captureIntruderPhoto(bitmap, rotation)
                    
                    if (mode == "STEALTH" || mode == "APP_UNLOCK") {
                        handler.postDelayed({
                            if (!isFinishing) {
                                if (mode == "STEALTH") {
                                    finish()
                                    overridePendingTransition(0, 0)
                                } else {
                                    isProcessing = false
                                }
                            }
                        }, 1500)
                    } else {
                        handler.postDelayed({ isProcessing = false }, 2000)
                    }
                }
            } else if (mode == "STEALTH") {
                // In stealth mode, if we haven't seen a face yet but have frames,
                // we just keep updating lastDetectedBitmap. The fail-safe will handle it.
            }
        }
    }

    private fun stopCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e("LockActivity", "Error stopping camera", e)
        }
    }

    private fun verifyFace(face: Face, bitmap: Bitmap): Boolean {
        if (intent.getBooleanExtra("TEST_MODE", false)) {
            Log.d("LockActivity", "TEST MODE: Forcing verification failure")
            return false
        }
        if (!isOwnerRegistered) return false
        
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val savedEmbeddingString = sharedPrefs.getString("OwnerFaceEmbedding", null) ?: return false
        
        val faceBitmap = cropFace(bitmap, face) ?: return false
        val currentEmbedding = faceNetModel.getFaceEmbedding(faceBitmap)
        
        val savedEmbedding = savedEmbeddingString.split(",").map { it.toFloat() }.toFloatArray()
        
        val distance = faceNetModel.compare(currentEmbedding, savedEmbedding)
        Log.d("LockActivity", "Face distance: $distance (Risk: $riskLevel)")
        
        // Dynamic Threshold based on Risk Level
        val threshold = when(riskLevel) {
            "HIGH" -> 0.75f   // Very strict
            "MEDIUM" -> 0.80f // Standard
            else -> 0.85f     // More realistic for front camera
        }
        
        return distance < threshold
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap? {
        val rect = face.boundingBox
        val x = rect.left.coerceAtLeast(0)
        val y = rect.top.coerceAtLeast(0)
        val width = rect.width().coerceAtMost(bitmap.width - x)
        val height = rect.height().coerceAtMost(bitmap.height - y)
        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, x, y, width, height)
        } else {
            null
        }
    }

    private fun saveOwnerPattern() {
        val face = lastDetectedFace
        val bitmap = lastDetectedBitmap
        if (face == null || bitmap == null) {
            Toast.makeText(this, "No face detected!", Toast.LENGTH_SHORT).show()
            return
        }

        val faceBitmap = cropFace(bitmap, face) ?: return
        val embedding = faceNetModel.getFaceEmbedding(faceBitmap)
        val embeddingString = embedding.joinToString(",")
        
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("OwnerFaceEmbedding", embeddingString).apply()

        val file = File(getExternalFilesDir(null), "owner_face.dat")
        file.writeText("REGISTERED") 
        
        Toast.makeText(this, "Face Registered Successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun captureIntruderPhoto(bitmap: Bitmap? = null, rotationDegrees: Int = 0) {
        Log.d("LockActivity", "captureIntruderPhoto called. Bitmap present: ${bitmap != null}")
        val timestamp = System.currentTimeMillis()
        
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                saveIntruderData(bitmap, rotationDegrees, timestamp, location?.latitude, location?.longitude)
            }.addOnFailureListener {
                saveIntruderData(bitmap, rotationDegrees, timestamp, null, null)
            }
        } else {
            saveIntruderData(bitmap, rotationDegrees, timestamp, null, null)
        }
    }

    private fun saveIntruderData(bitmap: Bitmap?, rotationDegrees: Int, timestamp: Long, lat: Double?, lon: Double?) {
        val dir = getExternalFilesDir(null)
        if (dir == null) {
            Log.e("LockActivity", "External files dir is null!")
            return
        }
        val locSuffix = if (lat != null && lon != null) "_loc_${lat}_${lon}" else ""
        val photoFile = File(dir, "intruder_${timestamp}${locSuffix}.jpg")
        Log.d("LockActivity", "Saving intruder data to: ${photoFile.absolutePath}")
        
        if (bitmap != null) {
            // Use background executor for image processing and saving
            cameraExecutor.execute {
                try {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    // Flip horizontally for front camera
                    matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                    
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    
                    java.io.FileOutputStream(photoFile).use { out ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    Log.d("LockActivity", "Intruder photo saved: ${photoFile.absolutePath}")
                    
                    if (intent.getBooleanExtra("TEST_MODE", false)) {
                        runOnUiThread {
                            Toast.makeText(this@LockActivity, "Test Photo Captured!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LockActivity", "Failed to save intruder bitmap", e)
                }
            }
        } else {
            // Fallback to standard ImageCapture if no bitmap provided
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            imageCapture?.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("LockActivity", "Intruder photo saved via ImageCapture: ${photoFile.absolutePath}")
                    if (intent.getBooleanExtra("TEST_MODE", false)) {
                        runOnUiThread {
                            Toast.makeText(this@LockActivity, "Test Photo Captured!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e("LockActivity", "ImageCapture failed", exc)
                }
            })
        }
    }

    private fun applyRiskLevelAuth() {
        when (riskLevel) {
            "HIGH" -> {
                binding.cameraCard.setStrokeColor(ContextCompat.getColorStateList(this, R.color.error))
                binding.tvMessage.text = getString(R.string.risk_high)
                binding.tvMessage.setTextColor(ContextCompat.getColor(this, R.color.error))
            }
            "MEDIUM" -> {
                binding.cameraCard.setStrokeColor(ContextCompat.getColorStateList(this, R.color.amber_500))
                binding.tvMessage.text = getString(R.string.risk_medium)
                binding.tvMessage.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            else -> {
                binding.cameraCard.setStrokeColor(ContextCompat.getColorStateList(this, R.color.primary))
                binding.tvMessage.text = getString(R.string.auth_face)
                binding.tvMessage.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }

    private fun unlockSuccess() {
        riskEngine.resetFailedAttempts()
        val targetApp = intent.getStringExtra("TARGET_PACKAGE")
        if (targetApp != null) {
            AppLockService.lastUnlockedApp = targetApp
        } else {
            val currentApp = getForegroundApp()
            if (currentApp != null) {
                AppLockService.lastUnlockedApp = currentApp
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        
        if (stats != null && stats.isNotEmpty()) {
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            return sortedStats[0].packageName
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        failSafeRunnable?.let { handler.removeCallbacks(it) }
        // Don't shut down immediately to allow pending saves to finish
        handler.postDelayed({ cameraExecutor.shutdown() }, 5000)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required for face unlock", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private class FaceAnalyzer(private val listener: (Face?, Bitmap?, Int) -> Unit) : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                
                // Extract bitmap immediately to avoid issues with proxy closing or async delay
                val bitmap = try {
                    imageProxy.toBitmap()
                } catch (e: Exception) {
                    Log.e("LockActivity", "toBitmap failed", e)
                    null
                }

                detector.process(image)
                    .addOnSuccessListener { faces ->
                        val face = faces.firstOrNull()
                        listener(face, bitmap, rotation)
                    }
                    .addOnFailureListener {
                        // Still notify listener even if detection fails, to allow stealth capture
                        listener(null, bitmap, rotation)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
