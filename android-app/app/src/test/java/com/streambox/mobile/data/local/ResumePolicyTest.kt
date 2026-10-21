package com.streambox.mobile.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the local resume logic (Phase 3 scope item 9):
 * resume only when the media and timestamp are valid.
 */
class ResumePolicyTest {

    private val subjectIds = "resume-policy"

    @Test
    fun `below the minimum threshold does not resume`() {
        // 2.9s: user basically just opened the player — skip resume.
        assertNull(ResumePolicy.decide(positionMs = 2_900L, durationMs = 130_000L))
        assertNull(ResumePolicy.decide(positionMs = 0L, durationMs = 130_000L))
    }

    @Test
    fun `mid-stream position is resumed`() {
        assertEquals(45_000L, ResumePolicy.decide(45_000L, 130_000L))
        assertEquals(3_000L, ResumePolicy.decide(3_000L, 130_000L))
    }

    @Test
    fun `near the end does not resume`() {
        // <= 15s left is at most the end crawl — replay from the start next time.
        assertNull(ResumePolicy.decide(115_000L, 130_000L)) // exactly 15s left
        assertNull(ResumePolicy.decide(129_000L, 130_000L))
        assertNull(ResumePolicy.decide(130_000L, 130_000L))
        // 16s left is still resumable.
        assertEquals(114_000L, ResumePolicy.decide(114_000L, 130_000L))
    }

    @Test
    fun `corrupt timestamp past duration does not resume`() {
        assertNull(ResumePolicy.decide(positionMs = 999_999L, durationMs = 130_000L))
    }

    @Test
    fun `unknown duration still trusts a sane timestamp`() {
        // Live/unknown-length media: only the early-clip and no-data guards apply.
        assertEquals(30_000L, ResumePolicy.decide(30_000L, 0L))
        assertNull(ResumePolicy.decide(0L, 0L))
        assertNull(ResumePolicy.decide(-1L, 0L))
    }
}
