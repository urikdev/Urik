package com.urik.keyboard.ui.keyboard.components

import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.service.AdaptiveDimensions
import com.urik.keyboard.settings.CursorSpeed
import kotlin.math.abs
import kotlin.math.sqrt

class ActionKeyGestureHandler(
    private val onSpacebarCursorMove: (Int) -> Unit,
    private val onBackspaceSwipeDelete: () -> Unit,
    private val getAdaptiveDimensions: () -> AdaptiveDimensions?,
    private val getDensity: () -> Float,
    private val getCurrentCursorSpeed: () -> CursorSpeed
) {
    private var isGestureActive = false
    private var gestureKey: KeyboardKey.Action? = null
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureLastProcessedX = 0f
    private var gesturePrevX = 0f
    private var gesturePrevTime = 0L

    val isActive: Boolean get() = isGestureActive

    fun handleDown(key: KeyboardKey.Action?, x: Float, y: Float): Boolean {
        if (key == null) return false
        if (key.action != KeyboardKey.ActionType.SPACE && key.action != KeyboardKey.ActionType.BACKSPACE) return false
        gestureKey = key
        gestureStartX = x
        gestureStartY = y
        isGestureActive = false
        gestureLastProcessedX = x
        gesturePrevX = x
        gesturePrevTime = System.currentTimeMillis()
        return true
    }

    fun handleMove(x: Float, y: Float): Boolean {
        if (gestureKey == null) return false
        if (!isGestureActive) {
            val gestureThresholdDp = getAdaptiveDimensions()?.gestureThresholdDp ?: 20f
            val gestureThreshold = gestureThresholdDp * getDensity()
            val dx = x - gestureStartX
            val dy = y - gestureStartY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > gestureThreshold) {
                isGestureActive = true
            }
        }
        if (isGestureActive && gestureKey?.action == KeyboardKey.ActionType.SPACE) {
            processSpacebarCursorMovement(x)
        }
        return isGestureActive
    }

    fun handleUp(x: Float, y: Float) {
        if (gestureKey?.action == KeyboardKey.ActionType.BACKSPACE) {
            finalizeBackswipeGesture(x, y)
        }
        isGestureActive = false
        gestureKey = null
    }

    fun cancel() {
        isGestureActive = false
        gestureKey = null
    }

    private fun processSpacebarCursorMovement(x: Float) {
        val density = getDensity()
        val now = System.currentTimeMillis()
        val dt = (now - gesturePrevTime).coerceAtLeast(1)
        val velocityPxPerMs = abs(x - gesturePrevX) / dt.toFloat()
        gesturePrevX = x
        gesturePrevTime = now
        val velocityDpPerMs = velocityPxPerMs / density
        val accelerationMultiplier = when {
            velocityDpPerMs > 1.5f -> 3.0f
            velocityDpPerMs > 0.8f -> 2.0f
            velocityDpPerMs > 0.4f -> 1.4f
            else -> 1.0f
        }
        val baseSensitivity = getCurrentCursorSpeed().sensitivityDp * density
        val sensitivity = baseSensitivity / accelerationMultiplier
        val totalDx = x - gestureStartX
        val positionsToMove = (totalDx / sensitivity).toInt()
        val lastPositionsMoved = ((gestureLastProcessedX - gestureStartX) / sensitivity).toInt()
        val deltaPositions = positionsToMove - lastPositionsMoved
        if (deltaPositions != 0) {
            onSpacebarCursorMove(deltaPositions)
            gestureLastProcessedX = gestureStartX + positionsToMove * sensitivity
        }
    }

    private fun finalizeBackswipeGesture(x: Float, y: Float) {
        val density = getDensity()
        val dx = x - gestureStartX
        val dy = y - gestureStartY
        val absDx = abs(dx)
        val absDy = abs(dy)
        val minDistance = 30f * density
        if (dx < 0 && absDx > absDy && absDx > minDistance) {
            onBackspaceSwipeDelete()
        }
    }
}
