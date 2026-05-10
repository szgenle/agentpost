package com.szgenle.agentpost.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.mail.MailProviderPreset
import com.szgenle.agentpost.core.mail.MailProviderPresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 提示消息 → Snackbar
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelfSection(
                initial = state.self?.let { it.toSelfForm() } ?: SelfForm(),
                busy = state.busy,
                onSave = viewModel::saveSelf,
                onTest = viewModel::testConnection,
            )
            HorizontalDivider()
            AgentSection(
                initialDisplayName = state.agent?.displayName.orEmpty(),
                initialEmail = state.agent?.email.orEmpty(),
                busy = state.busy,
                onSave = viewModel::saveAgent,
            )
        }
    }
}

@Composable
private fun SelfSection(
    initial: SelfForm,
    busy: Boolean,
    onSave: (SelfForm) -> Unit,
    onTest: () -> Unit,
) {
    var form by remember(initial) { mutableStateOf(initial) }
    // 已回填表单时，按 host 反查预设；空表单默认未选中
    var selectedPresetId by remember(initial) {
        mutableStateOf(MailProviderPresets.ALL.firstOrNull { it.imapHost == initial.imapHost && it.imapHost.isNotEmpty() }?.id)
    }

    fun applyPreset(preset: MailProviderPreset) {
        selectedPresetId = preset.id
        if (preset.id == MailProviderPresets.CUSTOM.id) return
        form = form.copy(
            imapHost = preset.imapHost,
            imapPort = preset.imapPort,
            imapUseSsl = preset.imapUseSsl,
            smtpHost = preset.smtpHost,
            smtpPort = preset.smtpPort,
            smtpUseStartTls = preset.smtpUseStartTls,
        )
    }

    Text("SELF（我的邮箱）", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

    Text("邮箱服务商", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    ProviderChipRow(
        presets = MailProviderPresets.ALL,
        selectedId = selectedPresetId,
        onSelect = ::applyPreset,
    )

    OutlinedTextField(
        value = form.displayName,
        onValueChange = { form = form.copy(displayName = it) },
        label = { Text("显示名称（可选）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = form.email,
        onValueChange = { newEmail ->
            form = form.copy(email = newEmail)
            // 用户尚未手动选过预设时，按域名自动匹配
            if (selectedPresetId == null) {
                MailProviderPresets.matchByEmail(newEmail)?.let(::applyPreset)
            }
        },
        label = { Text("邮箱地址") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = { form = form.copy(password = it) },
        label = { Text("密码 / App Password") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    Text("IMAP", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    HostPortRow(
        host = form.imapHost,
        port = form.imapPort,
        onHost = { form = form.copy(imapHost = it) },
        onPort = { form = form.copy(imapPort = it) },
    )
    SwitchRow(
        label = "SSL",
        checked = form.imapUseSsl,
        onChange = { form = form.copy(imapUseSsl = it) },
    )

    Text("SMTP", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    HostPortRow(
        host = form.smtpHost,
        port = form.smtpPort,
        onHost = { form = form.copy(smtpHost = it) },
        onPort = { form = form.copy(smtpPort = it) },
    )
    SwitchRow(
        label = "STARTTLS",
        checked = form.smtpUseStartTls,
        onChange = { form = form.copy(smtpUseStartTls = it) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onSave(form) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("保存 SELF") }
        OutlinedButton(
            onClick = onTest,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("测试拉取") }
    }
}

@Composable
private fun AgentSection(
    initialDisplayName: String,
    initialEmail: String,
    busy: Boolean,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialDisplayName) { mutableStateOf(initialDisplayName) }
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }

    Text("AGENT（家里 AI 的邮箱）", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("显示名称（可选）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("邮箱地址") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = { onSave(name, email) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("保存 AGENT") }
}

@Composable
private fun ProviderChipRow(
    presets: List<MailProviderPreset>,
    selectedId: String?,
    onSelect: (MailProviderPreset) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = selectedId == preset.id,
                onClick = { onSelect(preset) },
                label = { Text(preset.displayName) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun HostPortRow(
    host: String,
    port: Int,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text("Host") },
            modifier = Modifier.weight(2f),
            singleLine = true,
        )
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { s -> onPort(s.filter { it.isDigit() }.toIntOrNull() ?: 0) },
            label = { Text("Port") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
