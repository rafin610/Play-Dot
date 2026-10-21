package com.streambox.mobile.data.local

import kotlinx.serialization.Serializable

/** Persisted playback position for one movie/episode. */
@Serializable
data class PlaybackPosition(
    val subjectId: String,
    val season: Int = 1,
    val episode: Int = 1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Pure resume policy (unit-tested) for Phase 3 scope item 9:
 * "resume playback only when the media and timestamp are valid".
 */
object ResumePolicy {

    const val MIN_RESUME_MS = 3_000L
    const val END_RESIDUE_MS = 15_000L

    /**
     * Returns the resume position, or null when resuming is pointless
     * (too early / at or past the end / unknown duration math).
     */
    fun decide(positionMs: Long, durationMs: Long): Long? = when {
        positionMs < MIN_RESUME_MS -> null
        durationMs <= 0 -> positionMs // live/unknown duration: trust the timestamp
        durationMs - positionMs <= END_RESIDUE_MS -> null
        positionMs > durationMs -> null
        else -> positionMs
    }
}
