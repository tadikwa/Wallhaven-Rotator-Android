package fr.tadikwa.wallhavenrotator

object PoolPolicy {
    // Eight ready-to-use images = two hours of reserve at the minimum 15-minute
    // cadence, per active profile. Keeping the pool deliberately small prevents
    // burst-downloading dozens of full-resolution images and respects Wallhaven.
    const val TARGET_SIZE = 8
    const val LOW_WATERMARK = 2
    const val CACHE_MISS_TARGET_SIZE = 1
    const val HISTORY_LIMIT = 1000
    const val MAX_SEARCH_PAGES_PER_REFILL = 2

    fun shouldRefill(currentCount: Int): Boolean = currentCount <= LOW_WATERMARK
}
