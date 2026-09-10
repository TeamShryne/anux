package com.teamshryne.anux.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("anux_prefs")

data class AnuxSettings(
    val dnsPrimary: String = "8.8.8.8",
    val dnsSecondary: String = "8.8.4.4",
    val kernelRelease: String = "6.17.0-PRoot-Distro",
    val hostname: String = "localhost",
    val fontSize: Int = 14,
    /** 0 = system, 1 = dark, 2 = light */
    val themeMode: Int = 0,
)

class AnuxPrefs(private val context: Context) {
    private object Keys {
        val DNS1 = stringPreferencesKey("dns1")
        val DNS2 = stringPreferencesKey("dns2")
        val KERNEL = stringPreferencesKey("kernel")
        val HOSTNAME = stringPreferencesKey("hostname")
        val FONT = intPreferencesKey("font")
        val THEME = intPreferencesKey("theme")
    }

    val settings: Flow<AnuxSettings> = context.prefs.data.map { p ->
        AnuxSettings(
            dnsPrimary = p[Keys.DNS1] ?: "8.8.8.8",
            dnsSecondary = p[Keys.DNS2] ?: "8.8.4.4",
            kernelRelease = p[Keys.KERNEL] ?: "6.17.0-PRoot-Distro",
            hostname = p[Keys.HOSTNAME] ?: "localhost",
            fontSize = p[Keys.FONT] ?: 14,
            themeMode = p[Keys.THEME] ?: 0,
        )
    }

    suspend fun update(transform: (AnuxSettings) -> AnuxSettings) {
        val next = transform(settings.first())
        context.prefs.edit { e ->
            e[Keys.DNS1] = next.dnsPrimary
            e[Keys.DNS2] = next.dnsSecondary
            e[Keys.KERNEL] = next.kernelRelease
            e[Keys.HOSTNAME] = next.hostname
            e[Keys.FONT] = next.fontSize.coerceIn(8, 32)
            e[Keys.THEME] = next.themeMode.coerceIn(0, 2)
        }
    }
}
