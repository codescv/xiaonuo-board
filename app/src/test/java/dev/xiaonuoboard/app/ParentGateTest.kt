package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.data.ParentGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParentGateTest {

    @Test
    fun questionsAreSingleDigitMultiplicationsWithoutTrivialFactors() {
        val gate = ParentGate(Random(7))
        repeat(500) {
            val q = gate.newQuestion()
            assertTrue(q.a in 2..9 && q.b in 2..9)
            assertEquals(q.a * q.b, q.answer)
            assertTrue(q.text.contains("×"))
        }
    }

    @Test
    fun correctAnswerUnlocks() {
        val gate = ParentGate(Random(3))
        val answer = gate.current.answer
        assertTrue(gate.check(" $answer "))
        assertEquals(0, gate.failedAttempts)
    }

    @Test
    fun wrongAnswerRotatesQuestion() {
        val gate = ParentGate(Random(3))
        val before = gate.current
        assertFalse(gate.check((before.answer + 1).toString()))
        assertFalse(gate.check("abc"))
        assertFalse(gate.check(""))
        assertEquals(3, gate.failedAttempts)
    }

    @Test
    fun longPressNeedsTwoSeconds() {
        assertFalse(ParentGate.longPressUnlocks(1999))
        assertTrue(ParentGate.longPressUnlocks(2000))
    }
}
