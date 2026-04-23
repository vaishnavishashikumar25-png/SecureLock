package com.vaish.applock

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.math.sqrt

class FaceNetModel(context: Context) {
    private var interpreter: Interpreter
    private val imageSize = 160
    private var outputSize = 512

    init {
        try {
            val modelBuffer = loadModelFile(context, "facenet.tflite")
            interpreter = Interpreter(modelBuffer)
            
            val outputShape = interpreter.getOutputTensor(0).shape()
            outputSize = outputShape[1]
            Log.d("FaceNetModel", "Detected model output size: $outputSize")
        } catch (e: Exception) {
            Log.e("FaceNetModel", "CRITICAL ERROR: Could not load TFLite model. Ensure facenet.tflite is a valid binary file in assets.", e)
            throw RuntimeException("Invalid Model File: Ensure you downloaded the RAW facenet.tflite file and not the HTML page.", e)
        }
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        val input = ByteBuffer.allocateDirect(1 * imageSize * imageSize * 3 * 4)
        input.order(ByteOrder.nativeOrder())
        
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
        val intValues = IntArray(imageSize * imageSize)
        resizedBitmap.getPixels(intValues, 0, imageSize, 0, 0, imageSize, imageSize)
        
        for (pixelValue in intValues) {
            input.putFloat(((pixelValue shr 16 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((pixelValue shr 8 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((pixelValue and 0xFF) - 127.5f) / 127.5f)
        }

        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    fun compare(embedding1: FloatArray, embedding2: FloatArray): Float {
        var distance = 0f
        for (i in embedding1.indices) {
            val diff = embedding1[i] - embedding2[i]
            distance += diff * diff
        }
        return sqrt(distance)
    }

    // L2 Normalization often helps with FaceNet models
    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (f in v) sum += f * f
        val mag = sqrt(sum)
        for (i in v.indices) v[i] = v[i] / mag
        return v
    }
}
