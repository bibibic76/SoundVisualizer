package com.example.soundvisualizer.ai

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Verifies and atomically refreshes filesystem copies of bundled AI model assets. */
internal object ModelCacheIntegrity {

    data class ExpectedFile(
        val name: String,
        val byteCount: Long,
        val sha256: String
    )

    fun isValid(file: File, expected: ExpectedFile): Boolean {
        return runCatching {
            file.isFile && file.length() == expected.byteCount && sha256(file) == expected.sha256
        }.getOrDefault(false)
    }

    /** A bundle is usable only after every file and its completion manifest verify. */
    fun isBundleValid(directory: File, expectedFiles: List<ExpectedFile>, manifestName: String): Boolean {
        return runCatching {
            val manifest = File(directory, manifestName)
            manifest.isFile && manifest.readText() == manifestContents(expectedFiles) &&
                expectedFiles.all { expected -> isValid(File(directory, expected.name), expected) }
        }.getOrDefault(false)
    }

    /** Writes the completion manifest last, after every member has passed its digest check. */
    fun writeBundleManifest(directory: File, expectedFiles: List<ExpectedFile>, manifestName: String) {
        val destination = File(directory, manifestName)
        val temporary = File(directory, ".${manifestName}.partial")
        if (temporary.exists()) check(temporary.delete()) { "Unable to remove stale model manifest temp: $temporary" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(manifestContents(expectedFiles).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replace(destination, temporary)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * Returns true when an invalid cache file was replaced. The previous file is kept until a
     * complete replacement has been copied and verified, so an interrupted copy cannot become a
     * valid-looking cache entry.
     */
    fun copyIfInvalid(
        destination: File,
        expected: ExpectedFile,
        openSource: () -> InputStream
    ): Boolean {
        if (isValid(destination, expected)) return false
        val parent = destination.parentFile ?: error("Model cache destination has no parent: $destination")
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create model cache directory: $parent" }

        val temporary = File(parent, ".${destination.name}.partial")
        if (temporary.exists()) check(temporary.delete()) { "Unable to remove stale model cache temp: $temporary" }

        try {
            val copied = openSource().use { input ->
                FileOutputStream(temporary).use { output ->
                    copyAndDigest(input, output)
                }
            }
            check(copied.byteCount == expected.byteCount) {
                "Unexpected asset size for ${expected.name}: ${copied.byteCount}, expected ${expected.byteCount}"
            }
            check(copied.sha256 == expected.sha256) { "Unexpected asset digest for ${expected.name}" }
            replace(destination, temporary)
            check(isValid(destination, expected)) { "Model cache verification failed after copy: ${expected.name}" }
            return true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class CopyDigest(val byteCount: Long, val sha256: String)

    private fun manifestContents(expectedFiles: List<ExpectedFile>): String {
        return buildString {
            append("soundvisualizer-ai-model-cache-v1\n")
            for (expected in expectedFiles.sortedBy { it.name }) {
                append(expected.name).append('\t').append(expected.byteCount).append('\t')
                    .append(expected.sha256).append('\n')
            }
        }
    }

    private fun copyAndDigest(input: InputStream, output: FileOutputStream): CopyDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var byteCount = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            byteCount += read
        }
        output.fd.sync()
        return CopyDigest(byteCount, digest.digest().toHex())
    }

    private fun replace(destination: File, temporary: File) {
        if (temporary.renameTo(destination)) return
        if (destination.exists()) check(destination.delete()) { "Unable to replace corrupt model cache: $destination" }
        check(temporary.renameTo(destination)) { "Unable to install verified model cache: $destination" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
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
}

/** Expected bytes for the model bundle shipped in assets/ai. Update with every model bundle update. */
internal object YamnetModelFiles {
    val files = listOf(
        ModelCacheIntegrity.ExpectedFile(
            name = "yamnet.onnx",
            byteCount = 25_592L,
            sha256 = "290369c40886a4ae948f77671fff901023eec81484ac393465dfb1b342b1ca85"
        ),
        ModelCacheIntegrity.ExpectedFile(
            name = "yamnet.data",
            byteCount = 14_915_108L,
            sha256 = "aa05b5b196bdfd74fb59ae4cbba22578c5ab25b9e792e52867678844bcecc839"
        )
    )
}
