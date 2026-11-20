package com.laughing.musicplayer

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.ThemeMode
import com.laughing.musicplayer.core.util.PermissionOnboardingHelper
import com.laughing.musicplayer.core.util.SecureCredentialStore
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.ui.components.FirstLaunchPermissionsDialog
import com.laughing.musicplayer.ui.theme.LiquidGlassModalBorder
import com.laughing.musicplayer.ui.theme.LiquidGlassModalScrim
import com.laughing.musicplayer.ui.theme.LiquidGlassModalSurface
import com.laughing.musicplayer.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingAndLiquidGlassModalRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liquidGlassModalTokensUseCrispFrostedSurfaceBorderAndDarkScrim() {
        assertEquals(Color(0xFF1C1C1E).copy(alpha = 0.92f), LiquidGlassModalSurface)
        assertEquals(Color.White.copy(alpha = 0.15f), LiquidGlassModalBorder)
        assertEquals(Color.Black.copy(alpha = 0.68f), LiquidGlassModalScrim)
        assertEquals(0.92f, LiquidGlassModalSurface.alpha, 0.001f)
        assertEquals(0.15f, LiquidGlassModalBorder.alpha, 0.001f)
        assertEquals(0.68f, LiquidGlassModalScrim.alpha, 0.001f)
    }

    @Test
    fun oemAndSystemPermissionIntentsAreConstructedProperly() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(PermissionOnboardingHelper.isXiaomiOrHyperOsDevice(manufacturer = "Xiaomi", brand = "Redmi", miuiVersionProp = null, hyperOsProp = null))
        assertTrue(PermissionOnboardingHelper.isXiaomiOrHyperOsDevice(manufacturer = "POCO", brand = "POCO", miuiVersionProp = null, hyperOsProp = null))
        assertTrue(PermissionOnboardingHelper.isXiaomiOrHyperOsDevice(manufacturer = "Other", brand = "Other", miuiVersionProp = "V14", hyperOsProp = null))
        assertTrue(PermissionOnboardingHelper.isXiaomiOrHyperOsDevice(manufacturer = "Other", brand = "Other", miuiVersionProp = null, hyperOsProp = "OS1.0"))
        assertFalse(PermissionOnboardingHelper.isXiaomiOrHyperOsDevice(manufacturer = "Google", brand = "google", miuiVersionProp = null, hyperOsProp = null))

        val miuiIntent = PermissionOnboardingHelper.buildMiuiOtherPermissionsIntent(context)
        assertEquals("miui.intent.action.APP_PERM_EDITOR", miuiIntent.action)
        assertEquals(context.packageName, miuiIntent.getStringExtra("extra_pkgname"))

        val allFilesIntent = PermissionOnboardingHelper.buildAllFilesAccessIntent(context)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, allFilesIntent.action)
        assertEquals("package:${context.packageName}", allFilesIntent.dataString)
    }

    @Test
    fun firstLaunchDialogDisplaysAllPermissionStatusesXiaomiGuideAndPersistsDismiss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsDataStore(context, SecureCredentialStore(context))
        assertFalse(settings.isPermissionsOnboardingCompletedSync())

        var laterClicked = false
        composeRule.setContent {
            MusicPlayerTheme(themeMode = ThemeMode.LIQUID_GLASS) {
                FirstLaunchPermissionsDialog(
                    onDismissLater = {
                        laterClicked = true
                        runBlocking { settings.setPermissionsOnboardingCompleted(true) }
                    },
                    onCompleted = {},
                    isXiaomiDevice = true
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("first_launch_permissions_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_row_audio").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_status_audio").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_row_notifications").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_status_notifications").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_row_all_files").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_status_all_files").assertIsDisplayed()

        // Xiaomi / MIUI / HyperOS special permissions instructions
        composeRule.onNodeWithTag("perm_card_xiaomi_miui").assertIsDisplayed()
        composeRule.onNodeWithText("• Hiển thị trên màn hình khóa").assertIsDisplayed()
        composeRule.onNodeWithText("• Mở cửa sổ mới khi chạy nền").assertIsDisplayed()
        composeRule.onNodeWithText("• Tạo lối tắt màn hình chính").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_action_miui").assertIsDisplayed()

        // Action buttons
        composeRule.onNodeWithTag("onboarding_grant_btn").assertIsDisplayed()
        composeRule.onNodeWithText("Cấp quyền").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_later_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue(laterClicked)
        assertTrue(settings.isPermissionsOnboardingCompletedSync())
        runBlocking {
            assertTrue(settings.preferencesFlow.first().hasCompletedPermissionsOnboarding)
        }
    }

    @Test
    fun nonXiaomiDeviceHidesMiuiCardCleanly() {
        composeRule.setContent {
            MusicPlayerTheme(themeMode = ThemeMode.LIQUID_GLASS) {
                FirstLaunchPermissionsDialog(
                    onDismissLater = {},
                    onCompleted = {},
                    isXiaomiDevice = false
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("first_launch_permissions_dialog").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasTestTag("perm_card_xiaomi_miui")).fetchSemanticsNodes().isEmpty()
        )
    }
}
