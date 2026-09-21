package ru.lesha.voice

internal object VoiceStopCommand {
    private val command = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}_])л[её]ша(?![\\p{L}\\p{M}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    fun matches(candidates: List<String>?): Boolean =
        candidates?.any(command::containsMatchIn) == true
}
