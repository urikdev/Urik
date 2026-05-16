@file:Suppress("UnnecessaryParentheses")

package com.urik.keyboard.service

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap
import kotlin.math.ln
import kotlin.math.roundToLong

object TestUrikBuilder {
    private val MAGIC = byteArrayOf(0x55, 0x52, 0x49, 0x4B)
    private const val VERSION = 1
    private const val MAX_FREQ = 30_000_000.0
    private const val FREQ_BUCKETS = 254

    fun buildUrik(words: Map<String, Long>): ByteArray {
        val sortedWords = words.entries.sortedBy { it.key }
        val builder = DawgBuilder()
        for ((word, freq) in sortedWords) {
            builder.addWord(word, quantize(freq))
        }
        return builder.finish()
    }

    fun buildUrik(wordList: List<Pair<String, Long>>): ByteArray = buildUrik(wordList.toMap())

    fun buildUrikFromText(text: String): ByteArray {
        val words = mutableMapOf<String, Long>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(Regex("\\s+"), 2)
            if (parts.size >= 2) {
                words[parts[0]] = parts[1].toLongOrNull() ?: 1L
            }
        }
        return buildUrik(words)
    }

    private fun quantize(freq: Long): Byte {
        if (freq <= 0L) return 1
        val bucket = (FREQ_BUCKETS * ln(freq.toDouble()) / ln(MAX_FREQ)).roundToLong() + 1
        return bucket.coerceIn(1, FREQ_BUCKETS.toLong()).toByte()
    }

    private class DawgBuilder {
        private data class Arc(val label: Char, val freq: Byte, val isFinal: Boolean, var target: State?)
        private data class State(
            val arcs: MutableList<Arc> = mutableListOf(),
            var isFinal: Boolean = false,
            var freq: Byte = 0
        ) {
            fun contentKey(): String = buildString {
                append(if (isFinal) "1" else "0")
                append(freq.toInt())
                for (a in arcs) {
                    append(a.label)
                    append(a.freq.toInt())
                    append(if (a.isFinal) "1" else "0")
                    append(System.identityHashCode(a.target))
                }
            }
        }

        private val minimized = HashMap<String, State>()
        private val unchecked = ArrayDeque<Triple<State, Char, State>>()
        private var previousWord = ""
        private val root = State()
        private var wordCount = 0

        fun addWord(word: String, freqByte: Byte) {
            require(word >= previousWord) { "Words must be sorted. Got '$word' after '$previousWord'" }
            if (word == previousWord) return

            val commonPrefix = word.commonPrefixWith(previousWord).length
            minimize(commonPrefix)

            var current = if (unchecked.isEmpty()) root else unchecked.last().third
            for (i in commonPrefix until word.length) {
                val ch = word[i]
                val next = State()
                val isLast = i == word.length - 1
                current.arcs.add(Arc(ch, if (isLast) freqByte else 0, isLast, next))
                unchecked.addLast(Triple(current, ch, next))
                current = next
            }
            current.isFinal = true
            current.freq = freqByte
            previousWord = word
            wordCount++
        }

        private fun minimize(downTo: Int) {
            while (unchecked.size > downTo) {
                val (parent, label, child) = unchecked.removeLast()
                val key = child.contentKey()
                val existing = minimized[key]
                val arc = parent.arcs.last { it.label == label }
                if (existing != null) arc.target = existing else minimized[key] = child
            }
        }

        fun finish(): ByteArray {
            minimize(0)
            val visited = IdentityHashMap<State, Int>()
            val queue = ArrayDeque<State>()
            queue.add(root)
            var offset = 0
            val stateList = mutableListOf<State>()

            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (s in visited) continue
                visited[s] = offset
                stateList.add(s)
                offset += 1 + s.arcs.size * 6
                for (arc in s.arcs) {
                    arc.target?.let { if (it !in visited) queue.add(it) }
                }
            }

            val buf = ByteArrayOutputStream()
            val out = DataOutputStream(buf)

            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(wordCount)
            out.writeInt(stateList.size)

            for (state in stateList) {
                val header = (state.arcs.size and 0x7F) or (if (state.isFinal) 0x80 else 0x00)
                out.writeByte(header)
                for (arc in state.arcs) {
                    val labelCode = arc.label.code
                    out.writeByte((labelCode shr 8) and 0xFF)
                    out.writeByte(labelCode and 0xFF)
                    out.writeByte(arc.freq.toInt() and 0xFF)
                    val targetOffset = arc.target?.let { visited[it] } ?: 0
                    out.writeByte((targetOffset shr 16) and 0xFF)
                    out.writeByte((targetOffset shr 8) and 0xFF)
                    out.writeByte(targetOffset and 0xFF)
                }
            }

            out.flush()
            return buf.toByteArray()
        }
    }
}
