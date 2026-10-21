import java.io.DataInputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Compares two compiled frontend jars on length-prefixed little-endian float windows from stdin. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: FrontendParityKt baseline.jar candidate.jar" }
    val loaders = args.map { URLClassLoader(arrayOf(File(it).toURI().toURL())) }
    try {
        val classes = loaders.map { it.loadClass("com.example.soundvisualizer.ai.QualcommSourceAudioPreprocessor") }
        val frontends = classes.map { it.getDeclaredConstructor().newInstance() }
        val methods = classes.map { it.getMethod("computeLogMelSpectrogram", FloatArray::class.java) }
        val input = DataInputStream(System.`in`.buffered())
        val hash = MessageDigest.getInstance("SHA-256")
        var windows = 0
        while (true) {
            val first = input.read()
            if (first == -1) break
            val header = byteArrayOf(first.toByte(), input.readByte(), input.readByte(), input.readByte())
            val samples = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            require(samples == 15_600) { "Invalid sample count: $samples" }
            val bytes = ByteArray(samples * 4)
            input.readFully(bytes)
            val mono = FloatArray(samples)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(mono)
            val baseline = methods[0].invoke(frontends[0], mono) as FloatArray
            val candidate = methods[1].invoke(frontends[1], mono) as FloatArray
            check(baseline.size == candidate.size)
            for (i in baseline.indices) check(baseline[i].toRawBits() == candidate[i].toRawBits()) {
                "window=$windows bin=$i baseline=${baseline[i]} candidate=${candidate[i]}"
            }
            val output = ByteBuffer.allocate(candidate.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            output.asFloatBuffer().put(candidate)
            hash.update(output.array())
            windows++
        }
        check(windows > 0) { "No windows supplied" }
        println("bitExactWindows=$windows sha256=${hash.digest().joinToString("") { "%02x".format(it) }}")
    } finally {
        loaders.forEach { it.close() }
    }
}
