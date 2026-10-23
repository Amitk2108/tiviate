package app.tiviate.player

/** "Stop watching in N minutes": playback stops and the app leaves when it is due. */
class SleepTimer(private val clock: () -> Long = System::currentTimeMillis) {
    private var endAt: Long? = null

    fun start(minutes: Int) {
        endAt = clock() + minutes * 60_000L
    }

    fun cancel() {
        endAt = null
    }

    /** Null when the timer is off. */
    fun remainingMs(): Long? = endAt?.let { (it - clock()).coerceAtLeast(0) }

    /** Whole minutes left, rounded up (shows "1 min" until it fires). */
    fun minutesLeft(): Int? = remainingMs()?.let { ((it + 59_999) / 60_000).toInt() }

    fun isDue(): Boolean = endAt?.let { clock() >= it } ?: false
}
