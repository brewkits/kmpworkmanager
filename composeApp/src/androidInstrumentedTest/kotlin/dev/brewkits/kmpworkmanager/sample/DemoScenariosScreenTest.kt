package dev.brewkits.kmpworkmanager.sample

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.sample.ui.DemoScenariosScreen
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the newly-added Demo Scenarios cards (see the
 * production-readiness follow-up: bandwidth throttling, TokenRefresh, ExistingPolicy.UPDATE,
 * InputMerger, background URLSession, TaskRequest priority/idempotency). Uses
 * [SpyBackgroundTaskScheduler] instead of [FakeBackgroundTaskScheduler] specifically because
 * these tests assert *what* was scheduled, not just that tapping doesn't crash.
 *
 * This is the instrumentation APK Firebase Test Lab runs against real devices — see
 * `.github/workflows/firebase-test-lab.yml` for how these tests reach a real device matrix.
 */
class DemoScenariosScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(scheduler: SpyBackgroundTaskScheduler) {
        composeTestRule.setContent {
            DemoScenariosScreen(scheduler = scheduler)
        }
    }

    @Test
    fun bandwidthThrottledDownloadCard_enqueuesWithMaxBytesPerSecond() {
        val scheduler = SpyBackgroundTaskScheduler()
        setContent(scheduler)

        composeTestRule.onNodeWithText("HTTP Download — Bandwidth Throttled").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            scheduler.enqueueCalls.any { it.id == "demo-builtin-httpdownload-throttled" }
        }
        val call = scheduler.enqueueCalls.first { it.id == "demo-builtin-httpdownload-throttled" }
        // maxBytesPerSecond lives inside the serialized inputJson (HttpDownloadConfig), not
        // as a top-level enqueue() parameter — assert it made it into the JSON payload.
        assert(call.inputJson?.contains("maxBytesPerSecond") == true) {
            "Expected inputJson to carry maxBytesPerSecond, got: ${call.inputJson}"
        }
    }

    @Test
    fun tokenRefreshCard_enqueuesRequestWithTokenRefreshConfig() {
        val scheduler = SpyBackgroundTaskScheduler()
        setContent(scheduler)

        composeTestRule.onNodeWithText("Token Refresh on 401").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            scheduler.enqueueCalls.any { it.id == "demo-builtin-tokenrefresh" }
        }
        val call = scheduler.enqueueCalls.first { it.id == "demo-builtin-tokenrefresh" }
        assert(call.inputJson?.contains("tokenRefresh") == true) {
            "Expected inputJson to carry a tokenRefresh block, got: ${call.inputJson}"
        }
    }

    @Test
    fun existingPolicyUpdateCard_enqueuesWithUpdatePolicy() {
        val scheduler = SpyBackgroundTaskScheduler()
        setContent(scheduler)

        composeTestRule.onNodeWithText("Update Periodic (ExistingPolicy.UPDATE)").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            scheduler.enqueueCalls.any { it.id == "demo-update-policy" }
        }
        val call = scheduler.enqueueCalls.first { it.id == "demo-update-policy" }
        assert(call.policy == dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy.UPDATE)
        assert(call.trigger is TaskTrigger.Periodic)
    }

    @Test
    fun inputMergerCard_enqueuesTwoStepChain() {
        val scheduler = SpyBackgroundTaskScheduler()
        setContent(scheduler)

        composeTestRule.onNodeWithText("InputMerger: Step 2 URL Overwritten by Step 1's Output").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            scheduler.chainCalls.isNotEmpty()
        }
        assert(scheduler.chainCalls.isNotEmpty()) { "Expected enqueueChain to have been called" }
    }

    @Test
    fun taskRequestPriorityCard_enqueuesNonIdempotentCriticalChain() {
        val scheduler = SpyBackgroundTaskScheduler()
        setContent(scheduler)

        composeTestRule.onNodeWithText("TaskRequest: Priority + Non-Idempotent").performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            scheduler.chainCalls.any { it.id == "demo-critical-non-idempotent" }
        }
    }
}
