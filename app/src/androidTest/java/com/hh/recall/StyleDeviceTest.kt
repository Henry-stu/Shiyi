package com.hh.recall

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Component tests never open the user's database or preferences. Run in each device orientation. */
@RunWith(AndroidJUnit4::class)
class StyleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun themeSwitchHasAccessibleStateAndTouchTarget() {
        var dark by mutableStateOf(false)
        compose.setContent { RecallTheme(dark) { ThemeToggle(dark, { dark = it }) } }
        val toggle = compose.onNodeWithTag("themeToggle")
        toggle.assertIsOff().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(64.dp)
        toggle.performClick().assertIsOn()
        compose.runOnIdle { assertTrue(dark) }
        toggle.performClick().assertIsOff()
        compose.runOnIdle { assertFalse(dark) }
    }

    @Test fun disabledThemeSwitchKeepsItsState() {
        var dark by mutableStateOf(true)
        compose.setContent { RecallTheme(dark) { ThemeToggle(dark, { dark = it }, enabled = false) } }
        compose.onNodeWithTag("themeToggle").assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(dark) }
    }

    @Test fun searchAcceptsTextClearsAndKeepsNativeKeyboardSemantics() {
        var query by mutableStateOf("")
        compose.setContent { RecallTheme(false) { LibrarySearch(query, { query = it }) } }
        val search = compose.onNodeWithTag("search")
        search.assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("搜索学习资料").assertExists()
        search.performClick().performTextInput("学习资料")
        search.assertIsFocused().assertTextContains("学习资料")
        compose.onNodeWithTag("clearSearch").performClick()
        compose.runOnIdle { assertEquals("", query) }
        search.performImeAction()
        search.assertIsNotFocused()
        compose.onNodeWithTag("clearSearch").assertDoesNotExist()
    }

    @Test fun loadingSupportsCancellationAndAnnouncesProgress() {
        var cancelling by mutableStateOf(false)
        compose.setContent { RecallTheme(true) { Surface {
            ImportLoadingPane(if (cancelling) "正在取消，请稍候…" else "正在读取 · 2 / 4 页", cancelling, { cancelling = true })
        } } }
        compose.onNodeWithTag("importLoader").assertExists()
        compose.onNodeWithTag("importHint").assertTextEquals("正在读取 · 2 / 4 页")
        compose.onNodeWithTag("cancelImport").assertIsDisplayed().performClick().assertIsNotEnabled()
        compose.onNodeWithText("正在取消…").assertExists()
        compose.runOnIdle { assertTrue(cancelling) }
    }

    @Test fun responsiveComponentsFitAtLargeFontInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                RecallTheme(dark) { Surface(Modifier.fillMaxSize().testTag("styleSurface")) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        ThemeToggle(dark, { dark = it })
                        Spacer(Modifier.height(12.dp))
                        LibrarySearch("", {})
                        ImportLoadingPane("正在识别第 12 页…", false, {}, Modifier.weight(1f))
                    }
                } }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("themeToggle").assertIsDisplayed()
            compose.onNodeWithText("搜索学习资料").assertIsDisplayed()
            compose.onNodeWithTag("cancelImport").assertIsDisplayed()
            val root = compose.onNodeWithTag("styleSurface").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("themeToggle", "search", "cancelImport")) {
                val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag outside window", root.contains(bounds.topLeft) && bounds.right <= root.right && bounds.bottom <= root.bottom)
            }
            val path = File(compose.activity.cacheDir, "style-qa/${if (night) "dark" else "light"}-large-font.png")
            path.parentFile!!.mkdirs()
            path.outputStream().use { compose.onNodeWithTag("styleSurface").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun compactLoadingRemainsScrollableWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                RecallTheme(false) { Surface(Modifier.width(320.dp).height(240.dp)) {
                    ImportLoadingPane("正在识别第 12 页…", false, {})
                } }
            }
        }
        compose.onNodeWithTag("cancelImport").performScrollTo().assertIsDisplayed()
    }

    @Test fun loadingAnimationMovesAndStopsWhenCancelling() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        var cancelling by mutableStateOf(false)
        // Infinite animations are intentionally cancelled by the test policy while autoAdvance is true.
        compose.mainClock.autoAdvance = false
        compose.setContent { RecallTheme(false) { ImportLoadingPane("正在读取…", cancelling, {}) } }
        fun pixels(): IntArray {
            val bitmap = compose.onNodeWithTag("importLoader").captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        try {
            compose.mainClock.advanceTimeByFrame()
            val first = pixels()
            compose.mainClock.advanceTimeBy(192)
            assertFalse("Loading animation did not move", first.contentEquals(pixels()))
            compose.runOnUiThread { cancelling = true }
            compose.mainClock.advanceTimeBy(32)
            val stopped = pixels()
            compose.mainClock.advanceTimeBy(320)
            assertTrue("Cancellation should stop decorative motion", stopped.contentEquals(pixels()))
        } finally { compose.mainClock.autoAdvance = true }
    }
}
