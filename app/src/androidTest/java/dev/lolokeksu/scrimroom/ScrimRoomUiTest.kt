package dev.lolokeksu.scrimroom

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android UI smoke paths. No network or fabricated server approval.
 * Screenshot evidence is stored in app-specific external files and pulled by CI.
 */
@RunWith(AndroidJUnit4::class)
class ScrimRoomUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrimRoomTests")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)?.use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } ?: error("Failed to write test screenshot")
    }

    @Test fun navigationAndOfflineQueue() {
        compose.onNodeWithText("SCRIM ROOM").assertExists()
        compose.onNodeWithText("PILOT ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Сильные игроки.\nЧестный подбор.").assertExists()
        shot("01-home")
        compose.onNodeWithText("Очередь").performClick()
        compose.onNodeWithText("Очередь 5 × 5").assertIsDisplayed()
        compose.onNodeWithText("Вымышленные игроки").assertExists()
        shot("02-queue")
        compose.onNodeWithText("Профиль").performClick()
        compose.onNodeWithText("Профиль игрока").assertIsDisplayed()
        shot("03-profile")
        compose.onNodeWithText("Сессии").performClick()
        compose.onNodeWithText("Игровые сессии").assertIsDisplayed()
        compose.onNodeWithText("Для расписания нужна онлайн-заявка.").assertExists()
        shot("09-sessions-offline")
        compose.onNodeWithText("Матчи").performClick()
        compose.onNodeWithText("ИСТОРИЯ / ТОЛЬКО ДЕМО").assertExists()
        shot("04-empty-history")
    }

    @Test fun invalidRegistrationAndDisabledSubmission() {
        compose.onNodeWithText("Профиль").performClick()
        compose.onNodeWithText("Отправить заявку и скриншоты").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить локальный черновик").performScrollTo().performClick()
        compose.onNodeWithText("Укажи Riot ID вместе с тегом после #.").assertExists()
        shot("05-invalid-profile")
    }

    @Test fun demoResultAndPersistenceAfterActivityRecreation() {
        compose.onNodeWithText("Очередь").performClick()
        compose.onNodeWithText("Сформировать демо-матч").performScrollTo().performClick()
        compose.onNodeWithText("Демо · синие").assertIsDisplayed()
        compose.onNodeWithText("Демо · красные").assertExists()
        compose.onNodeWithText("Демо-матч сформирован").assertExists()
        shot("06-demo-teams")
        compose.onNodeWithText("Победили синие").performScrollTo().performClick()
        compose.onNodeWithText("Матчи").performClick()
        compose.onNodeWithText("Демо · синие").assertExists()
        shot("07-history")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Демо · синие").assertExists()
        shot("08-history-after-recreate")
    }
}
