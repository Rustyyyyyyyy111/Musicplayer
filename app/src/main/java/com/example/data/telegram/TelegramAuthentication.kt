package com.example.data.telegram

import com.example.core.util.ActivityLogger
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

enum class TelegramCodeChannel(val labelVi: String) {
    TELEGRAM_MESSAGE("Tin nhắn trong ứng dụng Telegram"),
    SMS("Tin nhắn SMS"),
    CALL("Cuộc gọi xác thực"),
    FRAGMENT("Fragment"),
    UNKNOWN("Telegram")
}

sealed interface TelegramAuthUiState {
    data class Unconfigured(
        val reason: String,
        val missingApiKeys: Boolean,
        val nativeLibraryMissing: Boolean
    ) : TelegramAuthUiState

    data object Initializing : TelegramAuthUiState

    data class WaitPhoneNumber(
        val lastPhone: String = "",
        val inlineError: String? = null
    ) : TelegramAuthUiState

    data class WaitOtpCode(
        val phoneNumber: String = "",
        val codeLength: Int = 5,
        val channel: TelegramCodeChannel = TelegramCodeChannel.TELEGRAM_MESSAGE,
        val nextChannel: TelegramCodeChannel? = null,
        val resendTimeoutSec: Int = 60,
        val inlineError: String? = null
    ) : TelegramAuthUiState

    data class WaitTwoFactorPassword(
        val passwordHint: String,
        val inlineError: String? = null
    ) : TelegramAuthUiState

    data object Authenticating : TelegramAuthUiState

    data class Authenticated(
        val accountSlot: Int,
        val userId: Long,
        val displayName: String,
        val username: String?
    ) : TelegramAuthUiState
}

