package com.example.soundvisualizer.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pinned MIT Silero v6.2.3, single owner thread. Not a semantic label classifier. */
internal class SileroInference(context: Context) : AutoCloseable {
    companion object {
        const val SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    }
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input = ByteBuffer.allocateDirect(576 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val state = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val sr = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer().apply { put(0, 16_000L) }
    private val contextTail = FloatArray(64)

    init {
        val bytes = context.assets.open("ai/silero_vad.onnx").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == SHA256) { "Silero model hash mismatch" }
        session = OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(1); it.setInterOpNumThreads(1)
            env.createSession(bytes, it)
        }
    }

    fun reset() { for (i in 0 until 256) state.put(i, 0f); contextTail.fill(0f) }

    fun score(block: FloatArray): Float {
        require(block.size == 512)
        input.clear(); input.put(contextTail); input.put(block); input.rewind(); state.rewind(); sr.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 576)).use { audio ->
            OnnxTensor.createTensor(env, state, longArrayOf(2, 1, 128)).use { recurrent ->
                OnnxTensor.createTensor(env, sr, longArrayOf()).use { rate ->
                    session.run(mapOf("input" to audio, "state" to recurrent, "sr" to rate)).use { out ->
                        @Suppress("UNCHECKED_CAST") val probability = (out[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST") val next = out[1].value as Array<Array<FloatArray>>
                        for (layer in 0..1) for (i in 0 until 128) state.put(layer * 128 + i, next[layer][0][i])
                        block.copyInto(contextTail, 0, 448, 512)
                        require(probability.isFinite() && probability in 0f..1f)
                        return probability
                    }
                }
            }
        }
    }
    override fun close() { session.close() }
}
