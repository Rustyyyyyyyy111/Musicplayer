package com.laughing.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.TelegramChatType
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.telegram.TelegramAuthUiState
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun TelegramAuthScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit,
    onOpenSourcePicker: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val authState by viewModel.telegramAuthState.collectAsStateWithLifecycle()
    val activeSlot by viewModel.telegramAccountSlot.collectAsStateWithLifecycle()

    var phoneInput by rememberSaveable { mutableStateOf("") }
    var otpInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var apiIdInput by rememberSaveable { mutableStateOf("") }
    var apiHashInput by rememberSaveable { mutableStateOf("") }
    var apiFormError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState) {
        when (val st = authState) {
            is TelegramAuthUiState.WaitPhoneNumber -> {
                if (phoneInput.isBlank() && st.lastPhone.isNotBlank()) {
                    phoneInput = st.lastPhone
                }
            }
            is TelegramAuthUiState.WaitOtpCode -> {
                otpInput = ""
            }
            else -> Unit
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("telegram_auth_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                }
                Text(
                    text = "Tài khoản Telegram",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
            }
        }

        // 3-Step Login Progress Strip
        item {
            val activeStep = when (authState) {
                is TelegramAuthUiState.WaitPhoneNumber -> 1
                is TelegramAuthUiState.WaitOtpCode -> 2
                is TelegramAuthUiState.WaitTwoFactorPassword -> 2
                is TelegramAuthUiState.Authenticated -> 3
                else -> 1
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LoginStepBadge(1, "Số điện thoại", activeStep >= 1, Modifier.weight(1f))
                LoginStepBadge(2, "Mã Telegram", activeStep >= 2, Modifier.weight(1f))
                LoginStepBadge(3, "Hoàn tất", activeStep >= 3, Modifier.weight(1f))
            }
        }

        // Auth State Content (front and center)
        item {
            AppleCard(cornerRadius = 22.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (val state = authState) {
                        is TelegramAuthUiState.Unconfigured -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.accent)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (state.nativeLibraryMissing) "Thiết bị không hỗ trợ TDLib native"
                                    else "Cấu hình API Telegram để nhận mã",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.textPrimary
                                )
                            }
                            Text(
                                text = state.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                            if (state.missingApiKeys && !state.nativeLibraryMissing) {
                                Text(
                                    text = "Nhập API ID & API Hash từ my.telegram.org (hoặc thiết lập trong bảng Secrets của AI Studio) để kích hoạt đăng nhập bằng số điện thoại và nhận mã từ Telegram.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                                OutlinedTextField(
                                    value = apiIdInput,
                                    onValueChange = { apiIdInput = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("TELEGRAM_API_ID") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("tg_api_id_input"),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                OutlinedTextField(
                                    value = apiHashInput,
                                    onValueChange = { apiHashInput = it },
                                    label = { Text("TELEGRAM_API_HASH") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("tg_api_hash_input"),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                apiFormError?.let { err ->
                                    Text(err, style = MaterialTheme.typography.bodyMedium, color = colors.accent)
                                }
                                Button(
                                    onClick = {
                                        apiFormError = viewModel.submitTelegramApiCredentials(apiIdInput, apiHashInput)
                                    },
                                    enabled = apiIdInput.isNotBlank() && apiHashInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tg_api_save_btn")
                                ) {
                                    Text("Lưu khóa API & tiếp tục đăng nhập SĐT")
                                }
                            } else {
                                Text(
                                    text = "Lưu ý kiến trúc: Thư viện TDLib gốc được đóng gói cho arm64-v8a; giả lập x86/x86_64 không nạp được libtdjni.so.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        is TelegramAuthUiState.Initializing,
                        is TelegramAuthUiState.Authenticating -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Đang kết nối máy chủ Telegram...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary
                                )
                            }
                        }

                        is TelegramAuthUiState.WaitPhoneNumber -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Phone, contentDescription = null, tint = colors.accent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bước 1: Nhập số điện thoại",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.textPrimary
                                )
                            }
                            Text(
                                text = "Telegram sẽ gửi mã xác thực (5 chữ số) về ứng dụng Telegram hoặc SMS của số điện thoại này.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = phoneInput.startsWith("+84"),
                                    onClick = {
                                        if (!phoneInput.startsWith("+84")) {
                                            phoneInput = "+84" + phoneInput.removePrefix("0")
                                        }
                                    },
                                    label = { Text("Việt Nam (+84)") }
                                )
                            }
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("Số điện thoại (ví dụ +84901234567)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth().testTag("tg_phone_input"),
                                shape = RoundedCornerShape(14.dp)
                            )
                            state.inlineError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.accent
                                )
                            }
                            Button(
                                onClick = { viewModel.submitTelegramPhone(phoneInput) },
                                enabled = phoneInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tg_phone_submit")
                            ) {
                                Text("Nhận mã từ Telegram")
                            }
                        }

                        is TelegramAuthUiState.WaitOtpCode -> {
                            var resendRemaining by remember(state.phoneNumber, state.resendTimeoutSec) {
                                mutableIntStateOf(state.resendTimeoutSec)
                            }
                            LaunchedEffect(resendRemaining) {
                                if (resendRemaining > 0) {
                                    delay(1000L)
                                    resendRemaining--
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.MarkChatUnread, contentDescription = null, tint = colors.accent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bước 2: Nhập mã từ Telegram",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.textPrimary
                                )
                            }
                            Surface(
                                color = colors.accent.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Số điện thoại: ${state.phoneNumber}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "Kênh nhận mã (${state.codeLength} số): ${state.channel.labelVi}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.accent
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = otpInput,
                                onValueChange = { raw ->
                                    val digits = raw.filter { it.isDigit() }
                                    if (digits.length <= 8) otpInput = digits
                                },
                                label = { Text("Nhập mã ${state.codeLength} chữ số từ Telegram") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().testTag("tg_otp_input"),
                                shape = RoundedCornerShape(14.dp)
                            )
                            state.inlineError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.accent
                                )
                            }
                            Button(
                                onClick = { viewModel.submitTelegramOtp(otpInput) },
                                enabled = otpInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tg_otp_submit")
                            ) {
                                Text("Xác nhận mã Telegram")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = { viewModel.changeTelegramPhoneNumber() },
                                    modifier = Modifier.testTag("tg_change_phone")
                                ) {
                                    Text("Đổi số điện thoại", color = colors.textSecondary)
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.resendTelegramOtp()
                                        resendRemaining = 60
                                    },
                                    enabled = resendRemaining <= 0,
                                    modifier = Modifier.testTag("tg_resend_otp")
                                ) {
                                    Text(
                                        if (resendRemaining > 0) "Gửi lại mã (${resendRemaining}s)"
                                        else "Gửi lại mã",
                                        color = if (resendRemaining > 0) colors.textSecondary else colors.accent
                                    )
                                }
                            }
                        }

                        is TelegramAuthUiState.WaitTwoFactorPassword -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.accent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Mật khẩu xác thực 2 bước (2FA)",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.textPrimary
                                )
                            }
                            if (state.passwordHint.isNotBlank()) {
                                Text(
                                    text = "Gợi ý: ${state.passwordHint}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Mật khẩu 2FA") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().testTag("tg_2fa_input"),
                                shape = RoundedCornerShape(14.dp)
                            )
                            state.inlineError?.let { err ->
                                Text(err, style = MaterialTheme.typography.bodyMedium, color = colors.accent)
                            }
                            Button(
                                onClick = { viewModel.submitTelegram2FA(passwordInput) },
                                enabled = passwordInput.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tg_2fa_submit")
                            ) {
                                Text("Mở khóa tài khoản")
                            }
                        }

                        is TelegramAuthUiState.Authenticated -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.accent)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = state.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = colors.textPrimary
                                    )
                                    val sub = buildString {
                                        if (!state.username.isNullOrBlank()) append("@${state.username} • ")
                                        append("Khe #${state.accountSlot + 1}")
                                    }
                                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onOpenSourcePicker,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Chọn nguồn nhạc")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.logoutTelegram() },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.height(48.dp).testTag("tg_logout_btn")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Đăng xuất")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Multi-account slot switcher
        item {
            AppleCard(cornerRadius = 20.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Chọn khe tài khoản",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Tài khoản 1", 1 to "Tài khoản 2", 2 to "Tài khoản 3").forEach { (slot, label) ->
                            FilterChip(
                                selected = activeSlot == slot,
                                onClick = { viewModel.switchTelegramAccount(slot) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Strict Read-Only Notice
        item {
            AppleCard(cornerRadius = 20.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = colors.accent)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Chế độ chỉ đọc nghiêm ngặt: Đăng nhập để đọc nhạc cá nhân, ứng dụng không bao giờ gửi, sửa hoặc xóa tin nhắn Telegram của bạn.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginStepBadge(
    stepNumber: Int,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalMusicPlayerColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (active) colors.accent.copy(alpha = 0.14f) else colors.cardSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (active) colors.accent else colors.divider),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Color.White else colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TelegramSourcePickerScreen(
    viewModel: MusicPlayerViewModel,
    initialFilterType: TelegramChatType? = null,
    onBack: () -> Unit,
    onOpenTopicTracks: (Long, Long, String) -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val chats by viewModel.telegramChats.collectAsStateWithLifecycle()
    val topics by viewModel.telegramTopics.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningTelegram.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeTypeFilter by remember { mutableStateOf(initialFilterType) }
    var onlyWithMusic by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (chats.isEmpty()) {
            viewModel.refreshTelegramChats()
        }
    }

    val filteredChats = remember(chats, searchQuery, activeTypeFilter, onlyWithMusic) {
        chats.filter { chat ->
            val matchesQuery = searchQuery.isBlank() || VietnameseTextNormalizer.matches(chat.title, searchQuery)
            val matchesType = activeTypeFilter == null || chat.chatType == activeTypeFilter
            val matchesMusic = !onlyWithMusic || chat.audioCount > 0 || chat.isForum
            matchesQuery && matchesType && matchesMusic
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("telegram_source_picker_screen")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                    }
                    Text(
                        text = "Chọn nguồn nhạc Telegram",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
                IconButton(
                    onClick = { viewModel.refreshTelegramChats() },
                    enabled = !isScanning,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Làm mới danh sách", tint = colors.accent)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Tìm kênh, nhóm, cuộc trò chuyện...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Xóa")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = activeTypeFilter == null,
                        onClick = { activeTypeFilter = null },
                        label = { Text("Tất cả (${chats.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent,
                            selectedLabelColor = Color.White
                        )
                    )
                }
                items(TelegramChatType.entries) { chatType ->
                    FilterChip(
                        selected = activeTypeFilter == chatType,
                        onClick = { activeTypeFilter = if (activeTypeFilter == chatType) null else chatType },
                        label = { Text(chatType.labelVi) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = onlyWithMusic,
                        onCheckedChange = { onlyWithMusic = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chỉ hội thoại có nhạc", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                }
                Row {
                    TextButton(onClick = { viewModel.selectAllTelegramChats() }) { Text("Chọn tất cả", color = colors.accent) }
                    TextButton(onClick = { viewModel.deselectAllTelegramChats() }) { Text("Bỏ chọn", color = colors.textSecondary) }
                }
            }

            Button(
                onClick = { viewModel.scanSelectedTelegramSources(fullRescan = false) },
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tg_scan_selected_btn")
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isScanning) "Đang đồng bộ tin nhắn âm thanh..." else "Quét nguồn đã chọn")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredChats, key = { it.chatId }) { chat ->
                val chatTopics = remember(topics, chat.chatId) {
                    topics.filter { it.chatId == chat.chatId }
                }
                AppleCard(cornerRadius = 18.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = chat.isSelected,
                                onCheckedChange = { viewModel.toggleTelegramChat(chat.chatId, it) },
                                colors = CheckboxDefaults.colors(checkedColor = colors.accent)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = buildString {
                                    append(chat.chatType.labelVi)
                                    append(" • ${chat.audioCount} bài")
                                    if (chat.isForum || chat.topicCount > 0) {
                                        append(" • ${chat.topicCount} chủ đề")
                                    }
                                }
                                Text(meta, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            }
                            TextButton(
                                onClick = { viewModel.scanSingleTelegramChat(chat.chatId) },
                                enabled = !isScanning
                            ) {
                                Text("Quét", color = colors.accent)
                            }
                        }

                        if (chatTopics.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(chatTopics, key = { "${it.chatId}_${it.messageThreadId}" }) { topic ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { onOpenTopicTracks(topic.chatId, topic.messageThreadId, topic.name) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.accent)
                                        },
                                        label = { Text("${topic.name} (${topic.trackCount})") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopicTracksScreen(
    viewModel: MusicPlayerViewModel,
    chatId: Long,
    threadId: Long,
    topicTitle: String,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val tracksFlow = remember(chatId, threadId) { viewModel.observeTopicTracks(chatId, threadId) }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("topic_tracks_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                }
                ArtworkImage(
                    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
                    contentDescription = topicTitle,
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 12.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = topicTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Forum Topic Artist • ${tracks.size} bài hát",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                Button(
                    onClick = { viewModel.playTrack(tracks.first(), tracks) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Phát")
                }
            }
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Chưa có tệp âm thanh trong chủ đề '$topicTitle'. Hãy quét hội thoại để cập nhật.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, tracks) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                    )
                }
            }
        }
    }
}
