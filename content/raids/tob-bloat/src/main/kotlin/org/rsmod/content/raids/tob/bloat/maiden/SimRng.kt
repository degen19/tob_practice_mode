package org.rsmod.content.raids.tob.bloat.maiden

/**
 * The sim's seeded RNG (verzikSim engine/rng.js): an xmur3 hash of the seed into sfc32's state. The same seed gives
 * the same numbers as the sim, draw for draw - so a raid here can be checked against the sim's, and replayed by seed.
 */
class SimRng(val seed: String) {
    private var a = 0
    private var b = 0
    private var c = 0
    private var d = 0

    init {
        var h = 1779033703 xor seed.length
        for (ch in seed) {
            h = (h xor ch.code) * -862048943
            h = (h shl 13) or (h ushr 19)
        }
        fun next(): Int {
            h = (h xor (h ushr 16)) * -2048144789
            h = (h xor (h ushr 13)) * -1028477387
            h = h xor (h ushr 16)
            return h
        }
        a = next()
        b = next()
        c = next()
        d = next()
        repeat(15) { random() }
    }

    /** A float in [0, 1). */
    fun random(): Double {
        val t = a + b + d
        d += 1
        a = b xor (b ushr 9)
        b = c + (c shl 3)
        c = (c shl 21) or (c ushr 11)
        c += t
        return (t.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    /** An integer in [lo, hi] (Python's randint). */
    fun randint(lo: Int, hi: Int): Int {
        require(hi >= lo) { "empty range in randint($lo, $hi)" }
        return lo + Math.floor(random() * (hi - lo + 1)).toInt()
    }
}
