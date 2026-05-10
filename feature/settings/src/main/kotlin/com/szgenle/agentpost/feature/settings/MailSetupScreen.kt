package com.szgenle.agentpost.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.mail.MailProviderPresets
import com.szgenle.agentpost.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailSetupRoute(
    onBack: () -> Unit,
    viewModel: MailSetupViewModel = viewModel(factory = MailSetupViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.asString(context))
        viewModel.clearMessage()
    }

    val initialForm = state.self?.toSelfForm() ?: SelfForm()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_mail_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
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
            MailSetupForm(
                initialForm = initialForm,
                hasExistingPassword = state.hasExistingPassword,
                busy = state.busy,
                onSave = viewModel::save,
                onTest = viewModel::testConnection,
            )
        }
    }
}

@Composable
private fun MailSetupForm(
    initialForm: SelfForm,
    hasExistingPassword: Boolean,
    busy: Boolean,
    onSave: (SelfForm, String) -> Unit,
    onTest: () -> Unit,
) {
    var form by remember(initialForm) { mutableStateOf(initialForm) }
    var password by remember(hasExistingPassword) { mutableStateOf("") }

    // 服务商完全由邮箱派生：匹配到预设就显示其名称，否则显示"自定义"
    val detectedPreset = remember(form.email) {
        MailProviderPresets.matchByEmail(form.email)
    }
    val providerLabel = detectedPreset?.displayName
        ?: stringResource(R.string.settings_mail_provider_custom)
    // 非自定义时默认收起高级（Host/Port/SSL）
    var advancedExpanded by remember(detectedPreset?.id) {
        mutableStateOf(detectedPreset == null)
    }

    // 服务商只读展示：一行 label + value
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_provider),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            providerLabel,
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    OutlinedTextField(
        value = form.displayName,
        onValueChange = { form = form.copy(displayName = it) },
        label = { Text(stringResource(R.string.settings_display_name_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = form.email,
        onValueChange = { newEmail ->
            // 邮箱变动时，匹配到预设则自动回填 host/port/SSL；匹配不到只改 email
            val matched = MailProviderPresets.matchByEmail(newEmail)
            form = if (matched != null) {
                form.copy(
                    email = newEmail,
                    imapHost = matched.imapHost,
                    imapPort = matched.imapPort,
                    imapUseSsl = matched.imapUseSsl,
                    smtpHost = matched.smtpHost,
                    smtpPort = matched.smtpPort,
                    smtpUseStartTls = matched.smtpUseStartTls,
                )
            } else {
                form.copy(email = newEmail)
            }
        },
        label = { Text(stringResource(R.string.settings_email_address)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.settings_password)) },
        // 密码框不用 placeholder——留空即真空，避免用 "••••••••" 占位符
        // 误导用户以为已输入。"留空=不改"的语义由 ViewModel 保证。
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    // 高级设置（Host/Port/SSL/STARTTLS）
    TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
        Text(stringResource(R.string.settings_advanced_toggle))
    }
    if (advancedExpanded) {
        Text(
            stringResource(R.string.settings_imap),
            style = MaterialTheme.typography.labelLarge,
        )
        HostPortRow(
            host = form.imapHost,
            port = form.imapPort,
            onHost = { form = form.copy(imapHost = it) },
            onPort = { form = form.copy(imapPort = it) },
        )
        SwitchRow(
            label = stringResource(R.string.settings_ssl),
            checked = form.imapUseSsl,
            onChange = { form = form.copy(imapUseSsl = it) },
        )

        Text(
            stringResource(R.string.settings_smtp),
            style = MaterialTheme.typography.labelLarge,
        )
        HostPortRow(
            host = form.smtpHost,
            port = form.smtpPort,
            onHost = { form = form.copy(smtpHost = it) },
            onPort = { form = form.copy(smtpPort = it) },
        )
        SwitchRow(
            label = stringResource(R.string.settings_starttls),
            checked = form.smtpUseStartTls,
            onChange = { form = form.copy(smtpUseStartTls = it) },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onSave(form, password) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(CoreUiR.string.common_save)) }
        OutlinedButton(
            onClick = onTest,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.settings_test_connection)) }
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
            label = { Text(stringResource(R.string.settings_host)) },
            modifier = Modifier.weight(2f),
            singleLine = true,
        )
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { s -> onPort(s.filter { it.isDigit() }.toIntOrNull() ?: 0) },
            label = { Text(stringResource(R.string.settings_port)) },
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
