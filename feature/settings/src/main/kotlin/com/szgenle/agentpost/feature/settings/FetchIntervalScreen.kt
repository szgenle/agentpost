package com.szgenle.agentpost.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.datastore.AppPreferences
import com.szgenle.agentpost.core.datastore.FetchIntervals
import com.szgenle.agentpost.core.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.szgenle.agentpost.core.ui.R as CoreUiR

// 下限与 DataStore 默认值一致，避免用户填出被邮服限流的值。
private const val MIN_FG_SEC = 30
private const val MIN_BG_MIN = 15

data class FetchIntervalUiState(
    val intervals: FetchIntervals = FetchIntervals(60, 15),
    val message: UiText? = null,
)

class FetchIntervalViewModel(
    private val prefs: AppPreferences,
) : ViewModel() {

    private val transient = MutableStateFlow<UiText?>(null)

    val uiState: StateFlow<FetchIntervalUiState> = combine(
        prefs.observeFetchIntervals(),
        transient,
    ) { intervals, msg ->
        FetchIntervalUiState(intervals = intervals, message = msg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FetchIntervalUiState(),
    )

    fun save(foregroundSeconds: Int, backgroundMinutes: Int) {
        viewModelScope.launch {
            val fg = foregroundSeconds.coerceAtLeast(MIN_FG_SEC)
            val bg = backgroundMinutes.coerceAtLeast(MIN_BG_MIN)
            prefs.setFetchIntervals(fg, bg)
            transient.value = UiText.Resource(R.string.settings_fetch_saved)
        }
    }

    fun clearMessage() {
        transient.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FetchIntervalViewModel(prefs = AppServiceLocator.appPreferences)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchIntervalRoute(
    onBack: () -> Unit,
    viewModel: FetchIntervalViewModel = viewModel(factory = FetchIntervalViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.asString(context))
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_fetch_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(CoreUiR.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        FetchIntervalForm(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            initial = state.intervals,
            onSave = viewModel::save,
        )
    }
}

@Composable
private fun FetchIntervalForm(
    modifier: Modifier = Modifier,
    initial: FetchIntervals,
    onSave: (Int, Int) -> Unit,
) {
    var fgText by remember(initial) { mutableStateOf(initial.foregroundSeconds.toString()) }
    var bgText by remember(initial) { mutableStateOf(initial.backgroundMinutes.toString()) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_fetch_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        OutlinedTextField(
            value = fgText,
            onValueChange = { s -> fgText = s.filter { it.isDigit() } },
            label = { Text(stringResource(R.string.settings_fetch_foreground_sec)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = bgText,
            onValueChange = { s -> bgText = s.filter { it.isDigit() } },
            label = { Text(stringResource(R.string.settings_fetch_background_min)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val fg = fgText.toIntOrNull() ?: MIN_FG_SEC
                    val bg = bgText.toIntOrNull() ?: MIN_BG_MIN
                    onSave(fg, bg)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(CoreUiR.string.common_save)) }
        }
    }
}
