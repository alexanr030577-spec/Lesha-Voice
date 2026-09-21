package ru.lesha.voice

/** UI state is confined to the main thread; a process restart never resumes clicks. */
internal object SessionState {
    var active = false
        private set
    var message = "Готов к работе"
        private set
    val listeners = mutableSetOf<() -> Unit>()

    fun update(active: Boolean, message: String) {
        this.active = active
        this.message = message
        listeners.toList().forEach { it() }
    }
}
