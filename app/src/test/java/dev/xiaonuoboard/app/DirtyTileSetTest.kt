package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.engine.DirtyRect
import dev.xiaonuoboard.app.engine.DirtyTileSet
import dev.xiaonuoboard.app.engine.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DirtyTileSetTest {

    private data class Drained(val i: Int, val l: Int, val t: Int, val r: Int, val b: Int)

    private fun DirtyTileSet.drainAll(): List<Drained> {
        val out = ArrayList<Drained>()
        drain { i, l, t, r, b -> out += Drained(i, l, t, r, b) }
        return out
    }

    @Test
    fun gridClipsEdgeTiles() {
        val g = TileGrid(1200, 1600, 200)
        assertEquals(6, g.cols)
        assertEquals(8, g.rows)
        assertEquals(48, g.count)
        val odd = TileGrid(250, 130, 100)
        assertEquals(3, odd.cols)
        assertEquals(2, odd.rows)
        val last = odd.count - 1
        assertEquals(200, odd.left(last))
        assertEquals(100, odd.top(last))
        assertEquals(50, odd.tileWidth(last))
        assertEquals(30, odd.tileHeight(last))
        assertEquals(100, odd.tileWidth(0))
    }

    @Test
    fun smallRectDirtiesOnlyItsTileWithExactSubRect() {
        val set = DirtyTileSet(TileGrid(1200, 1600, 200))
        set.mark(DirtyRect(210, 420, 230, 450))
        assertEquals(1, set.dirtyCount)
        val d = set.drainAll()
        assertEquals(listOf(Drained(2 * 6 + 1, 210, 420, 230, 450)), d)
        assertTrue(set.isEmpty)
        assertTrue(set.drainAll().isEmpty())
    }

    @Test
    fun rectSpanningTilesIsSplitPerTile() {
        val set = DirtyTileSet(TileGrid(400, 400, 200))
        set.mark(150, 190, 260, 210)
        val d = set.drainAll().sortedBy { it.i }
        assertEquals(
            listOf(
                Drained(0, 150, 190, 200, 200),
                Drained(1, 200, 190, 260, 200),
                Drained(2, 150, 200, 200, 210),
                Drained(3, 200, 200, 260, 210),
            ),
            d,
        )
    }

    @Test
    fun repeatedMarksGrowTheTileSubRect() {
        val set = DirtyTileSet(TileGrid(400, 400, 200))
        set.mark(10, 10, 20, 20)
        set.mark(50, 5, 60, 15)
        set.mark(12, 12, 14, 14)
        assertEquals(1, set.dirtyCount)
        assertEquals(listOf(Drained(0, 10, 5, 60, 20)), set.drainAll())
    }

    @Test
    fun emptyAndOutOfBoundsRectsAreClampedOrIgnored() {
        val set = DirtyTileSet(TileGrid(100, 100, 64))
        set.mark(DirtyRect.EMPTY)
        set.mark(50, 50, 50, 80)
        set.mark(200, 200, 300, 300)
        assertTrue(set.isEmpty)
        set.mark(-20, -20, 10, 10)
        assertEquals(listOf(Drained(0, 0, 0, 10, 10)), set.drainAll())
        set.mark(90, 90, 500, 500)
        assertEquals(listOf(Drained(3, 90, 90, 100, 100)), set.drainAll())
    }

    @Test
    fun markAllCoversEveryTileExactly() {
        val g = TileGrid(250, 130, 100)
        val set = DirtyTileSet(g)
        set.markAll()
        assertEquals(g.count, set.dirtyCount)
        for (d in set.drainAll()) {
            assertEquals(g.left(d.i), d.l)
            assertEquals(g.top(d.i), d.t)
            assertEquals(g.left(d.i) + g.tileWidth(d.i), d.r)
            assertEquals(g.top(d.i) + g.tileHeight(d.i), d.b)
        }
    }

    @Test
    fun drainedRectsAlwaysCoverEveryMarkedPixelAndStayInsideTheirTile() {
        val w = 333
        val h = 257
        val g = TileGrid(w, h, 64)
        val set = DirtyTileSet(g)
        val rnd = Random(42)
        repeat(50) {
            val marked = BooleanArray(w * h)
            repeat(rnd.nextInt(1, 12)) {
                val l = rnd.nextInt(-20, w)
                val t = rnd.nextInt(-20, h)
                val r = l + rnd.nextInt(0, 120)
                val b = t + rnd.nextInt(0, 120)
                set.mark(l, t, r, b)
                for (y in maxOf(0, t) until minOf(h, b)) for (x in maxOf(0, l) until minOf(w, r)) marked[y * w + x] = true
            }
            val covered = BooleanArray(w * h)
            val seen = HashSet<Int>()
            for (d in set.drainAll()) {
                assertTrue("tile drained twice", seen.add(d.i))
                assertTrue(d.l >= g.left(d.i) && d.r <= g.left(d.i) + g.tileWidth(d.i))
                assertTrue(d.t >= g.top(d.i) && d.b <= g.top(d.i) + g.tileHeight(d.i))
                assertTrue(d.r > d.l && d.b > d.t)
                for (y in d.t until d.b) for (x in d.l until d.r) covered[y * w + x] = true
            }
            for (i in marked.indices) if (marked[i]) assertTrue("pixel $i not uploaded", covered[i])
            assertFalse(set.isDirty(0))
            assertTrue(set.isEmpty)
        }
    }
}
