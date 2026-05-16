@file:Suppress("UnnecessaryParentheses")

package com.urik.keyboard.dictionary

import java.io.InputStream

class UrikDictionary(inputStream: InputStream) {
    private val data: ByteArray = inputStream.readBytes()

    val wordCount: Int
    val stateCount: Int
    private val stateTableOffset: Int = UrikFormat.HEADER_SIZE

    init {
        require(data.size >= UrikFormat.HEADER_SIZE) { "URIK file too small: ${data.size} bytes" }
        val magic = data.copyOfRange(0, 4)
        require(magic.contentEquals(UrikFormat.MAGIC)) { "Invalid URIK magic: ${magic.toList()}" }
        val version = readInt(4)
        require(version == UrikFormat.VERSION) { "Unsupported URIK version: $version" }
        wordCount = readInt(8)
        stateCount = readInt(12)
    }

    private fun readInt(offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    fun lookup(word: String): Boolean = getFreqByte(word) != null

    fun getFrequency(word: String): Long {
        val b = getFreqByte(word) ?: return 0L
        return UrikFormat.dequantizeFreq(b)
    }

    private fun getFreqByte(word: String): Int? {
        var stateOffset = stateTableOffset
        for ((idx, ch) in word.withIndex()) {
            val arc = findArc(stateOffset, ch) ?: return null
            stateOffset = stateTableOffset + arc.targetRelOffset
            if (idx == word.length - 1) {
                val isFinal = (data[stateOffset].toInt() and 0x80) != 0
                return if (isFinal) arc.freq else null
            }
        }
        return null
    }

    fun getCandidates(word: String, maxEditDistance: Int = 2): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        val auto = LevenshteinAutomaton(word, maxEditDistance)
        dfs(stateTableOffset, auto, auto.start(), StringBuilder(), results)
        return results.sortedBy { it.second }
    }

    private fun dfs(
        stateAbsOffset: Int,
        auto: LevenshteinAutomaton,
        autoState: LevenshteinAutomaton.State,
        path: StringBuilder,
        results: MutableList<Pair<String, Int>>
    ) {
        if (!auto.canReachFinal(autoState)) return

        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        val arcCount = stateHeader and 0x7F
        val isFinalDawg = (stateHeader and 0x80) != 0

        if (isFinalDawg && auto.isAccepting(autoState)) {
            results.add(path.toString() to autoState.row.last())
        }

        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            val targetHi = data[arcOffset + 3].toInt() and 0xFF
            val targetMid = data[arcOffset + 4].toInt() and 0xFF
            val targetLo = data[arcOffset + 5].toInt() and 0xFF
            val targetRelOffset = (targetHi shl 16) or (targetMid shl 8) or targetLo
            arcOffset += 6

            val nextAutoState = auto.step(autoState, label)
            path.append(label)
            dfs(stateTableOffset + targetRelOffset, auto, nextAutoState, path, results)
            path.deleteCharAt(path.length - 1)
        }
    }

    fun getWordsWithPrefix(prefix: String, maxResults: Int = 10): List<Pair<String, Long>> {
        var stateOffset = stateTableOffset
        var lastFreqByte = 0
        for (ch in prefix) {
            val arc = findArc(stateOffset, ch) ?: return emptyList()
            lastFreqByte = arc.freq
            stateOffset = stateTableOffset + arc.targetRelOffset
        }
        val results = mutableListOf<Pair<String, Long>>()
        collectWordsWithFreq(stateOffset, StringBuilder(prefix), lastFreqByte, results, maxResults)
        return results
    }

    private fun collectWordsWithFreq(
        stateAbsOffset: Int,
        path: StringBuilder,
        incomingFreqByte: Int,
        results: MutableList<Pair<String, Long>>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        val arcCount = stateHeader and 0x7F
        val isFinal = (stateHeader and 0x80) != 0

        if (isFinal) results.add(path.toString() to UrikFormat.dequantizeFreq(incomingFreqByte))
        if (results.size >= maxResults) return

        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            if (results.size >= maxResults) return
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            val freqByte = data[arcOffset + 2].toInt() and 0xFF
            val targetHi = data[arcOffset + 3].toInt() and 0xFF
            val targetMid = data[arcOffset + 4].toInt() and 0xFF
            val targetLo = data[arcOffset + 5].toInt() and 0xFF
            val targetRelOffset = (targetHi shl 16) or (targetMid shl 8) or targetLo
            arcOffset += 6

            path.append(label)
            collectWordsWithFreq(stateTableOffset + targetRelOffset, path, freqByte, results, maxResults)
            path.deleteCharAt(path.length - 1)
        }
    }

    private data class ArcInfo(val freq: Int, val targetRelOffset: Int)

    private fun findArc(stateAbsOffset: Int, ch: Char): ArcInfo? {
        val arcCount = data[stateAbsOffset].toInt() and 0x7F
        val target = ch.code
        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            if ((labelHi shl 8) or labelLo == target) {
                val freq = data[arcOffset + 2].toInt() and 0xFF
                val targetHi = data[arcOffset + 3].toInt() and 0xFF
                val targetMid = data[arcOffset + 4].toInt() and 0xFF
                val targetLo = data[arcOffset + 5].toInt() and 0xFF
                return ArcInfo(freq, (targetHi shl 16) or (targetMid shl 8) or targetLo)
            }
            arcOffset += 6
        }
        return null
    }
}
