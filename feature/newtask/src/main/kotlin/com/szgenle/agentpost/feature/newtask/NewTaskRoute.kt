package com.szgenle.agentpost.feature.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.ui.UiText
import com.szgenle.agentpost.core.ui.R as CoreUiR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewTaskUiState(
    val busy: Boolean = false,
    val sentTaskId: String? = null,
    val error: UiText? = null,
)

class NewTaskViewModel(private val repo: MailRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewTaskUiState())
    val state: StateFlow<NewTaskUiState> = _state.asStateFlow()

    fun send(title: String, body: String) {
        viewModelScope.launch {
            _state.value = NewTaskUiState(busy = true)
            val result = repo.sendNewTask(title = title, body = body, attachments = emptyList())
            _state.value = result.fold(
                onSuccess = { NewTaskUiState(sentTaskId = it) },
                onFailure = { e ->
                    // 服务端错误优先直接展示，其次才 fallback 到本地资源
                    val msg = e.message
                    val uiText = if (!msg.isNullOrBlank()) {
                        UiText.Dynamic(msg)
                    } else {
                        UiText.Resource(R.string.new_task_send_failed)
                    }
                    NewTaskUiState(error = uiText)
                },
            )
        }
    }

    fun consumeSent() { _state.value = NewTaskUiState() }
    fun consumeError() { _state.value = _state.value.copy(error = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { NewTaskViewModel(AppServiceLocator.mailRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskRoute(
    onBack: () -> Unit,
    onSent: () -> Unit,
    viewModel: NewTaskViewModel = viewModel(factory = NewTaskViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    LaunchedEffect(state.sentTaskId) {
        if (state.sentTaskId != null) {
            viewModel.consumeSent()
            onSent()
        }
    }
    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(e.asString(context))
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_task_title)) },
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.new_task_subject_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.new_task_body_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
            )
            Button(
                onClick = { viewModel.send(title.trim(), body) },
                enabled = !state.busy && title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.busy) {
                        stringResource(R.string.new_task_sending)
                    } else {
                        stringResource(CoreUiR.string.common_send)
                    },
                )
            }
        }
    }
}
