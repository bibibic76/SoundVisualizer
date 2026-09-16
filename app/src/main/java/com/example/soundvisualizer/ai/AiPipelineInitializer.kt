package com.example.soundvisualizer.ai

/**
 * Owns inference resources until [createOwner] succeeds.
 *
 * The booster is optional: ordinary initialization failures are reported and the
 * owner is built with YAMNet only. Once [createOwner] returns, resource ownership
 * has moved to that owner. Any earlier failure closes resources in reverse order.
 */
internal object AiPipelineInitializer {
    inline fun <Y : AutoCloseable, B : AutoCloseable, R> create(
        createYamnet: () -> Y,
        createBooster: () -> B,
        onBoosterUnavailable: (Exception) -> Unit = {},
        createOwner: (Y, B?) -> R
    ): R {
        val yamnet = createYamnet()
        return yamnet.closeOnFailure { ownedYamnet ->
            val booster = try {
                createBooster()
            } catch (failure: Exception) {
                onBoosterUnavailable(failure)
                null
            }

            if (booster == null) {
                createOwner(ownedYamnet, null)
            } else {
                booster.closeOnFailure { ownedBooster ->
                    createOwner(ownedYamnet, ownedBooster)
                }
            }
        }
    }
}

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
