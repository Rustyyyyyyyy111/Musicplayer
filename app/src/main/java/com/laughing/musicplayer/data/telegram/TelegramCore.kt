package com.laughing.musicplayer.data.telegram

import android.content.Context
import android.os.Build
import com.laughing.musicplayer.BuildConfig
import com.laughing.musicplayer.core.model.ProxyConfig
import com.laughing.musicplayer.core.model.ProxyType
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.SecureCredentialStore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * Strictly READ-ONLY TDLib client wrapper.
 * Mutating methods (sendMessage, deleteMessages, deleteChat, leaveChat, editMessage)
 * are intentionally never exposed or invoked.
 */
class TelegramCore(
    private val context: Context,
    private val credentialStore: SecureCredentialStore,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var currentAccountSlot: Int = 0

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val _fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 128)
    val fileUpdates: SharedFlow<TdApi.File> = _fileUpdates.asSharedFlow()

    private val _nativeAvailable = MutableStateFlow(false)
    val nativeAvailable: StateFlow<Boolean> = _nativeAvailable.asStateFlow()

    private val _nativeError = MutableStateFlow<String?>(null)
    val nativeError: StateFlow<String?> = _nativeError.asStateFlow()

    fun getResolvedApiCredentials(): Pair<Int, String>? {
        val buildId = BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0
        val buildHash = BuildConfig.TELEGRAM_API_HASH.trim()
        if (buildId > 0 && buildHash.isNotBlank() && buildHash != "YOUR_TELEGRAM_API_HASH") {
            return buildId to buildHash
        }
        val storedId = credentialStore.getSecret("tg_custom_api_id").toIntOrNull() ?: 0
        val storedHash = credentialStore.getSecret("tg_custom_api_hash").trim()
        if (storedId > 0 && storedHash.isNotBlank()) {
            return storedId to storedHash
        }
        return null
    }

    fun hasValidApiCredentials(): Boolean = getResolvedApiCredentials() != null

    fun saveCustomApiCredentials(apiId: Int, apiHash: String) {
        credentialStore.putSecret("tg_custom_api_id", apiId.toString())
        credentialStore.putSecret("tg_custom_api_hash", apiHash.trim())
    }

    @Synchronized
    fun initialize(accountSlot: Int = 0) {
        if (client != null && currentAccountSlot == accountSlot) return
        currentAccountSlot = accountSlot

        val loaded = runCatching {
            System.loadLibrary("tdjni")
            true
        }.getOrElse { err ->
            _nativeAvailable.value = false
            _nativeError.value = "Không thể nạp thư viện TDLib gốc (${Build.SUPPORTED_ABIS.joinToString()}): ${err.message}"
            logger.log("TELEGRAM", "ERROR", "Lỗi nạp native libtdjni: ${err.message}")
            false
        }
        if (!loaded) return

        _nativeAvailable.value = true
        _nativeError.value = null

        val updateHandler = Client.ResultHandler { obj ->
            when (obj) {
                is TdApi.UpdateAuthorizationState -> {
                    _authState.value = obj.authorizationState
                    if (obj.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                        scope.launch { applyTdlibParameters(accountSlot) }
                    } else if (obj.authorizationState is TdApi.AuthorizationStateClosed) {
                        client = null
                    }
                    logger.log("TG_AUTH", "INFO", "Trạng thái xác thực: ${obj.authorizationState.javaClass.simpleName}")
                }
                is TdApi.UpdateFile -> {
                    _fileUpdates.tryEmit(obj.file)
                }
            }
        }

        val exceptionHandler = Client.ExceptionHandler { t ->
            logger.log("TELEGRAM", "ERROR", "Ngoại lệ TDLib: ${t.message}")
        }

        runCatching {
            client?.send(TdApi.Close(), null)
            client = Client.create(updateHandler, exceptionHandler, exceptionHandler)
            logger.log("TELEGRAM", "INFO", "Khởi tạo TDLib client cho tài khoản #$accountSlot")
        }.onFailure { err ->
            _nativeAvailable.value = false
            _nativeError.value = err.message
            logger.log("TELEGRAM", "ERROR", "Không thể khởi tạo TDLib Client: ${err.message}")
        }
    }

    fun switchAccount(accountSlot: Int) {
        client?.send(TdApi.Close(), null)
        client = null
        _authState.value = null
        initialize(accountSlot)
    }

    private suspend fun applyTdlibParameters(accountSlot: Int) {
        val creds = getResolvedApiCredentials()
        if (creds == null) {
            logger.log("TG_AUTH", "WARN", "Thiếu TELEGRAM_API_ID / TELEGRAM_API_HASH")
            return
        }
        val (apiId, apiHash) = creds

        val dbDir = File(context.filesDir, "tdlib_acc_$accountSlot/db").apply { mkdirs() }
        val filesDir = File(context.filesDir, "tdlib_acc_$accountSlot/files").apply { mkdirs() }
        val encKey = getOrCreateDbEncryptionKey(accountSlot)

        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dbDir.absolutePath
            filesDirectory = filesDir.absolutePath
            databaseEncryptionKey = encKey
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = "vi"
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = Build.VERSION.RELEASE ?: "14"
            applicationVersion = BuildConfig.VERSION_NAME
        }

        val res = sendRaw(params)
        if (res is TdApi.Error) {
            logger.log("TG_AUTH", "ERROR", "SetTdlibParameters lỗi ${res.code}: ${res.message}")
        }
    }

    private fun getOrCreateDbEncryptionKey(accountSlot: Int): ByteArray {
        val prefKey = "td_db_key_$accountSlot"
        val existingHex = credentialStore.getSecret(prefKey)
        if (existingHex.length == 64) {
            return existingHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        credentialStore.putSecret(prefKey, bytes.joinToString("") { "%02x".format(it) })
        return bytes
    }

    suspend fun applyProxy(config: ProxyConfig) {
        val c = client ?: return
        if (config.type == ProxyType.NONE || config.host.isBlank()) {
            sendRaw(TdApi.DisableProxy())
            return
        }
        val proxyType = when (config.type) {
            ProxyType.SOCKS5 -> TdApi.ProxyTypeSocks5(config.username, config.password)
            ProxyType.HTTP -> TdApi.ProxyTypeHttp(config.username, config.password, false)
            ProxyType.NONE -> return
        }
        val res = sendRaw(TdApi.AddProxy(config.host, config.port, true, proxyType))
        if (res is TdApi.Error) {
            logger.log("TELEGRAM", "WARN", "Cấu hình Proxy TDLib thất bại: ${res.message}")
        } else {
            logger.log("TELEGRAM", "INFO", "Đã áp dụng Proxy ${config.type.name} cho TDLib")
        }
    }

    suspend fun <T : TdApi.Object> sendQuery(
        function: TdApi.Function<T>,
        timeoutMs: Long = 9_000L
    ): Result<T> {
        val activeClient = client ?: return Result.failure(
            IllegalStateException(_nativeError.value ?: "TDLib chưa sẵn sàng")
        )
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Result<T>> ->
                    activeClient.send(function) { obj ->
                        if (!cont.isActive) return@send
                        when (obj) {
                            is TdApi.Error -> {
                                cont.resume(Result.failure(TdLibException(obj.code, obj.message)))
                            }
                            else -> {
                                @Suppress("UNCHECKED_CAST")
                                cont.resume(Result.success(obj as T))
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            logger.log("TELEGRAM", "WARN", "Hết thời gian chờ TDLib cho ${function.javaClass.simpleName}")
            Result.failure(IOException("Hết thời gian kết nối Telegram (quá ${timeoutMs / 1000} giây)"))
        }
    }

    private suspend fun sendRaw(function: TdApi.Function<*>): TdApi.Object? {
        val activeClient = client ?: return null
        return suspendCancellableCoroutine { cont ->
            activeClient.send(function) { obj ->
                if (cont.isActive) cont.resume(obj)
            }
        }
    }

    class TdLibException(val code: Int, override val message: String) : Exception("TDLib ($code): $message")
}
