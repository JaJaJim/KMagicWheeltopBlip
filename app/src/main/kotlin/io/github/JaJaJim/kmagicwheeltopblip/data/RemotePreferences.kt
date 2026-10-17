package io.github.JaJaJim.kmagicwheeltopblip.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kmagic_wheeltop_blip_settings")

class RemotePreferences(private val context: Context) {

    companion object {
        private val BOUND_WHEELTOP_MAC_KEY = stringPreferencesKey("bound_wheeltop_mac")
        private val BOUND_WHEELTOP_NAME_KEY = stringPreferencesKey("bound_wheeltop_name")
        private val BOUND_REMOTE_MAC_KEY = stringPreferencesKey("bound_remote_mac")
        private val BOUND_REMOTE_NAME_KEY = stringPreferencesKey("bound_remote_name")
        private val IS_MONITORED_KEY = booleanPreferencesKey("is_monitored")
    }

    val boundWheeltopMac: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_WHEELTOP_MAC_KEY]
    }

    val boundWheeltopName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_WHEELTOP_NAME_KEY]
    }

    val boundRemoteMac: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_REMOTE_MAC_KEY]
    }

    val boundRemoteName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_REMOTE_NAME_KEY]
    }

    val isMonitored: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_MONITORED_KEY] ?: false
    }

    suspend fun saveBoundWheeltop(mac: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[BOUND_WHEELTOP_MAC_KEY] = mac
            preferences[BOUND_WHEELTOP_NAME_KEY] = name
            // Default isMonitored to false on fresh bind
            preferences[IS_MONITORED_KEY] = false
        }
    }

    suspend fun saveBoundRemote(mac: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[BOUND_REMOTE_MAC_KEY] = mac
            preferences[BOUND_REMOTE_NAME_KEY] = name
        }
    }

    suspend fun saveIsMonitored(monitored: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_MONITORED_KEY] = monitored
        }
    }

    suspend fun clearBoundWheeltop() {
        context.dataStore.edit { preferences ->
            preferences.remove(BOUND_WHEELTOP_MAC_KEY)
            preferences.remove(BOUND_WHEELTOP_NAME_KEY)
            preferences[IS_MONITORED_KEY] = false
        }
    }

    suspend fun clearBoundRemote() {
        context.dataStore.edit { preferences ->
            preferences.remove(BOUND_REMOTE_MAC_KEY)
            preferences.remove(BOUND_REMOTE_NAME_KEY)
        }
    }
}
