package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bluetooth.MeshCrypto
import com.example.bluetooth.MeshIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshnet_prefs")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class NodeSettings(
    val identity: MeshIdentity = MeshCrypto.generateIdentity(),
    val nickname: String = "anon",
    val joinedChannels: List<String> = listOf("#mesh", "#general", "#sos"),
    val vibrateOnPing: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val PEER_ID = stringPreferencesKey("peer_id")
        val PUBLIC_KEY = stringPreferencesKey("public_key_b64")
        val PRIVATE_KEY = stringPreferencesKey("private_key_b64")
        val NICKNAME = stringPreferencesKey("nickname")
        val JOINED_CHANNELS = stringSetPreferencesKey("joined_channels")
        val VIBRATE_ON_PING = booleanPreferencesKey("vibrate_on_ping")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<NodeSettings> = context.dataStore.data.map { prefs ->
        val peerId = prefs[Keys.PEER_ID]
        val pub = prefs[Keys.PUBLIC_KEY]
        val priv = prefs[Keys.PRIVATE_KEY]
        val identity = if (!peerId.isNullOrBlank() && !pub.isNullOrBlank() && !priv.isNullOrBlank()) {
            MeshIdentity(peerId = peerId, publicKeyBase64 = pub, privateKeyBase64 = priv)
        } else {
            null
        }
        val resolvedIdentity = identity ?: ensureIdentityCreated()
        val rawChannels = prefs[Keys.JOINED_CHANNELS]?.toList()?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("#mesh", "#general", "#sos")
        val orderedChannels = (listOf("#mesh") + rawChannels.filter { it != "#mesh" }).distinct()

        val themeMode = prefs[Keys.THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM

        NodeSettings(
            identity = resolvedIdentity,
            nickname = prefs[Keys.NICKNAME]?.takeIf { it.isNotBlank() }
                ?: "anon_${resolvedIdentity.peerId.take(4)}",
            joinedChannels = orderedChannels,
            vibrateOnPing = prefs[Keys.VIBRATE_ON_PING] ?: true,
            themeMode = themeMode
        )
    }

    suspend fun ensureIdentityCreated(): MeshIdentity {
        val prefs = context.dataStore.data.first()
        val peerId = prefs[Keys.PEER_ID]
        val pub = prefs[Keys.PUBLIC_KEY]
        val priv = prefs[Keys.PRIVATE_KEY]
        if (!peerId.isNullOrBlank() && !pub.isNullOrBlank() && !priv.isNullOrBlank()) {
            return MeshIdentity(peerId, pub, priv)
        }
        val fresh = MeshCrypto.generateIdentity()
        context.dataStore.edit { editPrefs ->
            editPrefs[Keys.PEER_ID] = fresh.peerId
            editPrefs[Keys.PUBLIC_KEY] = fresh.publicKeyBase64
            editPrefs[Keys.PRIVATE_KEY] = fresh.privateKeyBase64
            if (editPrefs[Keys.NICKNAME].isNullOrBlank()) {
                editPrefs[Keys.NICKNAME] = "anon_${fresh.peerId.take(4)}"
            }
            if (editPrefs[Keys.JOINED_CHANNELS].isNullOrEmpty()) {
                editPrefs[Keys.JOINED_CHANNELS] = setOf("#mesh", "#general", "#sos")
            }
        }
        return fresh
    }

    suspend fun setNickname(nickname: String) {
        val clean = nickname.trim().replace(" ", "_").take(20)
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[Keys.NICKNAME] = clean
        }
    }

    suspend fun joinChannel(channelName: String): String {
        val normalized = normalizeChannel(channelName)
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.JOINED_CHANNELS] ?: setOf("#mesh", "#general", "#sos")
            prefs[Keys.JOINED_CHANNELS] = current + normalized
        }
        return normalized
    }

    suspend fun setVibrateOnPing(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VIBRATE_ON_PING] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun panicResetIdentity(): MeshIdentity {
        val fresh = MeshCrypto.generateIdentity()
        context.dataStore.edit { prefs ->
            prefs[Keys.PEER_ID] = fresh.peerId
            prefs[Keys.PUBLIC_KEY] = fresh.publicKeyBase64
            prefs[Keys.PRIVATE_KEY] = fresh.privateKeyBase64
            prefs[Keys.NICKNAME] = "anon_${fresh.peerId.take(4)}"
            prefs[Keys.JOINED_CHANNELS] = setOf("#mesh", "#general", "#sos")
        }
        return fresh
    }

    companion object {
        fun normalizeChannel(raw: String): String {
            val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")
            val body = cleaned.ifEmpty { "mesh" }.take(24)
            return "#$body"
        }
    }
}
