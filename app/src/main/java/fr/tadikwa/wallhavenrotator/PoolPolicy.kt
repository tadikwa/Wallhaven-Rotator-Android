package fr.tadikwa.wallhavenrotator

object PoolPolicy {
    const val TARGET_SIZE = 24
    const val LOW_WATERMARK = 6
    const val HISTORY_LIMIT = 1000
    const val MAX_SEARCH_PAGES_PER_REFILL = 4

    fun shouldRefill(currentCount: Int): Boolean = currentCount <= LOW_WATERMARK
}