class TelegramAuthentication(
    private val core: TelegramCore,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow<TelegramAuthUiState>(TelegramAuthUiState.Initializing)
    val uiState: StateFlow<TelegramAuthUiState> = _uiState.asStateFlow()

    private val _activeAccountSlot = MutableStateFlow(0)
    val activeAccountSlot: StateFlow<Int> = _activeAccountSlot.asStateFlow()

    @Volatile
    private var lastSubmittedPhone: String = ""

    init {
        scope.launch {
            val savedSlot = settingsDataStore.preferencesFlow.first().activeTelegramAccountId
            _activeAccountSlot.value = savedSlot
            if (!core.hasValidApiCredentials()) {
                _uiState.value = TelegramAuthUiState.Unconfigured(
                    reason = "Cần TELEGRAM_API_ID và TELEGRAM_API_HASH (từ my.telegram.org hoặc bảng Secrets của AI Studio) để nhận mã đăng nhập từ Telegram.",
                    missingApiKeys = true,
                    nativeLibraryMissing = false
                )
            } else {
                core.initialize(savedSlot)
            }

            combine(core.authState, core.nativeError) { auth, err -> auth to err }
                .collect { (auth, err) ->
                    if (err != null) {
                        _uiState.value = TelegramAuthUiState.Unconfigured(
                            reason = err,
                            missingApiKeys = !core.hasValidApiCredentials(),
                            nativeLibraryMissing = true
                        )
                        return@collect
                    }
                    when (auth) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            if (!core.hasValidApiCredentials()) {
                                _uiState.value = TelegramAuthUiState.Unconfigured(
                                    reason = "Cần TELEGRAM_API_ID và TELEGRAM_API_HASH (từ my.telegram.org hoặc bảng Secrets của AI Studio).",
                                    missingApiKeys = true,
                                    nativeLibraryMissing = false
                                )
                            } else {
                                _uiState.value = TelegramAuthUiState.Initializing
                            }
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            _uiState.value = TelegramAuthUiState.WaitPhoneNumber(lastPhone = lastSubmittedPhone)
                        }
                        is TdApi.AuthorizationStateWaitCode -> {
                            val codeInfo = auth.codeInfo
                            val (channel, len) = mapCodeType(codeInfo.type)
                            val nextChannel = codeInfo.nextType?.let { mapCodeType(it).first }
                            _uiState.value = TelegramAuthUiState.WaitOtpCode(
                                phoneNumber = codeInfo.phoneNumber.ifBlank { lastSubmittedPhone },
                                codeLength = len,
                                channel = channel,
                                nextChannel = nextChannel,
                                resendTimeoutSec = codeInfo.timeout.coerceAtLeast(0)
                            )
                        }
                        is TdApi.AuthorizationStateWaitPassword -> {
                            _uiState.value = TelegramAuthUiState.WaitTwoFactorPassword(auth.passwordHint.orEmpty())
                        }
                        is TdApi.AuthorizationStateReady -> {
                            loadCurrentUser(_activeAccountSlot.value)
                        }
                        is TdApi.AuthorizationStateLoggingOut -> {
                            _uiState.value = TelegramAuthUiState.Authenticating
                        }
                        is TdApi.AuthorizationStateClosed -> {
                            _uiState.value = TelegramAuthUiState.Initializing
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun submitApiCredentials(apiIdRaw: String, apiHashRaw: String): String? {
        val parsedId = apiIdRaw.trim().toIntOrNull()
        val cleanHash = apiHashRaw.trim()
        if (parsedId == null || parsedId <= 0) {
            return "API ID phải là số nguyên dương từ my.telegram.org."
        }
        if (cleanHash.length < 16) {
            return "API Hash không hợp lệ."
        }
        core.saveCustomApiCredentials(parsedId, cleanHash)
        _uiState.value = TelegramAuthUiState.Initializing
        core.switchAccount(_activeAccountSlot.value)
        logger.log("TG_AUTH", "INFO", "Đã cấu hình thông số API Telegram và khởi tạo TDLib")
        return null
    }

    suspend fun submitPhoneNumber(phone: String) {
        val normalized = normalizePhone(phone)
        if (normalized.length < 8) {
            _uiState.value = TelegramAuthUiState.WaitPhoneNumber(
                lastPhone = phone,
                inlineError = "Vui lòng nhập số điện thoại hợp lệ kèm mã quốc gia (ví dụ +84901234567)."
            )
            return
        }
        lastSubmittedPhone = normalized
        _uiState.value = TelegramAuthUiState.Authenticating
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            // Set false so Telegram delivers the login code directly into the user's Telegram app/SMS
            isCurrentPhoneNumber = false
            hasUnknownPhoneNumber = false
            allowSmsRetrieverApi = false
        }
        core.sendQuery(TdApi.SetAuthenticationPhoneNumber(normalized, settings))
            .onFailure { err ->
                logger.log("TG_AUTH", "ERROR", "Gửi số điện thoại thất bại: ${err.message}")
                _uiState.value = TelegramAuthUiState.WaitPhoneNumber(
                    lastPhone = normalized,
                    inlineError = translateTdLibAuthError(err.message)
                )
            }
    }

    suspend fun submitOtpCode(code: String) {
        val clean = code.filter { it.isDigit() }
        val prev = _uiState.value as? TelegramAuthUiState.WaitOtpCode
        if (clean.isEmpty()) {
            if (prev != null) {
                _uiState.value = prev.copy(inlineError = "Vui lòng nhập mã xác thực từ Telegram.")
            }
            return
        }
        _uiState.value = TelegramAuthUiState.Authenticating
        core.sendQuery(TdApi.CheckAuthenticationCode(clean))
            .onFailure { err ->
                logger.log("TG_AUTH", "ERROR", "Xác thực OTP thất bại: ${err.message}")
                _uiState.value = (prev ?: TelegramAuthUiState.WaitOtpCode(phoneNumber = lastSubmittedPhone)).copy(
                    inlineError = translateTdLibAuthError(err.message)
                )
            }
    }

    suspend fun resendOtpCode() {
        val prev = _uiState.value as? TelegramAuthUiState.WaitOtpCode ?: return
        core.sendQuery(TdApi.ResendAuthenticationCode(null))
            .onSuccess {
                logger.log("TG_AUTH", "INFO", "Đã yêu cầu Telegram gửi lại mã xác thực")
            }
            .onFailure { err ->
                logger.log("TG_AUTH", "WARN", "Gửi lại mã thất bại: ${err.message}")
                _uiState.value = prev.copy(inlineError = translateTdLibAuthError(err.message))
            }
    }

    suspend fun changePhoneNumber() {
        _uiState.value = TelegramAuthUiState.WaitPhoneNumber(lastPhone = lastSubmittedPhone)
    }

    suspend fun submitTwoFactorPassword(password: String) {
        val prev = _uiState.value as? TelegramAuthUiState.WaitTwoFactorPassword
        if (password.isEmpty()) {
            if (prev != null) {
                _uiState.value = prev.copy(inlineError = "Vui lòng nhập mật khẩu xác thực 2 bước.")
            }
            return
        }
        _uiState.value = TelegramAuthUiState.Authenticating
        core.sendQuery(TdApi.CheckAuthenticationPassword(password))
            .onFailure { err ->
                logger.log("TG_AUTH", "ERROR", "Xác thực mật khẩu 2FA thất bại: ${err.message}")
                _uiState.value = (prev ?: TelegramAuthUiState.WaitTwoFactorPassword("")).copy(
                    inlineError = translateTdLibAuthError(err.message)
                )
            }
    }

    suspend fun logout() {
        _uiState.value = TelegramAuthUiState.Authenticating
        core.sendQuery(TdApi.LogOut())
            .onSuccess {
                lastSubmittedPhone = ""
                logger.log("TG_AUTH", "INFO", "Đã đăng xuất tài khoản Telegram #${_activeAccountSlot.value}")
                core.switchAccount(_activeAccountSlot.value)
            }
            .onFailure { err ->
                logger.log("TG_AUTH", "ERROR", "Đăng xuất thất bại: ${err.message}")
            }
    }

    suspend fun switchAccount(slot: Int) {
        _activeAccountSlot.value = slot
        settingsDataStore.setActiveTelegramAccount(slot)
        if (!core.hasValidApiCredentials()) {
            _uiState.value = TelegramAuthUiState.Unconfigured(
                reason = "Cần TELEGRAM_API_ID và TELEGRAM_API_HASH (từ my.telegram.org hoặc bảng Secrets của AI Studio).",
                missingApiKeys = true,
                nativeLibraryMissing = false
            )
            return
        }
        _uiState.value = TelegramAuthUiState.Initializing
        core.switchAccount(slot)
    }

    fun dismissError() {
        when (val current = _uiState.value) {
            is TelegramAuthUiState.WaitPhoneNumber -> _uiState.value = current.copy(inlineError = null)
            is TelegramAuthUiState.WaitOtpCode -> _uiState.value = current.copy(inlineError = null)
            is TelegramAuthUiState.WaitTwoFactorPassword -> _uiState.value = current.copy(inlineError = null)
            else -> Unit
        }
    }

    private suspend fun loadCurrentUser(slot: Int) {
        core.sendQuery(TdApi.GetMe()).onSuccess { me ->
            val fullName = listOf(me.firstName, me.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Telegram User" }
            val username = me.usernames?.activeUsernames?.firstOrNull()
            _uiState.value = TelegramAuthUiState.Authenticated(
                accountSlot = slot,
                userId = me.id,
                displayName = fullName,
                username = username
            )
            logger.log("TG_AUTH", "INFO", "Đăng nhập Telegram thành công (Slot #$slot)")
        }.onFailure {
            _uiState.value = TelegramAuthUiState.Authenticated(slot, 0L, "Telegram User", null)
        }
    }

    companion object {
        fun normalizePhone(raw: String): String {
            val cleaned = raw.trim().replace(Regex("[\\s\\-()]"), "")
            return when {
                cleaned.startsWith("+") -> cleaned
                cleaned.startsWith("0") && cleaned.length in 9..11 -> "+84" + cleaned.drop(1)
                cleaned.all { it.isDigit() } && cleaned.isNotEmpty() -> "+$cleaned"
                else -> cleaned
            }
        }

        private fun mapCodeType(type: TdApi.AuthenticationCodeType?): Pair<TelegramCodeChannel, Int> {
            return when (type) {
                is TdApi.AuthenticationCodeTypeTelegramMessage -> TelegramCodeChannel.TELEGRAM_MESSAGE to type.length
                is TdApi.AuthenticationCodeTypeSms -> TelegramCodeChannel.SMS to type.length
                is TdApi.AuthenticationCodeTypeCall -> TelegramCodeChannel.CALL to type.length
                is TdApi.AuthenticationCodeTypeFragment -> TelegramCodeChannel.FRAGMENT to type.length
                else -> TelegramCodeChannel.UNKNOWN to 5
            }
        }

        fun translateTdLibAuthError(raw: String?): String {
            val msg = raw.orEmpty().uppercase()
            return when {
                "PHONE_NUMBER_INVALID" in msg -> "Số điện thoại không đúng định dạng quốc tế (ví dụ +84901234567)."
                "PHONE_CODE_INVALID" in msg -> "Mã xác thực Telegram không chính xác. Vui lòng kiểm tra lại."
                "PHONE_CODE_EXPIRED" in msg -> "Mã xác thực đã hết hạn. Hãy bấm 'Gửi lại mã' để nhận mã mới."
                "PASSWORD_HASH_INVALID" in msg -> "Mật khẩu xác thực 2 bước (2FA) không chính xác."
                "FLOOD_WAIT" in msg -> "Bạn đã thử quá nhiều lần. Vui lòng đợi một lát rồi thử lại."
                else -> raw ?: "Đã xảy ra lỗi xác thực Telegram."
            }
        }
    }
}
