package com.example.soundvisualizer.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelCacheIntegrityTest {

    @Test
    fun validity_rejectsMissingEmptyTruncatedAndSameSizeCorruptFiles() = withTempDir { dir ->
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)
        val expected = expectedFile("model.data", source)
        val cached = File(dir, expected.name)

        assertFalse(ModelCacheIntegrity.isValid(cached, expected))
        cached.writeBytes(byteArrayOf())
        assertFalse(ModelCacheIntegrity.isValid(cached, expected))
        cached.writeBytes(source.copyOf(source.size - 1))
        assertFalse(ModelCacheIntegrity.isValid(cached, expected))
        cached.writeBytes(byteArrayOf(6, 5, 4, 3, 2, 1))
        assertFalse(ModelCacheIntegrity.isValid(cached, expected))
        cached.writeBytes(source)
        assertTrue(ModelCacheIntegrity.isValid(cached, expected))
    }

    @Test
    fun copyIfInvalid_repairsCorruptCacheAndSkipsVerifiedCache() = withTempDir { dir ->
        val source = byteArrayOf(10, 20, 30, 40, 50)
        val expected = expectedFile("model.onnx", source)
        val cached = File(dir, expected.name)
        cached.writeBytes(ByteArray(source.size))

        assertTrue(ModelCacheIntegrity.copyIfInvalid(cached, expected) { ByteArrayInputStream(source) })
        assertArrayEquals(source, cached.readBytes())
        assertFalse(ModelCacheIntegrity.copyIfInvalid(cached, expected) { fail("verified cache must not re-copy") })

        cached.writeBytes(source.copyOf(source.size - 1))
        assertTrue(ModelCacheIntegrity.copyIfInvalid(cached, expected) { ByteArrayInputStream(source) })
        assertArrayEquals(source, cached.readBytes())
    }

    @Test
    fun copyIfInvalid_rejectsBadSourceWithoutReplacingExistingCache() = withTempDir { dir ->
        val source = byteArrayOf(10, 20, 30, 40)
        val expected = expectedFile("model.onnx", source)
        val cached = File(dir, expected.name)
        val corrupt = byteArrayOf(9, 9, 9, 9)
        cached.writeBytes(corrupt)

        try {
            ModelCacheIntegrity.copyIfInvalid(cached, expected) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
            fail("bad source digest must be rejected")
        } catch (_: IllegalStateException) {
            assertArrayEquals(corrupt, cached.readBytes())
            assertFalse(File(dir, ".${expected.name}.partial").exists())
        }
    }

    @Test
    fun bundleManifest_requiresBothVerifiedFilesAndRejectsMixedPair() = withTempDir { dir ->
        val onnx = byteArrayOf(1, 2, 3, 4)
        val data = byteArrayOf(5, 6, 7, 8, 9)
        val expected = listOf(expectedFile("yamnet.onnx", onnx), expectedFile("yamnet.data", data))
        File(dir, "yamnet.onnx").writeBytes(onnx)
        File(dir, "yamnet.data").writeBytes(data)

        assertFalse(ModelCacheIntegrity.isBundleValid(dir, expected, "bundle.manifest"))
        ModelCacheIntegrity.writeBundleManifest(dir, expected, "bundle.manifest")
        assertTrue(ModelCacheIntegrity.isBundleValid(dir, expected, "bundle.manifest"))

        File(dir, "yamnet.data").writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        assertFalse(ModelCacheIntegrity.isBundleValid(dir, expected, "bundle.manifest"))
    }

    @Test
    fun shippedYamnetAssets_matchTheCacheIntegritySpecifications() = withTempDir { dir ->
        for (expected in YamnetModelFiles.files) {
            val source = sequenceOf(
                File("src/main/assets/ai", expected.name),
                File("app/src/main/assets/ai", expected.name)
            ).firstOrNull { it.isFile } ?: error("Missing shipped asset: ${expected.name}")
            val copied = File(dir, expected.name)
            copied.writeBytes(source.readBytes())
            assertTrue("stale checksum for ${expected.name}", ModelCacheIntegrity.isValid(copied, expected))
        }
    }

    private fun expectedFile(name: String, bytes: ByteArray): ModelCacheIntegrity.ExpectedFile {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ModelCacheIntegrity.ExpectedFile(name, bytes.size.toLong(), sha256)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("model-cache-integrity").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
