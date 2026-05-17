@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import com.urik.keyboard.data.CustomKeyMappingRepository
import com.urik.keyboard.data.database.CustomKeyMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CustomKeyMappingServiceTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var repository: CustomKeyMappingRepository
    private lateinit var mappingsFlow: MutableStateFlow<List<CustomKeyMapping>>
    private lateinit var service: CustomKeyMappingService

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        repository = mock()
        mappingsFlow = MutableStateFlow(emptyList())

        whenever(repository.mappings).thenReturn(mappingsFlow)

        service = CustomKeyMappingService(repository, testScope)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    // parseSymbols

    @Test
    fun `parseSymbols single char returns single-element list`() {
        assertEquals(listOf("ǔ"), CustomKeyMappingService.parseSymbols("ǔ"))
    }

    @Test
    fun `parseSymbols delimiter-separated returns ordered list`() {
        val raw = "ǔ${CustomKeyMappingService.LONG_PRESS_DELIMITER}ū"
        assertEquals(listOf("ǔ", "ū"), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols trims whitespace from each segment`() {
        val raw = " ǔ ${CustomKeyMappingService.LONG_PRESS_DELIMITER} ū "
        assertEquals(listOf("ǔ", "ū"), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols discards blank segments`() {
        val d = CustomKeyMappingService.LONG_PRESS_DELIMITER
        val raw = "${d}ǔ${d}${d}ū$d"
        assertEquals(listOf("ǔ", "ū"), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols empty string returns empty list`() {
        assertEquals(emptyList<String>(), CustomKeyMappingService.parseSymbols(""))
    }

    @Test
    fun `parseSymbols blank-only string returns empty list`() {
        assertEquals(emptyList<String>(), CustomKeyMappingService.parseSymbols("   "))
    }

    @Test
    fun `parseSymbols delimiter-only returns empty list`() {
        val raw = "${CustomKeyMappingService.LONG_PRESS_DELIMITER}${CustomKeyMappingService.LONG_PRESS_DELIMITER}"
        assertEquals(emptyList<String>(), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols caps at 5 symbols`() {
        val d = CustomKeyMappingService.LONG_PRESS_DELIMITER
        val raw = "a${d}b${d}c${d}d${d}e${d}f${d}g"
        assertEquals(listOf("a", "b", "c", "d", "e"), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols NFC deduplicates precomposed and decomposed equivalents`() {
        // é precomposed (U+00E9) and é decomposed (e + U+0301) — same NFC result
        val precomposed = "é"
        val decomposed = "é"
        val raw = "$precomposed${CustomKeyMappingService.LONG_PRESS_DELIMITER}$decomposed"
        val result = CustomKeyMappingService.parseSymbols(raw)
        assertEquals(1, result.size)
        assertEquals(precomposed, result[0])
    }

    @Test
    fun `parseSymbols preserves order of distinct symbols`() {
        val d = CustomKeyMappingService.LONG_PRESS_DELIMITER
        val raw = "ū${d}ǔ${d}û"
        assertEquals(listOf("ū", "ǔ", "û"), CustomKeyMappingService.parseSymbols(raw))
    }

    @Test
    fun `parseSymbols legacy single char without delimiter returns single-element list`() {
        assertEquals(listOf("ū"), CustomKeyMappingService.parseSymbols("ū"))
    }

    // initialize / observe

    @Test
    fun `mappings is empty initially`() {
        assertTrue(service.mappings.value.isEmpty())
    }

    @Test
    fun `initialize starts observing repository`() = runTest {
        mappingsFlow.value =
            listOf(
                CustomKeyMapping.create("a", "@"),
                CustomKeyMapping.create("b", "#")
            )

        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, service.mappings.value.size)
        assertEquals(listOf("@"), service.mappings.value["a"])
        assertEquals(listOf("#"), service.mappings.value["b"])
    }

    @Test
    fun `initialize parses multi-symbol stored value`() = runTest {
        val raw = "ǔ${CustomKeyMappingService.LONG_PRESS_DELIMITER}ū"
        mappingsFlow.value = listOf(CustomKeyMapping.create("u", raw))

        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("ǔ", "ū"), service.mappings.value["u"])
    }

    @Test
    fun `initialize is idempotent`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))

        service.initialize()
        service.initialize()
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, service.mappings.value.size)
    }

    // getMapping

    @Test
    fun `getMapping returns list for mapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("@"), service.getMapping("a"))
    }

    @Test
    fun `getMapping returns null for unmapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(service.getMapping("b"))
    }

    @Test
    fun `getMapping is case insensitive`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("@"), service.getMapping("a"))
        assertEquals(listOf("@"), service.getMapping("A"))
    }

    // hasMapping

    @Test
    fun `hasMapping returns true for mapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(service.hasMapping("a"))
    }

    @Test
    fun `hasMapping returns false for unmapped key`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(service.hasMapping("b"))
    }

    @Test
    fun `hasMapping is case insensitive`() = runTest {
        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(service.hasMapping("A"))
    }

    // getAllMappings

    @Test
    fun `getAllMappings returns snapshot as list values`() = runTest {
        mappingsFlow.value =
            listOf(
                CustomKeyMapping.create("a", "@"),
                CustomKeyMapping.create("b", "#")
            )
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        val snapshot = service.getAllMappings()

        assertEquals(2, snapshot.size)
        assertEquals(listOf("@"), snapshot["a"])
        assertEquals(listOf("#"), snapshot["b"])
    }

    // getMappingCount

    @Test
    fun `getMappingCount returns correct count`() = runTest {
        mappingsFlow.value =
            listOf(
                CustomKeyMapping.create("a", "@"),
                CustomKeyMapping.create("b", "#"),
                CustomKeyMapping.create("c", "$")
            )
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, service.getMappingCount())
    }

    @Test
    fun `getMappingCount returns 0 when empty`() = runTest {
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, service.getMappingCount())
    }

    // refresh

    @Test
    fun `refresh updates from repository`() = runTest {
        whenever(repository.getAllMappingsAsMap()).thenReturn(
            mapOf("x" to "!", "y" to "?")
        )

        service.refresh()

        assertEquals(2, service.mappings.value.size)
        assertEquals(listOf("!"), service.mappings.value["x"])
        assertEquals(listOf("?"), service.mappings.value["y"])
    }

    // live updates

    @Test
    fun `mappings updates when repository emits new data`() = runTest {
        service.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, service.mappings.value.size)

        mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, service.mappings.value.size)
        assertEquals(listOf("@"), service.mappings.value["a"])
    }
}
