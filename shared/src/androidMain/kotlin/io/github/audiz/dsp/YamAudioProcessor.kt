package io.github.audiz.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YamAudioProcessor(
    private val equalizerEngine: EqualizerEngine = EqualizerEngine,
    private val audioVisualizer: AudioVisualizer = AudioVisualizer
) : BaseAudioProcessor() {
    private var sampleRateHz: Int = -1
    private var tempByteArray = ByteArray(0)

    override fun isActive(): Boolean {
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        if (inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        if (sampleRateHz != inputAudioFormat.sampleRate) {
            sampleRateHz = inputAudioFormat.sampleRate
            equalizerEngine.setSampleRate(sampleRateHz.toFloat())
        }

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Читаем из inputBuffer в tempByteArray
        if (tempByteArray.size < remaining) {
            tempByteArray = ByteArray(remaining)
        }
        inputBuffer.get(tempByteArray, 0, remaining)

        // Обрабатываем ByteArray нашими Kotlin-эффектами
        equalizerEngine.processPcm(tempByteArray, remaining)

        // Отправляем на визуализацию
        audioVisualizer.processPcmChunk(tempByteArray, remaining)

        // Пишем обратно
        buffer.put(tempByteArray, 0, remaining)
        buffer.flip()
    }
}
