package com.example.soundvisualizer.ai

/** Close [this] only when [block] fails, preserving the original failure. */
internal inline fun <T : AutoCloseable, R> T.closeOnFailure(block: (T) -> R): R {
    try {
        return block(this)
    } catch (failure: Throwable) {
        try {
            close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}
