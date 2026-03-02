package com.urik.keyboard.ui.keyboard.components

/** Fixed-capacity ring buffer for swipe touch coordinates. */
class SwipePointRingBuffer {

    class Slot {
        var x: Float = 0f
        var y: Float = 0f
        var timestamp: Long = 0L
        var pressure: Float = 0f
        var velocity: Float = 0f

        fun reset() {
            x = 0f
            y = 0f
            timestamp = 0L
            pressure = 0f
            velocity = 0f
        }

        fun toSwipePoint(): SwipeDetector.SwipePoint =
            SwipeDetector.SwipePoint(
                x = x,
                y = y,
                timestamp = timestamp,
                pressure = pressure,
                velocity = velocity,
            )
    }

    private val slots = Array(CAPACITY) { Slot() }
    private var head = 0
    private var count = 0

    val size: Int get() = count

    fun write(x: Float, y: Float, timestamp: Long, pressure: Float, velocity: Float) {
        val slot = slots[head]
        slot.x = x
        slot.y = y
        slot.timestamp = timestamp
        slot.pressure = pressure
        slot.velocity = velocity

        head = (head + 1) and MASK
        if (count < CAPACITY) count++
    }

    fun peekLast(): SwipeDetector.SwipePoint? {
        if (count == 0) return null
        val index = (head - 1 + CAPACITY) and MASK
        return slots[index].toSwipePoint()
    }

    fun snapshot(): List<SwipeDetector.SwipePoint> {
        if (count == 0) return emptyList()

        val result = ArrayList<SwipeDetector.SwipePoint>(count)
        val tail = (head - count + CAPACITY) and MASK
        for (i in 0 until count) {
            val index = (tail + i) and MASK
            result.add(slots[index].toSwipePoint())
        }

        return result
    }

    fun reset() {
        for (slot in slots) {
            slot.reset()
        }
        head = 0
        count = 0
    }

    companion object {
        const val CAPACITY = 512
        private const val MASK = CAPACITY - 1
    }
}
