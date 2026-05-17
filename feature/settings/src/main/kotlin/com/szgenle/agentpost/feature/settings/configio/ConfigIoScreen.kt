package com.szgenle.agentpost.feature.settings.configio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.szgenle.agentpost.core.ui.components.AppTopBar
import com.szgenle.agentpost.feature.settings.R
import com.szgenle.agentpost.core.ui.R as CoreUiR

/**
 * 配置导入/导出二级页路由。
 *
 * 与外部协作：
 * - [onBack]：返回设置页。
 *
 * 路由层只做三件事：
 * 1. 通过 SAF 拉起 CreateDocument / OpenDocument，把 Uri 转给 ViewModel；
 * 2. 监听 [ConfigIoViewModel.events]，把事件落到 Snackbar / 错误对话框；
 * 3. 渲染主 UI（[ConfigIoContent]）。
 *
 * 「需要主密码」引导不再跳回设置页，而是在本页内弹 [InlineMasterPasswordDialog]，
 * 输完之后自动续践之前选好的导入/导出意图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigIoRoute(
    onBack: () -> Unit,
    viewModel: ConfigIoViewModel = viewModel(factory = ConfigIoViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 进页面时刷新一次主密码状态（用户可能在别处刚改过）
    LaunchedEffect(Unit) { viewModel.refreshHasZipPassword() }

    // 系统返回键统一走 onBack：避免不同入口（TopBar 按钮 / 系统 back / 手势）
    // 触发到 NavHost 不同的 pop 路径上。
    BackHandler(onBack = onBack)

    // 错误/特殊事件以独立 Dialog 提示；普通成功/失败用 Snackbar
    var wrongPwShown by remember { mutableStateOf(false) }
    var malformedReason by remember { mutableStateOf<String?>(null) }
    var versionUnsupported by remember { mutableStateOf<Int?>(null) }
    var failureReason by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        // 不指定 MIME 子类型也行，但显式 application/zip 让 SAF 上的"保存到"分类更准
        contract = ActivityResultContracts.CreateDocument(ConfigIoViewModel.MIME_ZIP),
    ) { uri ->
        if (uri != null) viewModel.startExport(uri, ctx)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.startImport(uri, ctx)
    }

    val msgExportSuccess = stringResource(R.string.settings_config_io_msg_export_ok)
    val msgImportApplied = stringResource(R.string.settings_config_io_msg_import_ok)
    val msgWeak = stringResource(R.string.settings_config_io_msg_weak_password)

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is ConfigIoEvent.ExportSuccess ->
                    snackbarHostState.showSnackbar("$msgExportSuccess  ${ev.fileName}")
                is ConfigIoEvent.ExportFailed -> failureReason = ev.reason
                is ConfigIoEvent.ImportApplied -> snackbarHostState.showSnackbar(
                    msgImportApplied.format(ev.accounts, ev.templates),
                )
                is ConfigIoEvent.WrongPassword -> wrongPwShown = true
                is ConfigIoEvent.Malformed -> malformedReason = ev.reason
                is ConfigIoEvent.VersionUnsupported -> versionUnsupported = ev.version
                is ConfigIoEvent.Failure -> failureReason = ev.reason
                ConfigIoEvent.NeedZipPassword -> Unit // 已迁移到 state.needsPasswordFor 驱动的 inline 弹框
                ConfigIoEvent.WeakPasswordHint -> snackbarHostState.showSnackbar(msgWeak)
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.settings_config_io_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ConfigIoContent(
            modifier = Modifier.padding(padding),
            state = state,
            onClickExport = {
                exportLauncher.launch(ConfigIoViewModel.buildSuggestedFileName())
            },
            onClickImport = {
                importLauncher.launch(arrayOf(ConfigIoViewModel.MIME_ZIP, "*/*"))
            },
        )
    }

    // 合并策略选择框：pendingImport 不为 null 时弹出
    if (state.pendingImport != null) {
        MergeStrategyDialog(
            strategy = state.pendingStrategy,
            summary = state.pendingSummary,
            busy = state.busy,
            onChange = viewModel::setStrategy,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::cancelImport,
        )
    }

    // 「需要主密码」引导框：state.needsPasswordFor != null 时弹出，输完后由 VM 自动续践。
    val purpose = state.needsPasswordFor
    if (purpose != null) {
        InlineMasterPasswordDialog(
            purpose = purpose,
            onSubmit = { pw -> viewModel.submitInlinePassword(pw, ctx) },
            onDismiss = viewModel::cancelInlinePassword,
        )
    }

    if (wrongPwShown) {
        SimpleInfoDialog(
            title = stringResource(R.string.settings_config_io_wrong_pw_title),
            body = stringResource(R.string.settings_config_io_wrong_pw_body),
            onDismiss = { wrongPwShown = false },
        )
    }
    malformedReason?.let { reason ->
        SimpleInfoDialog(
            title = stringResource(R.string.settings_config_io_malformed_title),
            body = stringResource(R.string.settings_config_io_malformed_body, reason),
            onDismiss = { malformedReason = null },
        )
    }
    versionUnsupported?.let { version ->
        SimpleInfoDialog(
            title = stringResource(R.string.settings_config_io_version_title),
            body = stringResource(R.string.settings_config_io_version_body, version),
            onDismiss = { versionUnsupported = null },
        )
    }
    failureReason?.let { reason ->
        SimpleInfoDialog(
            title = stringResource(R.string.settings_config_io_failure_title),
            body = stringResource(R.string.settings_config_io_failure_body, reason),
            onDismiss = { failureReason = null },
        )
    }
}

