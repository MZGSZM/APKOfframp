package com.mzgszm.apkofframp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val sweepMinutes: Int = 30,
    val settleSeconds: Int = 30,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val defaultKeep: Int = 3,
    val instantMode: Boolean = false,
    val showSystemApps: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val sweep = intPreferencesKey("sweep_minutes")
        val settle = intPreferencesKey("settle_seconds")
        val wifi = booleanPreferencesKey("wifi_only")
        val charging = booleanPreferencesKey("charging_only")
        val keep = intPreferencesKey("default_keep")
        val instant = booleanPreferencesKey("instant_mode")
        val system = booleanPreferencesKey("show_system_apps")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { fromPrefs(it) }

    private fun fromPrefs(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            sweepMinutes = (p[K.sweep] ?: d.sweepMinutes).coerceAtLeast(15),
            settleSeconds = (p[K.settle] ?: d.settleSeconds).coerceIn(5, 600),
            wifiOnly = p[K.wifi] ?: d.wifiOnly,
            chargingOnly = p[K.charging] ?: d.chargingOnly,
            defaultKeep = p[K.keep] ?: d.defaultKeep,
            instantMode = p[K.instant] ?: d.instantMode,
            showSystemApps = p[K.system] ?: d.showSystemApps,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(fromPrefs(p))
            p[K.sweep] = s.sweepMinutes
            p[K.settle] = s.settleSeconds
            p[K.wifi] = s.wifiOnly
            p[K.charging] = s.chargingOnly
            p[K.keep] = s.defaultKeep
            p[K.instant] = s.instantMode
            p[K.system] = s.showSystemApps
            p[K.theme] = s.theme.name
            p[K.dynamic] = s.dynamicColor
        }
    }
}
