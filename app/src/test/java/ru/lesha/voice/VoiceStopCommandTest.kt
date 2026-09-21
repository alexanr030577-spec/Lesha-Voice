package ru.lesha.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStopCommandTest {
    @Test fun acceptsSpellingCasePunctuationAndAnyCandidate() {
        listOf("Лёша", "леша", "ЛЁША", "ЛЕША", "Эй, Лёша!", "«леша»", "Лёша, остановись")
            .forEach { assertTrue(it, VoiceStopCommand.matches(listOf(it))) }
        assertTrue(VoiceStopCommand.matches(listOf("ошибка распознавания", "Лёша")))
    }

    @Test fun rejectsMissingResultsAndLargerWords() {
        assertFalse(VoiceStopCommand.matches(null))
        assertFalse(VoiceStopCommand.matches(emptyList()))
        listOf("", "Алёша", "АЛЕША", "Лёшам", "лешая", "Лёша123", "мой_Лёша", "Lesha")
            .forEach { assertFalse(it, VoiceStopCommand.matches(listOf(it))) }
    }
}
