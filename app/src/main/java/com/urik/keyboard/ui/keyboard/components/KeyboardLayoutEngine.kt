package com.urik.keyboard.ui.keyboard.components

import android.graphics.PointF
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.Button
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout

class KeyboardLayoutEngine(
    private val touchCoordinateTransformer: TouchCoordinateTransformer,
    private val onPositionsUpdated: (
        keyCharPositions: Map<KeyboardKey.Character, PointF>,
        charToPosition: Map<Char, PointF>
    ) -> Unit
) {
    private val _keyViews = mutableListOf<Button>()
    private val keyPositions = mutableMapOf<Button, Rect>()
    private val keyMapping = mutableMapOf<Button, KeyboardKey>()
    private val keyCharacterPositions = mutableMapOf<KeyboardKey.Character, PointF>()
    private var numberRowBoundaryY: Float = -1f
    private val numberRowButtons = mutableSetOf<Button>()
    private var currentLayout: KeyboardLayout? = null

    val keyViews: List<Button> get() = _keyViews

    fun buildFromViewGroup(viewGroup: ViewGroup, layout: KeyboardLayout) {
        clear()
        currentLayout = layout
        extractButtonViews(viewGroup)
    }

    private fun extractButtonViews(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            when (val child = viewGroup.getChildAt(i)) {
                is Button -> {
                    _keyViews.add(child)
                    mapButtonToKey(child)
                }
                is ViewGroup -> extractButtonViews(child)
            }
        }
    }

    private fun mapButtonToKey(button: Button) {
        val layout = currentLayout ?: return
        val buttonIndex = _keyViews.indexOf(button)
        if (buttonIndex == -1) return
        var currentIndex = 0
        layout.rows.forEach { row ->
            row.forEach { key ->
                if (key is KeyboardKey.Spacer) return@forEach
                if (currentIndex == buttonIndex) {
                    keyMapping[button] = key
                    return
                }
                currentIndex++
            }
        }
    }

    fun applyPositions(rawPositions: Map<Button, Rect>, viewWidth: Int, viewHeight: Int) {
        keyPositions.clear()
        keyPositions.putAll(rawPositions)
        computeNumberRowBoundary()
        expandEdgeKeyHitAreas(viewWidth, viewHeight)
        buildCharacterPositionMap()
        val charToPosition = mutableMapOf<Char, PointF>()
        keyCharacterPositions.forEach { (key, pos) ->
            if (key.value.isNotEmpty()) {
                charToPosition[key.value.first()] = pos
            }
        }
        onPositionsUpdated(keyCharacterPositions, charToPosition)
    }

    private fun computeNumberRowBoundary() {
        numberRowButtons.clear()
        numberRowBoundaryY = -1f
        val layout = currentLayout ?: return
        if (layout.rows.size <= 1) return
        val firstRow = layout.rows[0]
        val charKeys = firstRow.filterIsInstance<KeyboardKey.Character>()
        if (charKeys.size != 10 || !charKeys.all { it.type == KeyboardKey.KeyType.NUMBER }) return
        var maxBottom = 0f
        keyMapping.forEach { (button, key) ->
            if (key is KeyboardKey.Character && key.type == KeyboardKey.KeyType.NUMBER) {
                numberRowButtons.add(button)
                val rect = keyPositions[button]
                if (rect != null) maxBottom = maxOf(maxBottom, rect.bottom.toFloat())
            }
        }
        if (maxBottom > 0f) numberRowBoundaryY = maxBottom
    }

    private fun expandEdgeKeyHitAreas(viewWidth: Int, viewHeight: Int) {
        if (_keyViews.isEmpty()) return
        val layout = currentLayout ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val minTouchTargetPx = 48 * 3
        val hasTopNumberRow = numberRowBoundaryY > 0
        layout.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, _ ->
                val button = _keyViews.getOrNull(getButtonIndexForKey(rowIndex, colIndex)) ?: return@forEachIndexed
                val rect = keyPositions[button] ?: return@forEachIndexed
                val isBottomRow = rowIndex == layout.rows.size - 1
                val isFirstCol = colIndex == 0
                val isLastCol = colIndex == row.size - 1
                val isAlphaRowBelowNumberRow = hasTopNumberRow && rowIndex == 1
                if (!isBottomRow && !isFirstCol && !isLastCol && !isAlphaRowBelowNumberRow) return@forEachIndexed
                val expandedRect = Rect(rect)
                if (isBottomRow) expandedRect.bottom = viewHeight
                if (isAlphaRowBelowNumberRow) expandedRect.top = numberRowBoundaryY.toInt()
                if (isFirstCol) {
                    val maxExpansion = rect.left.coerceAtMost(minTouchTargetPx / 2)
                    expandedRect.left = (rect.left - maxExpansion).coerceAtLeast(0)
                }
                if (isLastCol) {
                    val remainingSpace = viewWidth - rect.right
                    val maxExpansion = remainingSpace.coerceAtMost(minTouchTargetPx / 2)
                    expandedRect.right = (rect.right + maxExpansion).coerceAtMost(viewWidth)
                }
                keyPositions[button] = expandedRect
            }
        }
    }

    private fun getButtonIndexForKey(rowIndex: Int, colIndex: Int): Int {
        val layout = currentLayout ?: return -1
        var index = 0
        for (r in 0 until rowIndex) {
            index += layout.rows[r].count { it !is KeyboardKey.Spacer }
        }
        index += layout.rows[rowIndex].take(colIndex).count { it !is KeyboardKey.Spacer }
        return index
    }

    fun findKeyAt(x: Float, y: Float): KeyboardKey? {
        val normalizedPoint = touchCoordinateTransformer.normalizeForHitDetection(x, y)
        val nx = normalizedPoint.x
        val ny = normalizedPoint.y
        if (keyPositions.isEmpty() && _keyViews.isNotEmpty()) return findKeyAtDirect(nx, ny)
        var closestButton: Button? = null
        var closestSquaredDist = Float.MAX_VALUE
        val isInAlphaRegion = numberRowBoundaryY > 0 && ny > numberRowBoundaryY
        keyPositions.forEach { (button, rect) ->
            if (rect.contains(nx.toInt(), ny.toInt())) return keyMapping[button]
            if (isInAlphaRegion && button in numberRowButtons) return@forEach
            val dx = nx - rect.centerX()
            val dy = ny - rect.centerY()
            val squaredDist = dx * dx + dy * dy
            if (squaredDist < closestSquaredDist) {
                closestSquaredDist = squaredDist
                closestButton = button
            }
        }
        return if (closestButton != null) keyMapping[closestButton] else null
    }

    private fun findKeyAtDirect(normalizedX: Float, normalizedY: Float): KeyboardKey? {
        val tempRect = Rect()
        _keyViews.forEach { button ->
            tempRect.set(0, 0, button.width, button.height)
            if (tempRect.contains(normalizedX.toInt(), normalizedY.toInt())) {
                return keyMapping[button]
            }
        }
        return null
    }

    private fun buildCharacterPositionMap() {
        keyCharacterPositions.clear()
        keyMapping.forEach { (button, key) ->
            if (key is KeyboardKey.Character) {
                val rect = keyPositions[button]
                if (rect != null) {
                    keyCharacterPositions[key] = PointF(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }
        }
    }

    fun clear() {
        _keyViews.clear()
        keyPositions.clear()
        keyMapping.clear()
        keyCharacterPositions.clear()
        numberRowButtons.clear()
        numberRowBoundaryY = -1f
        currentLayout = null
    }
}
