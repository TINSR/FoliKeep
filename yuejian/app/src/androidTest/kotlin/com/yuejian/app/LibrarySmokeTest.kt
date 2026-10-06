package com.yuejian.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LibrarySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun offlineLibraryAndThemeSurviveRecreation() {
        compose.onNodeWithText("FoliKeep").assertIsDisplayed()
        compose.onNodeWithText("导入资料").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
        val expectedTheme = if (compose.onAllNodesWithText("纯白主题").fetchSemanticsNodes().isNotEmpty()) "纯黑主题" else "纯白主题"
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(expectedTheme).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithText(expectedTheme).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("FoliKeep").assertIsDisplayed()
    }
}
