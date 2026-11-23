package fi.bibbi88.borawatch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Simple vario beeper, same rules as the web lab. */
class Beeper(private val climb: () -> Double) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val rate = 22050
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 2048))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val n = rate / 100                     // 10 ms blocks
        val buf = ShortArray(n)
        var phase = 0.0; var beat = 0.0; var amp = 0.0
        while (running) {
            val v = climb()
            var freq = 600.0; var target = 0.0
            if (!v.isNaN() && v > 0.15) {
                freq = min(1600.0, 600.0 + 120.0 * v)
                val period = max(0.12, 0.55 - 0.07 * v)
                beat = (beat + 0.01 / period) % 1.0
                target = if (beat < 0.5) 0.35 else 0.0
            } else if (!v.isNaN() && v < -2.5) {
                freq = max(180.0, 320.0 + 20.0 * v); target = 0.25; beat = 0.0
            } else beat = 0.0
            for (i in 0 until n) {
                amp += (target - amp) * 0.02   // soft edges, no clicks
                phase += 2 * PI * freq / rate
                if (phase > 2 * PI) phase -= 2 * PI
                val sq = if (sin(phase) >= 0) 1.0 else -1.0
                buf[i] = (sq * amp * 12000).toInt().toShort()
            }
            track.write(buf, 0, n)
        }
        track.stop(); track.release()
    }
}
