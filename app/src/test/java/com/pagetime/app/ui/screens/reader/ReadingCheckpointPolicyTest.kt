package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingCheckpointPolicyTest {
    @Test
    fun `does not interrupt before three minutes`() {
        assertFalse(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 179,
                progress = 0.2f,
                lastCheckpointProgress = -1f,
                generationInProgress = false
            )
        )
    }

    @Test
    fun `custom intensity controls the checkpoint interval`() {
        assertFalse(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 90,
                progress = 0.2f,
                lastCheckpointProgress = -1f,
                generationInProgress = false,
                intervalSeconds = 180
            )
        )
        assertTrue(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 90,
                progress = 0.2f,
                lastCheckpointProgress = -1f,
                generationInProgress = false,
                intervalSeconds = 90
            )
        )
    }

    @Test
    fun `requires meaningful new reading after a checkpoint`() {
        assertTrue(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 180,
                progress = 0.25f,
                lastCheckpointProgress = 0.20f,
                generationInProgress = false
            )
        )
        assertFalse(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 180,
                progress = 0.205f,
                lastCheckpointProgress = 0.20f,
                generationInProgress = false
            )
        )
    }

    @Test
    fun `does not start a second generation while one is running`() {
        assertFalse(
            ReadingCheckpointPolicy.shouldGenerate(
                creditedSeconds = 240,
                progress = 0.4f,
                lastCheckpointProgress = -1f,
                generationInProgress = true
            )
        )
    }
}
