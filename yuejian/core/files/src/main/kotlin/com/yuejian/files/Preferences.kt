package com.yuejian.files

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuejian.model.ReadingPreferences
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("reading_settings")
class LocalReadingPreferences(private val context: Context) : ReadingPreferences {
    private val recentColor = stringPreferencesKey("recent_highlight_color")
    override val recentHighlightColor = context.settings.data.map { it[recentColor] ?: "gray" }
    override suspend fun setRecentHighlightColor(value: String) {
        require(value in setOf("gray","yellow","green","blue","pink","purple"))
        context.settings.edit { it[recentColor] = value }
    }
    private val dark = booleanPreferencesKey("pure_black_theme")
    private val answerScale = intPreferencesKey("answer_text_scale")
    private val panelWidth = floatPreferencesKey("answer_panel_width_dp")
    override val darkTheme = context.settings.data.map { it[dark] ?: false }
    override suspend fun setDarkTheme(dark: Boolean) { context.settings.edit { it[this.dark] = dark } }
    override val answerTextScale = context.settings.data.map { it[answerScale] ?: 1 }
    override suspend fun setAnswerTextScale(value: Int) { context.settings.edit { it[answerScale] = value.coerceIn(0, 2) } }
    override val answerPanelWidthDp = context.settings.data.map { it[panelWidth] ?: 400f }
    override suspend fun setAnswerPanelWidthDp(value: Float) { context.settings.edit { it[panelWidth] = value.coerceIn(320f, 1200f) } }
    private val floatX = floatPreferencesKey("floating_tools_x")
    private val floatY = floatPreferencesKey("floating_tools_y")
    override val floatingToolsX = context.settings.data.map { it[floatX] ?: -1f }
    override val floatingToolsY = context.settings.data.map { it[floatY] ?: -1f }
    override suspend fun setFloatingToolsPos(x: Float, y: Float) {
        context.settings.edit {
            it[floatX] = x.coerceIn(0f, 1f); it[floatY] = y.coerceIn(0f, 1f)
        }
    }
    override fun floatingToolsPosition(documentId: String) = context.settings.data.map { values ->
        val x = values[floatPreferencesKey("document_${documentId}_floating_x")] ?: values[floatX] ?: -1f
        val y = values[floatPreferencesKey("document_${documentId}_floating_y")] ?: values[floatY] ?: -1f
        if (x in 0f..1f && y in 0f..1f) x to y else -1f to -1f
    }
    override suspend fun setFloatingToolsPosition(documentId: String, x: Float, y: Float) {
        require(documentId.isNotBlank() && x.isFinite() && y.isFinite())
        context.settings.edit {
            it[floatPreferencesKey("document_${documentId}_floating_x")] = x.coerceIn(0f, 1f)
            it[floatPreferencesKey("document_${documentId}_floating_y")] = y.coerceIn(0f, 1f)
        }
    }
    override val cardTextScales = context.settings.data.map { values ->
        values.asMap().mapNotNull { (key, value) ->
            val scale = value as? Float
            if (key.name.startsWith("card_text_zoom_") && scale != null && scale.isFinite())
                key.name.removePrefix("card_text_zoom_") to scale.coerceIn(.8f, 2f)
            else null
        }.toMap()
    }
    override suspend fun setCardTextScale(cardId: String, scale: Float) {
        require(cardId.isNotBlank() && scale.isFinite())
        context.settings.edit {
            val key = floatPreferencesKey("card_text_zoom_$cardId")
            if (scale == 1f) it.remove(key) else it[key] = scale.coerceIn(.8f, 2f)
        }
    }
    private val keepThinkingKey = booleanPreferencesKey("keep_thinking")
    override val keepThinking = context.settings.data.map { it[keepThinkingKey] ?: false }
    override suspend fun setKeepThinking(value: Boolean) { context.settings.edit { it[keepThinkingKey] = value } }
}
