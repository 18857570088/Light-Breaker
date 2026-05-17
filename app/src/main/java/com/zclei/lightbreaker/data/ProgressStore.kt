package com.zclei.lightbreaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.progressDataStore by preferencesDataStore(name = "light_breaker_progress")

class ProgressStore(private val context: Context) {
    val stats: Flow<ProgressStats> =
        context.progressDataStore.data.map { prefs ->
            val xp = prefs[KEY_XP] ?: 0
            ProgressStats(
                xp = xp,
                level = levelForXp(xp),
                lastLeftDevice = prefs[KEY_LAST_LEFT],
                lastRightDevice = prefs[KEY_LAST_RIGHT],
            )
        }

    suspend fun addXp(amount: Int) {
        context.progressDataStore.edit { prefs ->
            prefs[KEY_XP] = (prefs[KEY_XP] ?: 0) + amount.coerceAtLeast(0)
        }
    }

    suspend fun rememberDevice(
        leftName: String?,
        rightName: String?,
    ) {
        context.progressDataStore.edit { prefs ->
            leftName?.let { prefs[KEY_LAST_LEFT] = it }
            rightName?.let { prefs[KEY_LAST_RIGHT] = it }
        }
    }

    private fun levelForXp(xp: Int): Int = (xp / 120) + 1

    private companion object {
        val KEY_XP = intPreferencesKey("xp")
        val KEY_LAST_LEFT = stringPreferencesKey("last_left_device")
        val KEY_LAST_RIGHT = stringPreferencesKey("last_right_device")
    }
}
