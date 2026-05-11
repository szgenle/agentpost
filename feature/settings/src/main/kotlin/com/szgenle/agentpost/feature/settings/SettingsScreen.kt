package com.szgenle.agentpost.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.mail.MailProviderPresets
import com.szgenle.agentpost.core.datastore.CrashReportPref
import com.szgenle.agentpost.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenFetch: () -> Unit,
    onNavigateToTemplates: () -> Unit,
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
    var showCrashPrefDialog by remember { mutableStateOf(false) }
    var showZipPasswordDialog by remember { mutableStateOf(false) }
    var showBatteryHintDialog by remember { mutableStateOf(false) }

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
            // ============ 第 1 组：邮箱 ============
            // 把身份（智能体/我）与邮箱服务器、收件节奏归在一起，围绕「把邮件收进来」这条主线。
            SectionHeader(stringResource(R.string.settings_group_mailbox))

            // 智能体行：点击弹窗编辑名称+邮箱
            IdentityListItem(
                title = stringResource(R.string.settings_row_agent),
                subtitleHint = stringResource(R.string.settings_agent_subtitle_hint),
                name = state.agent?.displayName.orEmpty(),
                email = state.agent?.email.orEmpty(),
                onClick = { showAgentDialog = true },
            )
            HorizontalDivider()

            // 我行：点击弹窗编辑名称+邮箱（不动 IMAP/SMTP/密码）
            IdentityListItem(
                title = stringResource(R.string.settings_row_self),
                subtitleHint = null,
                name = state.self?.displayName.orEmpty(),
                email = state.self?.email.orEmpty(),
                onClick = { showSelfDialog = true },
            )
            HorizontalDivider()

            // 邮箱设置行：右侧显示服务商，点击进入二级页
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

            // 收件收取行
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

            // ============ 第 2 组：通知与推送 ============
            // 围绕「邮件到了之后怎么提示用户」这条主线：系统通知权限、后台运行权限、实时推送开关。
            SectionHeader(stringResource(R.string.settings_group_notifications))

            // 允许通知：点击跳系统 App 通知设置页
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

            // 允许后台运行：跳系统「电池优化」列表让用户将本 App 设为不受限
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
            HorizontalDivider()

            // 实时通知（实验）：开关 ON 时拉起前台服务 + IMAP IDLE 长连
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_realtime)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.settings_row_realtime_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.realtimePush,
                        onCheckedChange = { nextEnabled ->
                            if (nextEnabled && !state.realtimeBatteryDialogShown &&
                                !isIgnoringBatteryOptimizations(context)
                            ) {
                                // 首次开启且仍有电池限制 → 先弹软引导；开关仍然变为 ON。
                                showBatteryHintDialog = true
                            }
                            viewModel.setRealtimePush(nextEnabled)
                        },
                    )
                },
            )

            // ============ 第 3 组：其它 ============
            // 与主线关系较弱的通用选项：语言、崩溃上报、附件密码、命令模板。
            SectionHeader(stringResource(R.string.settings_group_others))

            // 语言行：右侧直接放两颗 FilterChip 原地切换
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

            // 崩溃上报：弹框选态，右侧显示当前选中的短文案
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_crash_report)) },
                supportingContent = {
                    Text(
                        text = stringResource(crashPrefShortRes(state.crashReportPref)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { showCrashPrefDialog = true },
            )
            HorizontalDivider()

            // 加密附件密码：用于自动解压家里 AI 回传的加密 zip
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_zip_password)) },
                supportingContent = {
                    Text(
                        text = stringResource(
                            if (state.hasZipPassword) R.string.settings_zip_password_set
                            else R.string.settings_zip_password_unset,
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
                modifier = Modifier.clickable { showZipPasswordDialog = true },
            )
            HorizontalDivider()

            // 命令模板：进入管理页 CRUD + 排序
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_row_command_templates)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.settings_row_command_templates_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable(onClick = onNavigateToTemplates),
            )

            // [DEBUG only] 触发一次测试崩溃：验证 CrashHandler 落盘与下次启动的上报流程。
            // Release 包 FLAG_DEBUGGABLE=0，此行自动消失。测完可整段删除。
            if (isDebuggable(context)) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("💥 Trigger test crash (DEBUG)") },
                    supportingContent = {
                        Text(
                            text = "Throws RuntimeException on main thread to exercise CrashHandler.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable {
                        throw RuntimeException(
                            "manual test crash @ ${System.currentTimeMillis()}",
                        )
                    },
                )
            }
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
    if (showCrashPrefDialog) {
        CrashReportPrefDialog(
            current = state.crashReportPref,
            onSelect = { pref ->
                viewModel.setCrashReportPref(pref)
                showCrashPrefDialog = false
            },
            onDismiss = { showCrashPrefDialog = false },
        )
    }
    if (showZipPasswordDialog) {
        ZipPasswordDialog(
            hasPassword = state.hasZipPassword,
            onSave = { pw ->
                viewModel.setZipPassword(pw)
                showZipPasswordDialog = false
            },
            onClear = {
                viewModel.clearZipPassword()
                showZipPasswordDialog = false
            },
            onDismiss = { showZipPasswordDialog = false },
        )
    }
    if (showBatteryHintDialog) {
        BatteryOptimizationHintDialog(
            onOpenSettings = {
                viewModel.markBatteryDialogShown()
                openBatteryOptimizationSettings(context)
                showBatteryHintDialog = false
            },
            onDontShowAgain = {
                viewModel.markBatteryDialogShown()
                showBatteryHintDialog = false
            },
            onLater = { showBatteryHintDialog = false },
        )
    }
}

