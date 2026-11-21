package dev.xiaonuoboard.app.data

import kotlin.random.Random

/**
 * Parent gate: a simple single-digit multiplication question ("10 以内乘法"), or a 2-second long press.
 * Pure logic so it can be unit tested; the dialog lives in the UI layer.
 */
class ParentGate(private val random: Random = Random.Default) {

    data class Question(val a: Int, val b: Int) {
        val answer: Int get() = a * b
        val text: String get() = "$a × $b = ?"
    }

    var current: Question = newQuestion()
        private set

    var failedAttempts: Int = 0
        private set

    /** Factors 2..9 so the product is never trivial for a toddler to guess. */
    fun newQuestion(): Question {
        current = Question(random.nextInt(2, 10), random.nextInt(2, 10))
        return current
    }

    /** Checks the typed answer; on a wrong answer a fresh question is generated. */
    fun check(input: String): Boolean {
        val ok = input.trim().toIntOrNull() == current.answer
        if (!ok) {
            failedAttempts++
            newQuestion()
        } else {
            failedAttempts = 0
        }
        return ok
    }

    companion object {
        const val LONG_PRESS_MILLIS = 2000L

        fun longPressUnlocks(heldMillis: Long): Boolean = heldMillis >= LONG_PRESS_MILLIS
    }
}
