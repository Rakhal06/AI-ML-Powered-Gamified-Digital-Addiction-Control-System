package com.softcontrol.ai

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TE-1: On-Device ML using TensorFlow Lite.
 * Loads model.tflite from assets and runs inference on-device.
 * Used as a fallback when the Flask backend is unreachable.
 *
 * The model takes 16 float features and outputs probabilities for 3 classes:
 * [addicted, distracted, focused] — sorted by LabelEncoder alphabetical order.
 */
class OnDeviceMLHelper(private val context: Context) {

    private var interpreter: Any? = null   // org.tensorflow.lite.Interpreter
    private var available = false

    companion object {
        private const val TAG        = "OnDeviceML"
        private const val MODEL_FILE = "model.tflite"
        private const val N_FEATURES = 16
        private const val N_CLASSES  = 3
        // LabelEncoder alphabetical order: addicted=0, distracted=1, focused=2
        private val CLASS_NAMES = arrayOf("addicted", "distracted", "focused")
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetFiles = context.assets.list("") ?: emptyArray()
            if (!assetFiles.contains(MODEL_FILE)) {
                Log.d(TAG, "model.tflite not found in assets — on-device ML unavailable")
                return
            }
            // Use reflection so it compiles even without TFLite on classpath
            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val mappedByteBuffer  = loadModelFile()
            interpreter = interpreterClass.getConstructor(java.nio.MappedByteBuffer::class.java)
                .newInstance(mappedByteBuffer)
            available = true
            Log.d(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.d(TAG, "TFLite load failed: ${e.message} — using server only")
            available = false
        }
    }

    private fun loadModelFile(): java.nio.MappedByteBuffer {
        val afd        = context.assets.openFd(MODEL_FILE)
        val inputStream = java.io.FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
            afd.startOffset, afd.declaredLength)
    }

    fun isAvailable(): Boolean = available

    /**
     * Run on-device inference.
     * @param features FloatArray of 16 values in the same order as FEATURES list in app.py
     * @return Pair(label, confidence) or null if model unavailable
     */
    fun predict(features: FloatArray): Pair<String, Float>? {
        if (!available || interpreter == null) return null
        if (features.size != N_FEATURES) return null
        try {
            val inputBuffer = ByteBuffer.allocateDirect(4 * N_FEATURES)
                .order(ByteOrder.nativeOrder())
            features.forEach { inputBuffer.putFloat(it) }

            val outputBuffer = Array(1) { FloatArray(N_CLASSES) }

            val runMethod = interpreter!!.javaClass.getMethod("run",
                Any::class.java, Any::class.java)
            runMethod.invoke(interpreter, inputBuffer, outputBuffer)

            val probs = outputBuffer[0]
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val label  = CLASS_NAMES[maxIdx]
            val conf   = probs[maxIdx]
            return Pair(label, conf)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            return null
        }
    }

    /**
     * Build the 16-feature array from raw values.
     * Feature order matches FEATURES list in app.py.
     */
    fun buildFeatureArray(
        timeSpent: Float,
        appSwitches: Int,
        hourOfDay: Int,
        violations: Int,
        sessionGap: Float,
        previousUsage: Float,
        focusSessions: Int,
        dayOfWeek: Int,
        locationType: String,
        dayType: String,
        batteryLevel: Int,
        headphoneConnected: Boolean
    ): FloatArray {
        val locEnc = when (locationType) { "home" -> 0f; "college" -> 1f; else -> 2f }
        val dtEnc  = if (dayType == "weekday") 0f else 1f
        val ii     = timeSpent * appSwitches
        val ds     = focusSessions * 10f - violations * 5f
        val up     = previousUsage / (sessionGap + 1f)
        val nf     = if (hourOfDay >= 22 || hourOfDay < 5) 1f else 0f
        val hc     = if (headphoneConnected) 1f else 0f
        return floatArrayOf(
            timeSpent, appSwitches.toFloat(), hourOfDay.toFloat(), violations.toFloat(),
            sessionGap, previousUsage, focusSessions.toFloat(), dayOfWeek.toFloat(),
            ii, ds, up, nf, locEnc, dtEnc, batteryLevel.toFloat(), hc
        )
    }

    fun close() {
        try {
            interpreter?.javaClass?.getMethod("close")?.invoke(interpreter)
        } catch (_: Exception) { }
    }
}