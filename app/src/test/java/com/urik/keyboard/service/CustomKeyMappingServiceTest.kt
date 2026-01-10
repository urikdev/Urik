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

    @Test
    fun `mappings is empty initially`() {
        assertTrue(service.mappings.value.isEmpty())
    }

    @Test
    fun `initialize starts observing repository`() =
        runTest {
            mappingsFlow.value =
                listOf(
                    CustomKeyMapping.create("a", "@"),
                    CustomKeyMapping.create("b", "#"),
                )

            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, service.mappings.value.size)
            assertEquals("@", service.mappings.value["a"])
            assertEquals("#", service.mappings.value["b"])
        }

    @Test
    fun `initialize is idempotent`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))

            service.initialize()
            service.initialize()
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, service.mappings.value.size)
        }

    @Test
    fun `getMapping returns mapped symbol`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = service.getMapping("a")

            assertEquals("@", result)
        }

    @Test
    fun `getMapping returns null for unmapped key`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            val result = service.getMapping("b")

            assertNull(result)
        }

    @Test
    fun `getMapping is case insensitive`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            val lowercase = service.getMapping("a")
            val uppercase = service.getMapping("A")

            assertEquals("@", lowercase)
            assertEquals("@", uppercase)
        }

    @Test
    fun `hasMapping returns true for mapped key`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(service.hasMapping("a"))
        }

    @Test
    fun `hasMapping returns false for unmapped key`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(service.hasMapping("b"))
        }

    @Test
    fun `hasMapping is case insensitive`() =
        runTest {
            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(service.hasMapping("A"))
        }

    @Test
    fun `getAllMappings returns snapshot`() =
        runTest {
            mappingsFlow.value =
                listOf(
                    CustomKeyMapping.create("a", "@"),
                    CustomKeyMapping.create("b", "#"),
                )
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            val snapshot = service.getAllMappings()

            assertEquals(2, snapshot.size)
            assertEquals("@", snapshot["a"])
            assertEquals("#", snapshot["b"])
        }

    @Test
    fun `getMappingCount returns correct count`() =
        runTest {
            mappingsFlow.value =
                listOf(
                    CustomKeyMapping.create("a", "@"),
                    CustomKeyMapping.create("b", "#"),
                    CustomKeyMapping.create("c", "$"),
                )
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(3, service.getMappingCount())
        }

    @Test
    fun `getMappingCount returns 0 when empty`() =
        runTest {
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, service.getMappingCount())
        }

    @Test
    fun `refresh updates from repository`() =
        runTest {
            whenever(repository.getAllMappingsAsMap()).thenReturn(
                mapOf("x" to "!", "y" to "?"),
            )

            service.refresh()

            assertEquals(2, service.mappings.value.size)
            assertEquals("!", service.mappings.value["x"])
            assertEquals("?", service.mappings.value["y"])
        }

    @Test
    fun `mappings updates when repository emits new data`() =
        runTest {
            service.initialize()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, service.mappings.value.size)

            mappingsFlow.value = listOf(CustomKeyMapping.create("a", "@"))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, service.mappings.value.size)
            assertEquals("@", service.mappings.value["a"])
        }
}
