package com.example.soundvisualizer.ai

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Verifies and atomically refreshes filesystem copies of bundled files. */
internal object ModelCacheIntegrity {

    data class ExpectedFile(
        val name: String,
        val byteCount: Long,
        val sha256: String
    )

    fun isValid(file: File, expected: ExpectedFile): Boolean {
        return file.isFile && file.length() == expected.byteCount && sha256(file) == expected.sha256
    }

    /**
     * Ensures every expected file is independently verified before returning.
     *
     * [openSource] is called only for an invalid or missing destination. A successful return means
     * every member either passed its one initial digest check or was installed from bytes whose
     * size and digest were checked while copying.
     */
    fun ensureFiles(
        directory: File,
        expectedFiles: List<ExpectedFile>,
        openSource: (String) -> InputStream
    ): List<String> {
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create cache directory: $directory"
        }
        require(expectedFiles.map { it.name }.distinct().size == expectedFiles.size) {
            "Expected file names must be unique"
        }

        val replaced = ArrayList<String>(expectedFiles.size)
        for (expected in expectedFiles) {
            if (copyIfInvalid(File(directory, expected.name), expected) { openSource(expected.name) }) {
                replaced += expected.name
            }
        }
        return replaced
    }

    /**
     * Returns true when an invalid cache file was replaced. A confirmed-invalid destination is
     * removed before writing to avoid requiring space for two full copies. New bytes are written
     * only to a temporary file and atomically installed after their size and digest verify, so an
     * interrupted copy cannot become a valid-looking cache entry.
     */
    fun copyIfInvalid(
        destination: File,
        expected: ExpectedFile,
        openSource: () -> InputStream
    ): Boolean {
        val parent = destination.parentFile ?: error("Model cache destination has no parent: $destination")
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create model cache directory: $parent" }
        val temporary = File(parent, ".${destination.name}.partial")

        if (isValid(destination, expected)) {
            // A stale temporary is never an input. Its cleanup must not make a valid cache unusable.
            if (temporary.exists()) temporary.delete()
            return false
        }
        if (temporary.exists()) check(temporary.delete()) {
            "Unable to remove stale model cache temp: $temporary"
        }
        if (destination.exists()) check(destination.delete()) {
            "Unable to remove invalid model cache: $destination"
        }

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
            return true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class CopyDigest(val byteCount: Long, val sha256: String)

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
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
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
