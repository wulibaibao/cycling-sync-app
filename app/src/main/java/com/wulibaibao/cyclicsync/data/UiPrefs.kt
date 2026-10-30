package com.wulibaibao.cyclicsync.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI 偏好设置。
 *
 * 存同步方向的选择 —— 每次重装 App 后不用重新勾选。
 */
internal class UiPrefs(context: Context) {
    companion object {
        private const val FILE = "cycling_sync_ui"
        private const val KEY_DIRECTIONS = "selected_directions"
        private const val KEY_LAST_DATE = "last_sync_date"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 已选同步方向（存名字） */
    fun loadDirections(): Set<String> =
        prefs.getStringSet(KEY_DIRECTIONS, null)?.toSet() ?: emptySet()

    suspend fun saveDirections(names: Set<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putStringSet(KEY_DIRECTIONS, names).apply()
    }

    /** 上次同步日期（epochDay） */
    fun loadLastDateEpochDay(): Long? =
        prefs.getLong(KEY_LAST_DATE, -1L).takeIf { it >= 0 }

    suspend fun saveLastDateEpochDay(epochDay: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_LAST_DATE, epochDay).apply()
    }
}
