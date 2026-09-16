package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPipelineInitializerTest {

    private class FakeResource(
        private val closeFailure: Throwable? = null
    ) : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    private data class Owner(
        val yamnet: FakeResource,
        val booster: FakeResource?
    )

    @Test
    fun successfulInitialization_transfersBothResourcesWithoutClosingThem() {
        val yamnet = FakeResource()
        val booster = FakeResource()

        val owner = AiPipelineInitializer.create(
            createYamnet = { yamnet },
            createBooster = { booster },
            createOwner = ::Owner
        )

        assertSame(yamnet, owner.yamnet)
        assertSame(booster, owner.booster)
        assertEquals(0, yamnet.closeCount)
        assertEquals(0, booster.closeCount)
    }

    @Test
    fun boosterFailure_buildsOwnerWithYamnetOnly() {
        val yamnet = FakeResource()
        val boosterFailure = IllegalStateException("booster unavailable")
        var reportedFailure: Exception? = null

        val owner = AiPipelineInitializer.create(
            createYamnet = { yamnet },
            createBooster = { throw boosterFailure },
            onBoosterUnavailable = { reportedFailure = it },
            createOwner = ::Owner
        )

        assertSame(yamnet, owner.yamnet)
        assertNull(owner.booster)
        assertSame(boosterFailure, reportedFailure)
        assertEquals(0, yamnet.closeCount)
    }

    @Test
    fun ownerFailureAfterBothResources_closesBothWithoutMaskingCause() {
        val yamnet = FakeResource()
        val booster = FakeResource()
        val ownerFailure = IllegalArgumentException("owner failed")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            AiPipelineInitializer.create(
                createYamnet = { yamnet },
                createBooster = { booster },
                createOwner = { _, _ -> throw ownerFailure }
            )
        }

        assertSame(ownerFailure, thrown)
        assertEquals(1, booster.closeCount)
        assertEquals(1, yamnet.closeCount)
    }

    @Test
    fun ownerFailureAfterBoosterFailure_closesYamnet() {
        val yamnet = FakeResource()

        assertThrows(IllegalStateException::class.java) {
            AiPipelineInitializer.create(
                createYamnet = { yamnet },
                createBooster = { throw IllegalArgumentException("booster unavailable") },
                createOwner = { _, booster ->
                    assertNull(booster)
                    throw IllegalStateException("owner failed")
                }
            )
        }

        assertEquals(1, yamnet.closeCount)
    }

    @Test
    fun yamnetFailure_doesNotAttemptBoosterCreation() {
        val yamnetFailure = IllegalStateException("yamnet unavailable")
        var boosterCreateCount = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            AiPipelineInitializer.create(
                createYamnet = { throw yamnetFailure },
                createBooster = {
                    boosterCreateCount++
                    FakeResource()
                },
                createOwner = ::Owner
            )
        }

        assertSame(yamnetFailure, thrown)
        assertEquals(0, boosterCreateCount)
    }

    @Test
    fun cleanupFailure_isSuppressedOnOriginalFailure() {
        val cleanupFailure = IllegalStateException("close failed")
        val resource = FakeResource(cleanupFailure)
        val ownerFailure = IllegalArgumentException("owner failed")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            resource.closeOnFailure { _: FakeResource -> throw ownerFailure }
        }

        assertSame(ownerFailure, thrown)
        assertTrue(thrown.suppressed.contains(cleanupFailure))
        assertEquals(1, resource.closeCount)
    }
}
