package io.github.audiz

import io.github.audiz.dsp.EqualizerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class EqualizerHeadroomTest {

    private fun preset(name: String) = EqualizerEngine.PRESETS.first { it.name == name }

    @Test
    fun testHeadroomEqualsLargestBoost() {
        val pop = EqualizerEngine.State(isEnabled = true, gains = preset("Pop").gains.copyOf())
        assertEquals(4f, EqualizerEngine.headroomDb(pop), 0.001f, "У «Поп» самый большой подъём +4 дБ")
    }

    @Test
    fun testNoHeadroomWhenDisabledOrOnlyCuts() {
        val disabled = EqualizerEngine.State(isEnabled = false, gains = preset("Pop").gains.copyOf())
        assertEquals(0f, EqualizerEngine.headroomDb(disabled), 0.001f)

        val cutsOnly = EqualizerEngine.State(isEnabled = true, gains = FloatArray(10) { -3f })
        assertEquals(0f, EqualizerEngine.headroomDb(cutsOnly), 0.001f, "Срезы не требуют запаса")
    }
}
