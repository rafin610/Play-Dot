package com.streambox.mobile.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streambox.mobile.MainActivity
import com.streambox.mobile.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoRenderingInstrumentedTest {
    @Test
    fun h264HlsRendersAndSupportsPlaybackAndTrackSelection() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val playerRef = AtomicReference<ExoPlayer>()
        val playerViewRef = AtomicReference<PlayerView>()
        val firstFrameRendered = CountDownLatch(1)

        try {
            scenario.onActivity { activity ->
                val playerView = activity.layoutInflater
                    .inflate(R.layout.player_view, null, false) as PlayerView
                val player = ExoPlayer.Builder(activity).build()
                player.addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            Log.i(TAG, "First video frame rendered from the public H.264 HLS sample.")
                            firstFrameRendered.countDown()
                        }

                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            Log.i(TAG, "Rendered sample video size: ${videoSize.width}x${videoSize.height}")
                        }
                    },
                )

                activity.setContentView(playerView)
                playerView.player = player
                playerRef.set(player)
                playerViewRef.set(playerView)
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(SAMPLE_HLS_URL)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
                player.prepare()
                player.play()
            }

            assertTrue(
                "Media3 did not render a first video frame from the public H.264 sample.",
                firstFrameRendered.await(FIRST_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            val videoTrackCount = AtomicInteger()
            val audioTrackCount = AtomicInteger()
            val lowestVideoIndex = AtomicInteger(-1)
            val firstSupportedAudioIndex = AtomicInteger(-1)
            val paused = AtomicBoolean()
            val positionBeforePause = AtomicLong()
            val seekPosition = AtomicLong()

            scenario.onActivity {
                val player = playerRef.get()
                val groups = player.currentTracks.groups
                val videoGroup = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                val audioGroup = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                assertNotNull("The HLS manifest did not expose a video track group.", videoGroup)
                assertNotNull("The HLS manifest did not expose an audio track group.", audioGroup)
                val supportedVideoGroup = requireNotNull(videoGroup)
                val supportedAudioGroup = requireNotNull(audioGroup)
                videoTrackCount.set(
                    (0 until supportedVideoGroup.length).count(supportedVideoGroup::isTrackSupported),
                )
                audioTrackCount.set(
                    (0 until supportedAudioGroup.length).count(supportedAudioGroup::isTrackSupported),
                )
                lowestVideoIndex.set(
                    (0 until supportedVideoGroup.length)
                        .filter(supportedVideoGroup::isTrackSupported)
                        .minByOrNull { supportedVideoGroup.getTrackFormat(it).height }
                        ?: -1,
                )
                firstSupportedAudioIndex.set(
                    (0 until supportedAudioGroup.length).first(supportedAudioGroup::isTrackSupported),
                )
                assertTrue("The HLS sample did not expose multiple video qualities.", videoTrackCount.get() > 1)
                assertTrue("The HLS sample did not expose an audio track.", audioTrackCount.get() > 0)

                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            supportedVideoGroup.mediaTrackGroup,
                            listOf(lowestVideoIndex.get()),
                        ),
                    )
                    .setOverrideForType(
                        TrackSelectionOverride(
                            supportedAudioGroup.mediaTrackGroup,
                            listOf(firstSupportedAudioIndex.get()),
                        ),
                    )
                    .build()
                assertTrue(
                    "Manual video quality selection was not applied.",
                    player.trackSelectionParameters.overrides.containsKey(
                        supportedVideoGroup.mediaTrackGroup,
                    ),
                )
                assertTrue(
                    "Manual audio selection was not applied.",
                    player.trackSelectionParameters.overrides.containsKey(
                        supportedAudioGroup.mediaTrackGroup,
                    ),
                )

                positionBeforePause.set(player.currentPosition)
                player.pause()
                paused.set(!player.isPlaying)
                player.seekTo(SEEK_POSITION_MS)
                seekPosition.set(player.currentPosition)
                player.play()
            }

            assertTrue("Pause did not stop playback.", paused.get())
            assertTrue("The seek was not applied.", seekPosition.get() >= SEEK_POSITION_MS - SEEK_TOLERANCE_MS)
            assertTrue("There were no selectable video tracks.", videoTrackCount.get() > 1)
            assertTrue("There were no selectable audio tracks.", audioTrackCount.get() > 0)

            val resumed = waitForPositionAfterSeek(playerRef.get(), scenario)
            assertTrue("Playback did not resume after seeking.", resumed)
            assertTrue(
                "Playback position did not advance after resume.",
                readPosition(playerRef.get(), scenario) > positionBeforePause.get(),
            )
        } finally {
            scenario.onActivity {
                playerViewRef.get()?.player = null
                playerRef.get()?.release()
            }
            scenario.close()
        }
    }

    private fun waitForPositionAfterSeek(
        player: ExoPlayer,
        scenario: ActivityScenario<MainActivity>,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (readPosition(player, scenario) > SEEK_POSITION_MS) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun readPosition(player: ExoPlayer, scenario: ActivityScenario<MainActivity>): Long {
        val position = AtomicLong()
        scenario.onActivity { position.set(player.currentPosition) }
        return position.get()
    }

    private companion object {
        const val TAG = "PlaybackInstrumentedTest"
        const val SAMPLE_HLS_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        const val FIRST_FRAME_TIMEOUT_SECONDS = 60L
        const val SEEK_POSITION_MS = 30_000L
        const val SEEK_TOLERANCE_MS = 5_000L
    }
}
