package com.urik.keyboard.dictionary

internal class LevenshteinAutomaton(private val word: String, private val maxDistance: Int) {
    private val n = word.length

    data class State(val row: IntArray) {
        override fun equals(other: Any?): Boolean = other is State && row.contentEquals(other.row)
        override fun hashCode(): Int = row.contentHashCode()
    }

    fun start(): State = State(IntArray(n + 1) { it })

    fun step(state: State, ch: Char): State {
        val prev = state.row
        val next = IntArray(n + 1)
        next[0] = prev[0] + 1
        for (i in 1..n) {
            val cost = if (word[i - 1] == ch) 0 else 1
            next[i] = minOf(
                next[i - 1] + 1,
                prev[i] + 1,
                prev[i - 1] + cost
            )
        }
        return State(next)
    }

    fun isAccepting(state: State): Boolean = state.row[n] <= maxDistance

    fun accept(candidate: String): Int {
        var state = start()
        for (ch in candidate) state = step(state, ch)
        return if (isAccepting(state)) state.row[n] else -1
    }

    fun canReachFinal(state: State): Boolean = state.row.any { it <= maxDistance }
}
