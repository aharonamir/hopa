package com.hopa.feature.profiles

import com.hopa.stores.AppSettings
import com.hopa.stores.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings held in memory, so the profiles state holder can be tested without DataStore. */
class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    val current: AppSettings get() = state.value

    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }

    override suspend fun modelKey(): String = ""

    override suspend fun hasModelKey(): Boolean = false

    override suspend fun setModelKey(value: String) = Unit

    override suspend fun bridgeToken(): String = ""

    override suspend fun setBridgeToken(value: String) = Unit

    override suspend fun bridgeRefresh(): String = ""

    override suspend fun setBridgeRefresh(value: String) = Unit

    override suspend fun backendAccessToken(): String = ""

    override suspend fun setBackendAccessToken(value: String) = Unit

    override suspend fun backendRefreshToken(): String = ""

    override suspend fun setBackendRefreshToken(value: String) = Unit

    override suspend fun backendPrivateKey(): String = ""

    override suspend fun setBackendPrivateKey(value: String) = Unit

    override suspend fun backendContentKey(): String = ""

    override suspend fun setBackendContentKey(value: String) = Unit

    override suspend fun clearBackend() = Unit
}
