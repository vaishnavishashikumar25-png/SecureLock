package com.vaish.applock

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.sqrt

class FaceNetModel(context: Context) {
    private var interpreter: Interpreter
    private val imageSize = 112
    private val outputSize = 192

    init {
        val model = context.assets.open("mobile_face_net.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(model.size)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(model)
        interpreter = Interpreter(buffer)
        
        val outputShape = interpreter.getOutputTensor(0).shape()
        Log.d("FaceNetModel", "Output shape: ${Arrays.toString(outputShape)}")
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
        return output[0]
    }

    fun compare(embedding1: FloatArray, embedding2: FloatArray): Float {
        var distance = 0f
        for (i in embedding1.indices) {
            val diff = embedding1[i] - embedding2[i]
            distance += diff * diff
        }
        return sqrt(distance)
    }
}
