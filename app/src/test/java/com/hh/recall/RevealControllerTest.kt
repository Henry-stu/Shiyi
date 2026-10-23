package com.hh.recall

import com.hh.recall.core.RevealController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevealControllerTest {
    @Test fun singleAnswerLastsFiveSeconds() = runTest {
        val controller = RevealController(backgroundScope)
        controller.toggle("a"); runCurrent(); advanceTimeBy(4999)
        assertEquals(setOf("a"), controller.revealed.value)
        advanceTimeBy(1); runCurrent(); assertTrue(controller.revealed.value.isEmpty())
    }
    @Test fun answersHaveIndependentTimers() = runTest {
        val controller = RevealController(backgroundScope)
        controller.toggle("a"); runCurrent(); advanceTimeBy(2000)
        controller.toggle("b"); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(setOf("b"), controller.revealed.value)
        advanceTimeBy(2000); runCurrent(); assertTrue(controller.revealed.value.isEmpty())
    }
    @Test fun showingAllCancelsOldTimers() = runTest {
        val controller = RevealController(backgroundScope)
        controller.toggle("a"); runCurrent(); advanceTimeBy(1000)
        controller.toggleAll(setOf("a", "b")); advanceTimeBy(10000); runCurrent()
        assertEquals(setOf("a", "b"), controller.revealed.value)
        controller.toggleAll(setOf("a", "b")); assertTrue(controller.revealed.value.isEmpty())
    }
    @Test fun tapAgainHidesAndReopeningStartsFreshTimer() = runTest {
        val controller = RevealController(backgroundScope)
        controller.toggle("a"); runCurrent(); advanceTimeBy(4000)
        controller.toggle("a"); assertTrue(controller.revealed.value.isEmpty())
        controller.toggle("a"); runCurrent(); advanceTimeBy(1500); runCurrent()
        assertEquals(setOf("a"), controller.revealed.value)
        advanceTimeBy(3500); runCurrent(); assertTrue(controller.revealed.value.isEmpty())
    }
    @Test fun leavingSessionPreventsOldTimerFromAffectingNewSession() = runTest {
        val controller = RevealController(backgroundScope)
        controller.toggle("a"); runCurrent(); advanceTimeBy(4000)
        controller.reset(); controller.toggle("a"); runCurrent()
        advanceTimeBy(1001); runCurrent(); assertEquals(setOf("a"), controller.revealed.value)
        controller.reset(); advanceTimeBy(10000); runCurrent(); assertTrue(controller.revealed.value.isEmpty())
    }
}
