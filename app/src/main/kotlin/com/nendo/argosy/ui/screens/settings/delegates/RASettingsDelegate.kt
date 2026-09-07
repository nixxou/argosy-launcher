package com.nendo.argosy.ui.screens.settings.delegates

import android.content.Context
import android.util.Log
import com.nendo.argosy.R
import com.nendo.argosy.data.preferences.UserPreferencesRepository
import com.nendo.argosy.data.repository.RALoginResult
import com.nendo.argosy.data.repository.RetroAchievementsRepository
import com.nendo.argosy.data.repository.LibretroSettingsRepository
import com.nendo.argosy.data.remote.romm.LiteBoxRaPoll
import com.nendo.argosy.data.remote.romm.RomMRepository
import com.nendo.argosy.data.remote.romm.RomMResult
import kotlinx.coroutines.delay
import com.nendo.argosy.ui.screens.settings.RASettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "RASettingsDelegate"

class RASettingsDelegate @Inject constructor(
    private val raRepository: RetroAchievementsRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val libretroSettingsRepo: LibretroSettingsRepository,
    private val romMRepository: RomMRepository,
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(RASettingsState())
    val state: StateFlow<RASettingsState> = _state.asStateFlow()

    fun updateState(newState: RASettingsState) {
        _state.value = newState
    }

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            val credentials = raRepository.getCredentials()
            val prefs = prefsRepository.userPreferences.first()
            val builtinPrefs = prefsRepository.getBuiltinEmulatorSettings().first()
            val canPush = raRepository.hasWritableRetroArchConfig()
            _state.update {
                it.copy(
                    isLoggedIn = credentials != null,
                    username = credentials?.username,
                    proxyEnabled = prefs.raProxyEnabled,
                    proxyAddress = prefs.raProxyAddress,
                    canPushToRetroArch = canPush,
                    defaultToHardcore = builtinPrefs.defaultToHardcore
                )
            }
        }

        scope.launch {
            // LiteBox only, one cached probe: a stock RomM (or offline) simply never shows the button.
            val available = runCatching { romMRepository.liteBoxSupportsRaCredentials() }.getOrDefault(false)
            _state.update { it.copy(liteBoxSyncAvailable = available) }
        }

        scope.launch {
            raRepository.observePendingCount().collect { count ->
                _state.update { it.copy(pendingAchievementsCount = count) }
            }
        }
    }

    fun showLoginForm(onFocusReset: () -> Unit) {
        _state.update {
            it.copy(
                showLoginForm = true,
                loginUsername = "",
                loginPassword = "",
                loginError = null
            )
        }
        onFocusReset()
    }

    fun hideLoginForm(onFocusReset: () -> Unit) {
        _state.update {
            it.copy(
                showLoginForm = false,
                loginUsername = "",
                loginPassword = "",
                loginError = null
            )
        }
        onFocusReset()
    }

    fun setLoginUsername(username: String) {
        _state.update { it.copy(loginUsername = username) }
    }

    fun setLoginPassword(password: String) {
        _state.update { it.copy(loginPassword = password) }
    }

    fun clearFocusField() {
        _state.update { it.copy(focusField = null) }
    }

    fun setFocusField(index: Int) {
        _state.update { it.copy(focusField = index) }
    }

    fun setProxyEnabled(scope: CoroutineScope, enabled: Boolean) {
        _state.update { it.copy(proxyEnabled = enabled) }
        scope.launch { prefsRepository.setRAProxy(enabled, _state.value.proxyAddress) }
    }

    fun setProxyAddress(scope: CoroutineScope, address: String) {
        _state.update { it.copy(proxyAddress = address) }
        scope.launch { prefsRepository.setRAProxy(_state.value.proxyEnabled, address) }
    }

    fun setDefaultToHardcore(scope: CoroutineScope, mode: String) {
        _state.update { it.copy(defaultToHardcore = mode) }
        scope.launch { libretroSettingsRepo.setBuiltinDefaultToHardcore(mode) }
    }

    fun login(scope: CoroutineScope, onFocusReset: () -> Unit) {
        val state = _state.value
        if (state.loginUsername.isBlank() || state.loginPassword.isBlank()) {
            _state.update {
                it.copy(loginError = context.getString(R.string.settings_ra_delegate_login_error_required))
            }
            return
        }

        scope.launch {
            _state.update { it.copy(isLoggingIn = true, loginError = null) }

            when (val result = raRepository.login(state.loginUsername, state.loginPassword)) {
                is RALoginResult.Success -> {
                    Log.d(TAG, "RA login successful for ${result.username}")
                    _state.update {
                        it.copy(
                            isLoggingIn = false,
                            isLoggedIn = true,
                            username = result.username,
                            showLoginForm = false,
                            loginUsername = "",
                            loginPassword = ""
                        )
                    }
                    onFocusReset()
                }
                is RALoginResult.Error -> {
                    _state.update {
                        it.copy(isLoggingIn = false, loginError = result.message)
                    }
                }
            }
        }
    }

    /**
     * Mehdi, 2026-09-07: "la touche pour synchro les identifiants RetroAchievements" - asks the LiteBox
     * desktop for its own RetroAchievements login. A card goes up over there; this polls until a human
     * shares or denies (or five minutes pass), then stores the username + connect token exactly as a
     * password login would. Shaped like the device pairing on purpose - same words, same patience.
     */
    fun syncFromLiteBox(scope: CoroutineScope, onFocusReset: () -> Unit) {
        if (_state.value.isLoggingIn) return
        scope.launch {
            _state.update {
                it.copy(
                    isLoggingIn = true, loginError = null,
                    liteBoxSyncStatus = context.getString(R.string.settings_ra_login_litebox_waiting)
                )
            }
            fun fail(message: String) {
                _state.update { it.copy(isLoggingIn = false, liteBoxSyncStatus = null, loginError = message) }
            }
            val ticket = when (val r = romMRepository.liteBoxRequestRaCredentials()) {
                is RomMResult.Success -> r.data
                is RomMResult.Error -> {
                    fail(
                        if (r.code == 409) context.getString(R.string.settings_ra_login_litebox_unavailable)
                        else r.message
                    )
                    return@launch
                }
            }
            if (ticket.requestId.isBlank()) { fail(context.getString(R.string.settings_ra_login_litebox_expired)); return@launch }
            val deadline = System.currentTimeMillis() + ticket.expiresIn * 1000L
            val interval = ticket.interval.coerceAtLeast(2) * 1000L
            while (true) {
                delay(interval)
                when (val poll = romMRepository.liteBoxPollRaCredentials(ticket.requestId)) {
                    LiteBoxRaPoll.Pending -> if (System.currentTimeMillis() > deadline) {
                        fail(context.getString(R.string.settings_ra_login_litebox_expired)); return@launch
                    }
                    LiteBoxRaPoll.Denied -> { fail(context.getString(R.string.settings_ra_login_litebox_denied)); return@launch }
                    LiteBoxRaPoll.Expired -> { fail(context.getString(R.string.settings_ra_login_litebox_expired)); return@launch }
                    is LiteBoxRaPoll.Failed -> { fail(poll.message); return@launch }
                    is LiteBoxRaPoll.Ready -> {
                        when (val result = raRepository.adoptCredentials(poll.credentials.username, poll.credentials.token)) {
                            is RALoginResult.Success -> {
                                Log.d(TAG, "RA login adopted from LiteBox for ${result.username}")
                                _state.update {
                                    it.copy(
                                        isLoggingIn = false, isLoggedIn = true, username = result.username,
                                        showLoginForm = false, loginUsername = "", loginPassword = "",
                                        liteBoxSyncStatus = null
                                    )
                                }
                                onFocusReset()
                            }
                            is RALoginResult.Error -> fail(result.message)
                        }
                        return@launch
                    }
                }
            }
        }
    }

    fun logout(scope: CoroutineScope, onFocusReset: () -> Unit) {
        scope.launch {
            _state.update { it.copy(isLoggingOut = true) }
            raRepository.logout()
            _state.update {
                it.copy(
                    isLoggingOut = false,
                    isLoggedIn = false,
                    username = null
                )
            }
            onFocusReset()
        }
    }

    fun pushToRetroArch(scope: CoroutineScope, onResult: (Int) -> Unit) {
        scope.launch { onResult(raRepository.pushCredentialsToRetroArch()) }
    }
}
