package fr.tadikwa.wallhavenrotator

object CachePolicy {
    const val DEFAULT_LIMIT_MB = 250
    val ALLOWED_LIMITS_MB = listOf(100, 250, 500)
    private const val REFILL_STOP_PERCENT = 90L

    fun normalizeLimitMb(value: Int): Int =
        ALLOWED_LIMITS_MB.firstOrNull { it == value } ?: DEFAULT_LIMIT_MB

    fun limitBytes(limitMb: Int): Long =
        normalizeLimitMb(limitMb).toLong() * 1024L * 1024L

    fun refillStopBytes(limitMb: Int): Long =
        limitBytes(limitMb) * REFILL_STOP_PERCENT / 100L
}

data class CacheStats(
    val files: Int,
    val bytes: Long
)
