package com.szgenle.agentpost.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.mail.MailProviderPresets
import com.szgenle.agentpost.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenFetch: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.asString(context))
        viewModel.clearMessage()
    }

    var showAgentDialog by remember { mutableStateOf(false) }
    var showSelfDialog by remember { mutableStateOf(false) }

    // 系统级权限状态：用户去系统设置授权后回来 ON_RESUME 重读一次
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = areNotificationsAllowed(context)
                batteryUnrestricted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            // 1. 语言行：右侧直接放两颗 FilterChip 原地切换
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_language)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.languageTag == "zh-CN",
                            onClick = { viewModel.setLanguage("zh-CN") },
                            label = { Text(stringResource(R.string.settings_language_zh)) },
                        )
                        FilterChip(
                            selected = state.languageTag == "en",
                            onClick = { viewModel.setLanguage("en") },
                            label = { Text(stringResource(R.string.settings_language_en)) },
                        )
                    }
                },
            )
            HorizontalDivider()

            // 2. 智能体行：点击弹窗编辑名称+邮箱
            IdentityListItem(
                title = stringResource(R.string.settings_row_agent),
                subtitleHint = stringResource(R.string.settings_agent_subtitle_hint),
                name = state.agent?.displayName.orEmpty(),
                email = state.agent?.email.orEmpty(),
                onClick = { showAgentDialog = true },
            )
            HorizontalDivider()

            // 3. 我行：点击弹窗编辑名称+邮箱（不动 IMAP/SMTP/密码）
            IdentityListItem(
                title = stringResource(R.string.settings_row_self),
                subtitleHint = null,
                name = state.self?.displayName.orEmpty(),
                email = state.self?.email.orEmpty(),
                onClick = { showSelfDialog = true },
            )
            HorizontalDivider()

            // 4. 邮箱设置行：右侧显示服务商，左滑进入二级页
            val providerText = state.self?.email
                ?.let { MailProviderPresets.matchByEmail(it)?.displayName }
                ?: stringResource(R.string.settings_mail_provider_custom)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_mail)) },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = providerText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onOpenMail),
            )
            HorizontalDivider()

            // 5. 收件收取行
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_fetch)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                R.string.settings_fetch_trailing_fmt,
                                state.fetchIntervals.foregroundSeconds,
                                state.fetchIntervals.backgroundMinutes,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onOpenFetch),
            )
            HorizontalDivider()

            // 6. 允许通知：点击跳系统 App 通知设置页
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_notifications)) },
                supportingContent = {
                    Text(
                        text = stringResource(
                            if (notificationsAllowed) R.string.settings_permission_allowed
                            else R.string.settings_permission_denied,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { openNotificationSettings(context) },
            )
            HorizontalDivider()

            // 7. 允许后台运行：跳系统「电池优化」列表让用户将本 App 设为不受限
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_background)) },
                supportingContent = {
                    Text(
                        text = stringResource(
                            if (batteryUnrestricted) R.string.settings_background_allowed
                            else R.string.settings_background_restricted,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { openBatteryOptimizationSettings(context) },
            )
        }
    }

    if (showAgentDialog) {
        IdentityEditDialog(
            title = stringResource(R.string.settings_dialog_edit_agent),
            initialName = state.agent?.displayName.orEmpty(),
            initialEmail = state.agent?.email.orEmpty(),
            busy = state.busy,
            onConfirm = { name, email ->
                viewModel.saveAgent(name, email)
                showAgentDialog = false
            },
            onDismiss = { showAgentDialog = false },
        )
    }
    if (showSelfDialog) {
        IdentityEditDialog(
            title = stringResource(R.string.settings_dialog_edit_self),
            initialName = state.self?.displayName.orEmpty(),
            initialEmail = state.self?.email.orEmpty(),
            busy = state.busy,
            onConfirm = { name, email ->
                viewModel.saveSelfIdentity(name, email)
                showSelfDialog = false
            },
            onDismiss = { showSelfDialog = false },
        )
    }
}

/** 智能体 / 我：统一的身份列表项，点击弹窗编辑。未设置时副标题显示"未设置"。 */
@Composable
private fun IdentityListItem(
    title: String,
    subtitleHint: String?,
    name: String,
    email: String,
    onClick: () -> Unit,
) {
    val emptyLabel = stringResource(R.string.settings_name_email_empty)
    val supporting = when {
        name.isNotBlank() && email.isNotBlank() -> "$name\n$email"
        email.isNotBlank() -> email
        name.isNotBlank() -> name
        else -> subtitleHint ?: emptyLabel
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(),
    )
}

@Composable
private fun IdentityEditDialog(
    title: String,
    initialName: String,
    initialEmail: String,
    busy: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf(initialEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_display_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.settings_email_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, email) },
                enabled = !busy && email.isNotBlank(),
            ) { Text(stringResource(CoreUiR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}

// ---- 系统设置权限跳转辅助（仅本文件使用）----

/** 通知是否被允许：综合 POST_NOTIFICATIONS 权限与 App/渠道级开关。 */
private fun areNotificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** App 是否已被用户加入「电池优化白名单」（系统不限制后台）。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** 跳系统的 App 通知设置页；ActionNotAvailable 时回退到 App 详情页。 */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(appDetailsIntent(context))
    }
}

/**
 * 跳系统「电池优化」列表页，用户手动将本 App 改为「不受限」。
 * 故意不用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，避免加 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限碰触 Play 政策。
 */
private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(appDetailsIntent(context))
    }
}

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
