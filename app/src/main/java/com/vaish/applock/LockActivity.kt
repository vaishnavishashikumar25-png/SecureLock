package com.vaish.applock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("MODE") ?: "VERIFY"
        isOwnerRegistered = File(getExternalFilesDir(null), "owner_face.dat").exists()

        if (mode == "REGISTER") {
            binding.tvMessage.text = "Position face to register"
            binding.btnUsePin.visibility = View.GONE
            binding.btnUnlock.text = "Capture"
        }

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
    }

    private fun verifyPin() {
        if (isProcessing) return
        val enteredPin = binding.etPinEntry.text.toString()
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val savedPin = sharedPrefs.getString("SecurityPin", null)

        if (savedPin == null) {
            Toast.makeText(this, "No PIN set. Setup PIN in main app.", Toast.LENGTH_SHORT).show()
        } else if (enteredPin == savedPin) {
            isProcessing = true
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            unlockSuccess()
        } else {
            binding.tilPin.error = "Incorrect PIN"
            captureIntruderPhoto() // Secret capture
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { face ->
                        onFaceDetected(face)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("LockActivity", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private var lastDetectedFace: Face? = null

    private fun onFaceDetected(face: Face?) {
        lastDetectedFace = face
        if (mode == "REGISTER" || isProcessing) {
            return
        }

        if (face != null) {
            if (verifyFace(face)) {
                isProcessing = true
                runOnUiThread {
                    Toast.makeText(this, "Owner Verified", Toast.LENGTH_SHORT).show()
                    unlockSuccess()
                }
            } else {
                // Potential intruder or different face
                Log.d("LockActivity", "Face match failed - possible intruder")
                // Only capture photo every 5 seconds to avoid flooding
                if (System.currentTimeMillis() % 5000 < 500) {
                   captureIntruderPhoto()
                }
            }
        }
    }

    private fun verifyFace(face: Face): Boolean {
        if (!isOwnerRegistered) return false
        
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        val savedSignature = sharedPrefs.getString("OwnerFaceSignature", null) ?: return false
        
        val currentSignature = getFaceSignature(face)
        val savedValues = savedSignature.split(",").map { it.toFloat() }
        val currentValues = currentSignature.split(",").map { it.toFloat() }
        
        // Compare landmark ratios (more stable than absolute positions)
        var diff = 0f
        for (i in savedValues.indices) {
            diff += kotlin.math.abs(savedValues[i] - currentValues[i])
        }
        
        Log.d("LockActivity", "Face diff: $diff")
        val isVerified = diff < 0.12f // Relaxed from 0.05, still tighter than 0.15
        return isVerified
    }

    private fun getFaceSignature(face: Face): String {
        // Use ratios of distances between landmarks to create a unique-ish signature
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: PointF(0f, 0f)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: PointF(0f, 0f)
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: PointF(0f, 0f)
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: PointF(0f, 0f)
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: PointF(0f, 0f)
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: PointF(0f, 0f)

        val eyeDist = dist(leftEye, rightEye)
        val eyeToNose = dist(midPoint(leftEye, rightEye), nose)
        val noseToMouth = dist(nose, mouthBottom)
        val mouthWidth = dist(mouthLeft, mouthRight)

        // Ratios are scale-invariant
        val r1 = if (eyeDist > 0) eyeToNose / eyeDist else 0f
        val r2 = if (eyeDist > 0) noseToMouth / eyeDist else 0f
        val r3 = if (eyeDist > 0) mouthWidth / eyeDist else 0f
        
        return "$r1,$r2,$r3"
    }

    private fun midPoint(p1: PointF, p2: PointF): PointF {
        return PointF((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    }

    private fun dist(p1: PointF, p2: PointF): Float {
        return sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
    }

    private fun saveOwnerPattern() {
        val face = lastDetectedFace
        if (face == null) {
            Toast.makeText(this, "No face detected!", Toast.LENGTH_SHORT).show()
            return
        }

        val signature = getFaceSignature(face)
        val sharedPrefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("OwnerFaceSignature", signature).apply()

        val file = File(getExternalFilesDir(null), "owner_face.dat")
        file.writeText("REGISTERED") 
        
        Toast.makeText(this, "Face Registered Successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun captureIntruderPhoto() {
        val photoFile = File(getExternalFilesDir(null), "intruder_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d("LockActivity", "Intruder photo saved: ${photoFile.absolutePath}")
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e("LockActivity", "Photo capture failed: ${exc.message}", exc)
            }
        })
    }

    private fun unlockSuccess() {
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
        cameraExecutor.shutdown()
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
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)
    }

    private class FaceAnalyzer(private val listener: (Face?) -> Unit) : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        listener(faces.firstOrNull())
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
    }
}
