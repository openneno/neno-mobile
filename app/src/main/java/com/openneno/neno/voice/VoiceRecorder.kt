package com.openneno.neno.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min

/**
 * 录音: 16kHz 单声道 16 位 PCM, 直接写成 WAV 文件.
 * 与网页端保存的原始语音格式相同, 也是 DeepSeek 语音识别接受的格式.
 */
class VoiceRecorder(val file: File) {
    @Volatile private var running = false
    @Volatile private var cancelled = false
    private var job: Job? = null
    @Volatile private var samples = 0L

    val durationMs: Long get() = samples * 1000 / SAMPLE_RATE

    /**
     * 开始录音. onLevel(0~1) 用于显示音量, onLimit 在达到最长时长时调用(录音已自动停止, 调用方随后 stop).
     * 无法开始录音时抛出异常.
     */
    @SuppressLint("MissingPermission") // 调用方在开始前已经申请了录音权限
    fun start(scope: CoroutineScope, onLevel: (Float) -> Unit, onLimit: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("无法打开麦克风")
        }
        file.parentFile?.mkdirs()
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(ByteArray(HEADER_SIZE))
        record.startRecording()
        running = true
        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize / 2)
            val bytes = ByteArray(buffer.size * 2)
            val maxSamples = SAMPLE_RATE.toLong() * MAX_DURATION_MS / 1000
            try {
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val count = min(read.toLong(), maxSamples - samples).toInt()
                    var peak = 0
                    for (i in 0 until count) {
                        val v = buffer[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
                        peak = maxOf(peak, abs(v))
                    }
                    out.write(bytes, 0, count * 2)
                    samples += count
                    onLevel(min(1f, peak / 32768f * 1.6f))
                    if (samples >= maxSamples) {
                        running = false
                        onLimit()
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                writeHeader(out, samples)
                out.close()
                if (cancelled) file.delete()
            }
        }
    }

    /** 结束录音, 返回时长(毫秒), 文件已经写完 */
    suspend fun stop(): Long {
        running = false
        job?.join()
        return durationMs
    }

    /** 放弃这次录音 */
    fun cancel() {
        cancelled = true
        running = false
        if (job == null) file.delete()
    }

    private fun writeHeader(out: RandomAccessFile, samples: Long) {
        val dataSize = (samples * 2).toInt()
        val header = java.nio.ByteBuffer.allocate(HEADER_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // 单声道
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        out.seek(0)
        out.write(header)
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 语音识别单次最长 120 秒 */
        const val MAX_DURATION_MS = 120_000L
        private const val HEADER_SIZE = 44
    }
}
