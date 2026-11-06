package com.github.caiheyu.farview.session

/** Hysteresis: start at 720p; upgrade only after sustained headroom. */
class QualityPolicy {
    // Congestion arrives on the peer write thread while tick() reads these on IO.
    @Volatile private var stableSince = 0L
    @Volatile private var congestedAt = 0L
    @Volatile private var changedAt = 0L
    fun reset(now: Long) { stableSince = now; congestedAt = 0; changedAt = now }
    fun congestion(now: Long) { congestedAt = now; stableSince = now }
    fun target(now: Long, actual: Int, rtt: Long, available1080: Boolean): Int {
        if (now - changedAt < 10000) return actual
        if (actual == 1080 && (rtt > 250 || (congestedAt > 0 && now - congestedAt < 5000))) {
            changedAt = now; stableSince = now; return 720
        }
        if (rtt !in 1..120) stableSince = now
        if (actual == 720 && available1080 && rtt in 1..120 && now - stableSince >= 20000) {
            changedAt = now; return 1080
        }
        return actual
    }
}
