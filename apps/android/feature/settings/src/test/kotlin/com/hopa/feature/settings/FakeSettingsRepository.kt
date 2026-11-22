package com.hopa.feature.settings

import com.hopa.stores.AppSettings
import com.hopa.stores.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The repository a settings collaborator can be tested against, without DataStore. */
class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    var modelKeyValue: String = ""
    var bridgeTokenValue: String = ""
    /** Simulates a Keystore write that silently keeps nothing. */
    var dropModelKey: Boolean = false
    private var bridgeRefreshValue: String = ""
    private var accessToken: String = ""
    private var refreshToken: String = ""
    private var privateKey: String = ""
    private var contentKey: String = ""

    override val settings: Flow<AppSettings> = state

    fun current(): AppSettings = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }

    override suspend fun modelKey(): String = modelKeyValue

    override suspend fun hasModelKey(): Boolean = modelKeyValue.isNotBlank()

    override suspend fun setModelKey(value: String) {
        if (!dropModelKey) {
            modelKeyValue = value
        }
    }

    override suspend fun bridgeToken(): String = bridgeTokenValue

    override suspend fun setBridgeToken(value: String) {
        bridgeTokenValue = value
    }

    override suspend fun bridgeRefresh(): String = bridgeRefreshValue

    override suspend fun setBridgeRefresh(value: String) {
        bridgeRefreshValue = value
    }

    override suspend fun backendAccessToken(): String = accessToken

    override suspend fun setBackendAccessToken(value: String) {
        accessToken = value
    }

    override suspend fun backendRefreshToken(): String = refreshToken

    override suspend fun setBackendRefreshToken(value: String) {
        refreshToken = value
    }

    override suspend fun backendPrivateKey(): String = privateKey

    override suspend fun setBackendPrivateKey(value: String) {
        privateKey = value
    }

    override suspend fun backendContentKey(): String = contentKey

    override suspend fun setBackendContentKey(value: String) {
        contentKey = value
    }

    override suspend fun clearBackend() {
        accessToken = ""
        refreshToken = ""
        privateKey = ""
        contentKey = ""
    }
}
