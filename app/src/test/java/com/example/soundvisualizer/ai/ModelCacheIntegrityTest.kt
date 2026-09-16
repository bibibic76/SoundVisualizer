package com.example.soundvisualizer.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    fun ensureFiles_emptyDirectoryCopiesEveryFile() = withTempDir { dir ->
        val sources = testBundle()
        val expected = sources.map { (name, bytes) -> expectedFile(name, bytes) }
        val opened = mutableListOf<String>()

        val copied = ModelCacheIntegrity.ensureFiles(dir, expected) { name ->
            opened += name
            ByteArrayInputStream(sources.getValue(name))
        }

        assertEquals(expected.map { it.name }, copied)
        assertEquals(expected.map { it.name }, opened)
        for ((name, bytes) in sources) {
            assertArrayEquals(bytes, File(dir, name).readBytes())
            assertFalse(File(dir, ".$name.partial").exists())
        }
    }

    @Test
    fun ensureFiles_reusesLegacyNormalCacheWithoutOpeningAssetsAndCleansTemps() = withTempDir { dir ->
        val sources = testBundle()
        val expected = sources.map { (name, bytes) -> expectedFile(name, bytes) }
        for ((name, bytes) in sources) {
            File(dir, name).writeBytes(bytes)
            File(dir, ".$name.partial").writeBytes(byteArrayOf(99))
        }

        val copied = ModelCacheIntegrity.ensureFiles(dir, expected) { name ->
            error("verified cache must not open asset: $name")
        }

        assertTrue(copied.isEmpty())
        for ((name, bytes) in sources) {
            assertArrayEquals(bytes, File(dir, name).readBytes())
            assertFalse(File(dir, ".$name.partial").exists())
        }
    }

    @Test
    fun ensureFiles_repairsOnlySameSizeCorruptMember() = withTempDir { dir ->
        val sources = testBundle()
        val expected = sources.map { (name, bytes) -> expectedFile(name, bytes) }
        File(dir, "yamnet.onnx").writeBytes(sources.getValue("yamnet.onnx"))
        File(dir, "yamnet.data").writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        val opened = mutableListOf<String>()

        val copied = ModelCacheIntegrity.ensureFiles(dir, expected) { name ->
            opened += name
            assertFalse("confirmed-invalid cache must release its space before copy", File(dir, name).exists())
            ByteArrayInputStream(sources.getValue(name))
        }

        assertEquals(listOf("yamnet.data"), copied)
        assertEquals(listOf("yamnet.data"), opened)
        assertArrayEquals(sources.getValue("yamnet.onnx"), File(dir, "yamnet.onnx").readBytes())
        assertArrayEquals(sources.getValue("yamnet.data"), File(dir, "yamnet.data").readBytes())
    }

    @Test
    fun ensureFiles_repairsTruncatedMember() = withTempDir { dir ->
        val sources = testBundle()
        val expected = sources.map { (name, bytes) -> expectedFile(name, bytes) }
        File(dir, "yamnet.onnx").writeBytes(sources.getValue("yamnet.onnx"))
        File(dir, "yamnet.data").writeBytes(byteArrayOf(5, 6))
        val opened = mutableListOf<String>()

        val copied = ModelCacheIntegrity.ensureFiles(dir, expected) { name ->
            opened += name
            ByteArrayInputStream(sources.getValue(name))
        }

        assertEquals(listOf("yamnet.data"), copied)
        assertEquals(listOf("yamnet.data"), opened)
        assertArrayEquals(sources.getValue("yamnet.data"), File(dir, "yamnet.data").readBytes())
    }

    @Test
    fun ensureFiles_copyFailurePreservesVerifiedMemberAndLeavesNoIncompleteCache() = withTempDir { dir ->
        val sources = testBundle()
        val expected = sources.map { (name, bytes) -> expectedFile(name, bytes) }
        File(dir, "yamnet.onnx").writeBytes(sources.getValue("yamnet.onnx"))
        File(dir, "yamnet.data").writeBytes(byteArrayOf(9, 8, 7, 6, 5))

        try {
            ModelCacheIntegrity.ensureFiles(dir, expected) { name ->
                assertEquals("yamnet.data", name)
                FailingInputStream(byteArrayOf(5, 6))
            }
            fail("copy failure must fail the whole bundle")
        } catch (expectedFailure: IOException) {
            assertEquals("injected copy failure", expectedFailure.message)
        }

        assertArrayEquals(sources.getValue("yamnet.onnx"), File(dir, "yamnet.onnx").readBytes())
        assertFalse(File(dir, "yamnet.data").exists())
        assertFalse(File(dir, ".yamnet.data.partial").exists())
    }

    @Test
    fun copyIfInvalid_rejectsBadSourceWithoutInstallingIt() = withTempDir { dir ->
        val source = byteArrayOf(10, 20, 30, 40)
        val expected = expectedFile("model.onnx", source)
        val cached = File(dir, expected.name)
        cached.writeBytes(byteArrayOf(9, 9, 9, 9))

        try {
            ModelCacheIntegrity.copyIfInvalid(cached, expected) {
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            }
            fail("bad source digest must be rejected")
        } catch (_: IllegalStateException) {
            assertFalse(cached.exists())
            assertFalse(File(dir, ".${expected.name}.partial").exists())
        }
    }

    @Test
    fun copyIfInvalid_validationIoFailureDoesNotOpenAsset() = withTempDir { dir ->
        val source = byteArrayOf(10, 20, 30, 40)
        val expected = expectedFile("model.onnx", source)
        val unreadable = object : File(dir, expected.name) {
            override fun isFile(): Boolean = true
            override fun length(): Long = expected.byteCount
        }
        var assetOpened = false

        try {
            ModelCacheIntegrity.copyIfInvalid(unreadable, expected) {
                assetOpened = true
                ByteArrayInputStream(source)
            }
            fail("validation I/O failure must propagate")
        } catch (_: IOException) {
            assertFalse("validation failure must stop before asset copy", assetOpened)
        }
    }

    @Test
    fun shippedYamnetAssets_matchTheCacheIntegritySpecifications() {
        for (expected in YamnetModelFiles.files) {
            val source = sequenceOf(
                File("src/main/assets/ai", expected.name),
                File("app/src/main/assets/ai", expected.name)
            ).firstOrNull { it.isFile } ?: error("Missing shipped asset: ${expected.name}")

            assertEquals("Update byteCount for ${expected.name}", expected.byteCount, source.length())
            assertEquals("Update sha256 for ${expected.name}", expected.sha256, sha256(source))
            assertTrue("SHA-256 must use lowercase hex", expected.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    private fun testBundle(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "yamnet.onnx" to byteArrayOf(1, 2, 3, 4),
        "yamnet.data" to byteArrayOf(5, 6, 7, 8, 9)
    )

    private fun expectedFile(name: String, bytes: ByteArray): ModelCacheIntegrity.ExpectedFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        return ModelCacheIntegrity.ExpectedFile(name, bytes.size.toLong(), digest)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun withTempDir(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("model-cache-integrity").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FailingInputStream(private val prefix: ByteArray) : InputStream() {
        private var delivered = false

        override fun read(): Int = throw UnsupportedOperationException("bulk reads only")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered) throw IOException("injected copy failure")
            val count = minOf(length, prefix.size)
            prefix.copyInto(buffer, offset, 0, count)
            delivered = true
            return count
        }
    }
}
