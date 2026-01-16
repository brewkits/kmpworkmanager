package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import kotlin.test.*

/**
 * Unit tests for WorkerManagerConfig.
 *
 * Tests the DI-agnostic service locator pattern for WorkerFactory registration.
 */
class WorkerManagerConfigTest {

    private class TestWorkerFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): Worker? = null
    }

    @BeforeTest
    fun setup() {
        // Reset before each test to ensure clean state
        WorkerManagerConfig.reset()
    }

    @AfterTest
    fun teardown() {
        // Clean up after each test
        WorkerManagerConfig.reset()
    }

    @Test
    fun `initialize sets factory successfully`() {
        val factory = TestWorkerFactory()

        WorkerManagerConfig.initialize(factory)

        assertTrue(WorkerManagerConfig.isInitialized())
        assertSame(factory, WorkerManagerConfig.getWorkerFactory())
    }

    @Test
    fun `initialize throws when already initialized`() {
        val factory1 = TestWorkerFactory()
        val factory2 = TestWorkerFactory()

        WorkerManagerConfig.initialize(factory1)

        assertFailsWith<IllegalStateException> {
            WorkerManagerConfig.initialize(factory2)
        }
    }

    @Test
    fun `getWorkerFactory throws when not initialized`() {
        assertFalse(WorkerManagerConfig.isInitialized())

        assertFailsWith<IllegalStateException> {
            WorkerManagerConfig.getWorkerFactory()
        }
    }

    @Test
    fun `isInitialized returns false before initialization`() {
        assertFalse(WorkerManagerConfig.isInitialized())
    }

    @Test
    fun `isInitialized returns true after initialization`() {
        WorkerManagerConfig.initialize(TestWorkerFactory())

        assertTrue(WorkerManagerConfig.isInitialized())
    }

    @Test
    fun `reset allows re-initialization`() {
        val factory1 = TestWorkerFactory()
        val factory2 = TestWorkerFactory()

        // First initialization
        WorkerManagerConfig.initialize(factory1)
        assertSame(factory1, WorkerManagerConfig.getWorkerFactory())

        // Reset and re-initialize
        WorkerManagerConfig.reset()
        assertFalse(WorkerManagerConfig.isInitialized())

        WorkerManagerConfig.initialize(factory2)
        assertSame(factory2, WorkerManagerConfig.getWorkerFactory())
    }

    @Test
    fun `reset clears initialization state`() {
        WorkerManagerConfig.initialize(TestWorkerFactory())
        assertTrue(WorkerManagerConfig.isInitialized())

        WorkerManagerConfig.reset()

        assertFalse(WorkerManagerConfig.isInitialized())
        assertFailsWith<IllegalStateException> {
            WorkerManagerConfig.getWorkerFactory()
        }
    }
}