@Composable
private fun ConfigIoContent(
    modifier: Modifier,
    state: ConfigIoUiState,
    onClickExport: () -> Unit,
    onClickImport: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        // 顶部说明：解释本页"会导出哪些 / 不会导出哪些"，避免用户对密码外泄产生误解
        Text(
            text = stringResource(R.string.settings_config_io_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        SectionHeader(stringResource(R.string.settings_config_io_section_export))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_config_io_export_row)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_config_io_export_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(enabled = !state.busy, onClick = onClickExport),
        )
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_config_io_section_import))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_config_io_import_row)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_config_io_import_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(enabled = !state.busy, onClick = onClickImport),
        )

        if (state.busy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.settings_config_io_busy))
            }
        }
    }
}

/** 与 SettingsScreen 中 SectionHeader 完全一致的样式，仅作页内分组。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * 合并策略对话框。
 *
 * 三选一 RadioButton + 实时影响摘要 +「确认 / 取消」。
 * 选项变化时让 VM 重算 [ImportSummary] 而不是 UI 自己算，让"哪些条目算冲突"
 * 这种语义跟数据层走同一份代码。
 */
@Composable
private fun MergeStrategyDialog(
    strategy: MergeStrategy,
    summary: ImportSummary?,
    busy: Boolean,
    onChange: (MergeStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_config_io_strategy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StrategyOption(
                    label = stringResource(R.string.settings_config_io_strategy_overwrite),
                    desc = stringResource(R.string.settings_config_io_strategy_overwrite_desc),
                    selected = strategy == MergeStrategy.OVERWRITE,
                    onClick = { onChange(MergeStrategy.OVERWRITE) },
                )
                StrategyOption(
                    label = stringResource(R.string.settings_config_io_strategy_merge),
                    desc = stringResource(R.string.settings_config_io_strategy_merge_desc),
                    selected = strategy == MergeStrategy.MERGE_NEW_ONLY,
                    onClick = { onChange(MergeStrategy.MERGE_NEW_ONLY) },
                )
                StrategyOption(
                    label = stringResource(R.string.settings_config_io_strategy_skip),
                    desc = stringResource(R.string.settings_config_io_strategy_skip_desc),
                    selected = strategy == MergeStrategy.SKIP_EXISTING,
                    onClick = { onChange(MergeStrategy.SKIP_EXISTING) },
                )
                if (summary != null) {
                    Text(
                        text = stringResource(
                            R.string.settings_config_io_strategy_summary_fmt,
                            summary.accountsToWrite,
                            summary.totalAccounts,
                            summary.templatesToWrite,
                            summary.totalTemplates,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 当前策略下不会产生任何写入时，给出醒目提示，避免用户
                    // 点了「确认」但看到 0/0 以为「未生效」。
                    if (summary.accountsToWrite == 0 && summary.templatesToWrite == 0) {
                        Text(
                            text = stringResource(R.string.settings_config_io_strategy_no_change),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.settings_config_io_strategy_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}

@Composable
private fun StrategyOption(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 通用单按钮信息框，用于密码错/解析失败/版本不兼容/其它失败。 */
@Composable
private fun SimpleInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_config_io_ok))
            }
        },
    )
}

/**
 * 「需要主密码」引导框。
 *
 * 与设置页的 ZipPasswordDialog 区开：这里是「设完主密码后自动续践当前导出/导入」的
 * 闭环中间步，避免用户走到设置页后误以为「输完密码=导入完成」。
 */
@Composable
private fun InlineMasterPasswordDialog(
    purpose: PasswordPurpose,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_config_io_inline_pw_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        when (purpose) {
                            PasswordPurpose.EXPORT -> R.string.settings_config_io_inline_pw_desc_export
                            PasswordPurpose.IMPORT -> R.string.settings_config_io_inline_pw_desc_import
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_zip_password_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pw.isNotEmpty()) onSubmit(pw) },
                enabled = pw.isNotEmpty(),
            ) {
                Text(
                    stringResource(
                        when (purpose) {
                            PasswordPurpose.EXPORT -> R.string.settings_config_io_inline_pw_confirm_export
                            PasswordPurpose.IMPORT -> R.string.settings_config_io_inline_pw_confirm_import
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}
