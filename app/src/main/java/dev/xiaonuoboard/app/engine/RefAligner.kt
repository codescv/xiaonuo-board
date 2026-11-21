package dev.xiaonuoboard.app.engine

/**
 * Forces a (possibly slightly drifted) AI colorization to be pixel-aligned with the line art:
 * every region is flat-filled with its dominant quantized color from [ref], and line pixels are
 * painted black. The result can be used both as the PiP reference and as the eyedropper source.
 */
object RefAligner {

    fun flatten(ref: IntArray, map: RegionMap, ink: BooleanArray, lineColor: Int = 0xFF111111.toInt()): IntArray {
        require(ref.size == map.labels.size)
        val n = map.regionCount + 1
        // Per-region histogram over 4096 quantized bins is too big for many regions; use a two-pass
        // approach: per region, sparse counting via a map of bin→count (regions are mostly flat).
        val counts = arrayOfNulls<HashMap<Int, IntArray>>(n)
        val labels = map.labels
        for (i in ref.indices) {
            val id = labels[i]
            if (id == 0) continue
            val c = ref[i]
            val bin = (((c shr 16) and 0xF0) shl 4) or ((c shr 8) and 0xF0) or ((c and 0xF0) shr 4)
            val m = counts[id] ?: HashMap<Int, IntArray>().also { counts[id] = it }
            // [count, sumR, sumG, sumB]
            val acc = m.getOrPut(bin) { IntArray(4) }
            acc[0]++
            acc[1] += (c shr 16) and 0xFF
            acc[2] += (c shr 8) and 0xFF
            acc[3] += c and 0xFF
        }
        val regionColor = IntArray(n) { 0xFFFFFFFF.toInt() }
        for (id in 1 until n) {
            val m = counts[id] ?: continue
            val best = m.values.maxByOrNull { it[0] } ?: continue
            val k = best[0]
            regionColor[id] = (0xFF shl 24) or ((best[1] / k) shl 16) or ((best[2] / k) shl 8) or (best[3] / k)
        }
        return IntArray(ref.size) { i ->
            when {
                ink[i] -> lineColor
                labels[i] == 0 -> lineColor
                else -> regionColor[labels[i]]
            }
        }
    }
}
