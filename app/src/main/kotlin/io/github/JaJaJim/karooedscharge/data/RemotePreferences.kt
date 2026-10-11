package io.github.JaJaJim.karooedscharge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "karoo_eds_charge_settings")

class RemotePreferences(private val context: Context) {

    companion object {
        private val BOUND_WHEELTOP_MAC_KEY = stringPreferencesKey("bound_wheeltop_mac")
        private val BOUND_WHEELTOP_NAME_KEY = stringPreferencesKey("bound_wheeltop_name")
    }

    val boundWheeltopMac: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_WHEELTOP_MAC_KEY]
    }

    val boundWheeltopName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BOUND_WHEELTOP_NAME_KEY]
    }

    suspend fun saveBoundWheeltop(mac: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[BOUND_WHEELTOP_MAC_KEY] = mac
            preferences[BOUND_WHEELTOP_NAME_KEY] = name
        }
    }

    suspend fun clearBoundWheeltop() {
        context.dataStore.edit { preferences ->
            preferences.remove(BOUND_WHEELTOP_MAC_KEY)
            preferences.remove(BOUND_WHEELTOP_NAME_KEY)
        }
    }
}
