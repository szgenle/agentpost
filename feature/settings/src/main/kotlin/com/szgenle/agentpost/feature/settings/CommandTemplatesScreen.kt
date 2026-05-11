package com.szgenle.agentpost.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.model.CommandTemplate
import com.szgenle.agentpost.core.ui.R as CoreUiR
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 命令模板管理 ViewModel。
 *
 * 所有 CRUD 走同一个「读 → 变换 → 整表覆盖」链路，天然保证列表内一致性；
 * 写入 [AppPreferences.setCommandTemplates] 采用整表覆盖语义，由本 VM 维护顺序。
 */
class CommandTemplatesViewModel(
    private val prefs: AppPreferences,
) : ViewModel() {

    val state: StateFlow<List<CommandTemplate>> =
        prefs.observeCommandTemplates().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** 新建或编辑：id 非空 → 覆盖；id 为空 → 生成 UUID 并追加到列表尾部。 */
    fun upsert(id: String?, title: String, subject: String, body: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val current = prefs.getCommandTemplates()
            val next = if (id.isNullOrBlank()) {
                current + CommandTemplate(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    subject = subject,
                    body = body,
                    updatedAt = now,
                )
            } else {
                current.map {
                    if (it.id == id) it.copy(
                        title = title,
                        subject = subject,
                        body = body,
                        updatedAt = now,
                    ) else it
                }
            }
            prefs.setCommandTemplates(next)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val next = prefs.getCommandTemplates().filterNot { it.id == id }
            prefs.setCommandTemplates(next)
        }
    }

    fun moveUp(id: String) = swap(id, -1)
    fun moveDown(id: String) = swap(id, +1)

    private fun swap(id: String, delta: Int) {
        viewModelScope.launch {
            val current = prefs.getCommandTemplates().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            val target = idx + delta
            if (idx < 0 || target !in current.indices) return@launch
            val tmp = current[idx]
            current[idx] = current[target]
            current[target] = tmp
            prefs.setCommandTemplates(current)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CommandTemplatesViewModel(prefs = AppServiceLocator.appPreferences)
            }
        }
    }
}

/**
 * 命令模板管理页。
 *
 * 顶栏返回 + 标题；FAB 新建；列表每行四个动作按钮：↑/↓/编辑/删除。
 * 编辑/新建复用同一个 [TemplateEditorDialog]，删除走二次确认 [DeleteConfirmDialog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandTemplatesRoute(
    onBack: () -> Unit,
    viewModel: CommandTemplatesViewModel = viewModel(factory = CommandTemplatesViewModel.Factory),
) {
    val templates by viewModel.state.collectAsStateWithLifecycle()

    // null 表示 dialog 未打开；非 null 时若 initial 为 null 表示「新建」，否则为「编辑」。
    var editorInitial by remember { mutableStateOf<EditorTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<CommandTemplate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.command_templates_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editorInitial = EditorTarget.New }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.command_templates_new),
                )
            }
        },
    ) { padding ->
        if (templates.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.command_templates_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(templates, key = { it.id }) { template ->
                    val index = templates.indexOfFirst { it.id == template.id }
                    ListItem(
                        headlineContent = { Text(template.title) },
                        supportingContent = {
                            val preview = template.subject.take(40)
                            if (preview.isNotBlank()) Text(preview)
                        },
                        trailingContent = {
                            TemplateRowActions(
                                canMoveUp = index > 0,
                                canMoveDown = index < templates.size - 1,
                                onMoveUp = { viewModel.moveUp(template.id) },
                                onMoveDown = { viewModel.moveDown(template.id) },
                                onEdit = { editorInitial = EditorTarget.Edit(template) },
                                onDelete = { pendingDelete = template },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editorInitial?.let { target ->
        TemplateEditorDialog(
            initial = (target as? EditorTarget.Edit)?.template,
            onDismiss = { editorInitial = null },
            onConfirm = { title, subject, body ->
                viewModel.upsert(
                    id = (target as? EditorTarget.Edit)?.template?.id,
                    title = title,
                    subject = subject,
                    body = body,
                )
                editorInitial = null
            },
        )
    }

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            onConfirm = {
                viewModel.delete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 编辑器弹框的开启目标：null=关闭 / New=新建 / Edit=编辑已有模板。 */
private sealed interface EditorTarget {
    data object New : EditorTarget
    data class Edit(val template: CommandTemplate) : EditorTarget
}

@Composable
private fun TemplateRowActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            // 使用 KeyboardArrowLeft + 向上旋转代替 ArrowUpward（material-icons-core 中未提供）
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.graphicsLayer(rotationZ = 90f),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.graphicsLayer(rotationZ = 90f),
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = null)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
    }
}

/**
 * 新建 / 编辑共享同一份表单。
 *
 * - title / body 必填才允许「保存」；subject 允许空（大量指令不需要特别的邮件主题）；
 * - 不回显 updatedAt，属于内部字段。
 */
@Composable
private fun TemplateEditorDialog(
    initial: CommandTemplate?,
    onConfirm: (title: String, subject: String, body: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var subject by remember { mutableStateOf(initial?.subject.orEmpty()) }
    var body by remember { mutableStateOf(initial?.body.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.command_templates_new
                    else R.string.command_templates_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.command_template_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(stringResource(R.string.command_template_field_subject)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.command_template_field_body)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, subject, body) },
                enabled = title.isNotBlank() && body.isNotBlank(),
            ) { Text(stringResource(CoreUiR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.command_templates_delete_confirm_title)) },
        text = { Text(stringResource(R.string.command_templates_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(CoreUiR.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreUiR.string.common_cancel))
            }
        },
    )
}