/** 设置页分组标题：整行铺一条 surfaceVariant 背景色，
 * 让用户扫一眼就能看到「这里是新一组的开始」，而不是空白+小字。
 * - 背景：colorScheme.surfaceVariant（浅灰/浅染，浅深色主题都能适配）
 * - 文本：colorScheme.onSurfaceVariant（与背景天然对比）
 * - 字重：titleSmall；左右内边距 16.dp 对齐 ListItem。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
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
    // 副标题只展示邮箱；没有邮箱时回退到 subtitleHint，再回退到「未设置」。
    val supporting = when {
        email.isNotBlank() -> email
        else -> subtitleHint ?: emptyLabel
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            // 名称放在右箭头左侧，与标题同一水平；没有名称时只留箭头。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (name.isNotBlank()) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            }
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

/** 运行时判断当前包是不是 debuggable，用于隐藏 Release 包里的测试入口。 */
private fun isDebuggable(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

// ---- 崩溃上报偏好（仅本文件使用）----

/** 行右侧显示的短文案。 */
private fun crashPrefShortRes(pref: CrashReportPref): Int = when (pref) {
    CrashReportPref.ASK_EACH_TIME -> R.string.settings_crash_ask
    CrashReportPref.AUTO -> R.string.settings_crash_auto
    CrashReportPref.NEVER -> R.string.settings_crash_never
}

/** 三选一弹框：选中即切换，并关闭弹框。 */
@Composable
private fun CrashReportPrefDialog(
    current: CrashReportPref,
    onSelect: (CrashReportPref) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_crash_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CrashPrefOption(
                    label = stringResource(R.string.settings_crash_ask),
                    selected = current == CrashReportPref.ASK_EACH_TIME,
                    onClick = { onSelect(CrashReportPref.ASK_EACH_TIME) },
                )
                CrashPrefOption(
                    label = stringResource(R.string.settings_crash_auto),
                    selected = current == CrashReportPref.AUTO,
                    onClick = { onSelect(CrashReportPref.AUTO) },
                )
                CrashPrefOption(
                    label = stringResource(R.string.settings_crash_never),
                    selected = current == CrashReportPref.NEVER,
                    onClick = { onSelect(CrashReportPref.NEVER) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}

@Composable
private fun CrashPrefOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/**
 * 加密附件主密码编辑弹框：
 * - 输入框走 [PasswordVisualTransformation] 隐藏明文，键盘类型 Password；
 * - 「保存」在输入非空时生效，空串走 clear 语义（由 VM 兜底）；
 * - 「清除」仅在已设置状态展示，避免首次配置时误触；
 * - 「取消」不落盘。
 */
@Composable
private fun ZipPasswordDialog(
    hasPassword: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_zip_password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_zip_password_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_zip_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(password) },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(CoreUiR.string.common_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasPassword) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.settings_zip_password_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(CoreUiR.string.common_cancel))
                }
            }
        },
    )
}

/**
 * 电池优化软引导弹框：
 * - 仅在「实时通知」首次从 OFF→ON 且当前未加入电池白名单时由上层触发；
 * - 三个动作：去系统设置 / 不再提示 / 稍后；
 * - 「去系统设置」与「不再提示」都会落一次 realtimeBatteryDialogShown=true，避免下次重复打扰；
 * - 「稍后」不落位，下次再开启仍会弹一次。
 */
@Composable
private fun BatteryOptimizationHintDialog(
    onOpenSettings: () -> Unit,
    onDontShowAgain: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.settings_battery_hint_title)) },
        text = {
            Text(
                text = stringResource(R.string.settings_battery_hint_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_battery_hint_go_settings))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDontShowAgain) {
                    Text(stringResource(R.string.settings_battery_hint_dont_show_again))
                }
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.settings_battery_hint_later))
                }
            }
        },
    )
}
