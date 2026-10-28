package com.mzgszm.apkofframp

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzgszm.apkofframp.data.ThemeMode
import com.mzgszm.apkofframp.ui.AppsScreen
import com.mzgszm.apkofframp.ui.HistoryScreen
import com.mzgszm.apkofframp.ui.SettingsScreen
import com.mzgszm.apkofframp.ui.theme.FallbackDark
import com.mzgszm.apkofframp.ui.theme.FallbackLight
import com.mzgszm.apkofframp.ui.theme.OfframpTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screens render in the light, dark, dynamic, and fallback schemes. */
@RunWith(AndroidJUnit4::class)
class ThemingTest {
    @get:Rule val rule = createComposeRule()

    private fun renderAll(mode: ThemeMode, dynamic: Boolean, check: @Composable () -> Unit = {}) {
        var screen by mutableStateOf(0)
        rule.setContent {
            OfframpTheme(mode, dynamic) {
                check()
                when (screen) {
                    0 -> AppsScreen(PaddingValues(), onAdd = {}, onOpen = {})
                    1 -> HistoryScreen(PaddingValues(), onOpen = {})
                    else -> SettingsScreen(PaddingValues(), onEditDestination = {})
                }
            }
        }
        rule.onNodeWithText("APK Offramp").assertIsDisplayed()
        screen = 1
        rule.onNodeWithText("History").assertIsDisplayed()
        screen = 2
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun fallbackLight() {
        renderAll(ThemeMode.LIGHT, dynamic = false) {
            assertEquals(FallbackLight.primary, MaterialTheme.colorScheme.primary)
        }
    }

    @Test fun fallbackDark() {
        renderAll(ThemeMode.DARK, dynamic = false) {
            assertEquals(FallbackDark.primary, MaterialTheme.colorScheme.primary)
        }
    }

    @Test fun dynamicLight() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        renderAll(ThemeMode.LIGHT, dynamic = true)
    }

    @Test fun dynamicDark() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        renderAll(ThemeMode.DARK, dynamic = true)
    }
}
