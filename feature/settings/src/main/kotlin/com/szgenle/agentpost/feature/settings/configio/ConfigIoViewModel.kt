package com.szgenle.agentpost.feature.settings.configio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szgenle.agentpost.core.data.AppServiceLocator
import com.szgenle.agentpost.core.data.MailRepository
import com.szgenle.agentpost.core.datastore.AppPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 配置导入/导出页 UiState。
 *
 * 该页只负责把"重活"委托给 [ConfigExporter] / [ConfigImporter]，
 * 自身只持以下三类瞬态：
 * - [busy]：正在跑导出/导入/解析，UI 应禁用按钮、显示进度；
 * - [hasZipPassword]：用于决定是否弹"先去设主密码"的引导框；
 * - [pendingImport]：[ConfigImporter.readAndDecrypt] 成功后挂起的中间态，
 *   等用户在 UI 上选完合并策略再走 [confirmImport]。
 */
data class ConfigIoUiState(
    val busy: Boolean = false,
    val hasZipPassword: Boolean = false,
    /** 已解密+解析成功、等待用户选合并策略再写入。 */
    val pendingImport: ExportedConfig? = null,
    /** 针对 pendingImport + 当前选中策略预算出的影响范围。 */
    val pendingSummary: ImportSummary? = null,
    val pendingStrategy: MergeStrategy = MergeStrategy.OVERWRITE,
    /**
     * 「需要主密码」引导状态。非 null 时 UI 应在本页弹出输入框，
     * 输完后 [submitInlinePassword] 用保存的 [pendingUri] 自动续践原意图，
     * 避免用户走到设置页输完密码后以为「导入完成」。
     */
    val needsPasswordFor: PasswordPurpose? = null,
    val pendingUri: android.net.Uri? = null,
)

/** 「需要主密码」上下文：区分提示文案与输入后要续践的动作。 */
enum class PasswordPurpose { EXPORT, IMPORT }

/**
 * 单次性事件（Toast / Snackbar / 跳回设置页）。用 Channel 以避免重复消费。
 */
sealed class ConfigIoEvent {
    /** 提示已成功导出，附文件名供 Snackbar 展示。 */
    data class ExportSuccess(val fileName: String) : ConfigIoEvent()
    data class ExportFailed(val reason: String) : ConfigIoEvent()
    /** 导入完成（已落盘）。 */
    data class ImportApplied(val accounts: Int, val templates: Int) : ConfigIoEvent()
    data object WrongPassword : ConfigIoEvent()
    data class Malformed(val reason: String) : ConfigIoEvent()
    data class VersionUnsupported(val version: Int) : ConfigIoEvent()
    data class Failure(val reason: String) : ConfigIoEvent()
    /** 用户尝试导出/导入但还没设主密码：UI 应跳回设置页并展开密码弹框。 */
    data object NeedZipPassword : ConfigIoEvent()
    /** 主密码长度过短的软提示（< 8 位）。导出仍会继续。 */
    data object WeakPasswordHint : ConfigIoEvent()
}

class ConfigIoViewModel(
    private val repo: MailRepository,
    prefs: AppPreferences,
) : ViewModel() {

    private val exporter = ConfigExporter(repo, prefs)
    private val importer = ConfigImporter(repo, prefs)

    private val _state = MutableStateFlow(
        ConfigIoUiState(hasZipPassword = repo.hasZipPassword()),
    )
    val state: StateFlow<ConfigIoUiState> = _state.asStateFlow()

    private val _events = Channel<ConfigIoEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 进入页面时由 UI 主动调用一次刷新，避免后台改了主密码后状态过期。 */
    fun refreshHasZipPassword() {
        _state.value = _state.value.copy(hasZipPassword = repo.hasZipPassword())
    }

    /**
     * 启动导出流程。
     *
     * 调用方需先通过 SAF [androidx.activity.result.contract.ActivityResultContracts.CreateDocument]
     * 拿到 [outputUri]。本方法内会再次校验 [MailRepository.hasZipPassword]：
     * 主密码缺失时将 [outputUri] 暂存到 [ConfigIoUiState.pendingUri] 并设
     * [ConfigIoUiState.needsPasswordFor]=EXPORT，由 UI 在本页弹输入框。
     * 输完后 [submitInlinePassword] 会重新调本方法自动续践。
     */
    fun startExport(outputUri: Uri, ctx: Context) {
        if (_state.value.busy) return
        val password = repo.getZipPassword()
        if (password.isNullOrEmpty()) {
            _state.value = _state.value.copy(
                needsPasswordFor = PasswordPurpose.EXPORT,
                pendingUri = outputUri,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            // 弱密码软提示：< 8 位时只发一次性事件，不阻断导出
            if (password.length < 8) {
                _events.trySend(ConfigIoEvent.WeakPasswordHint)
            }
            val config = runCatching { exporter.build() }.getOrElse { e ->
                _events.trySend(ConfigIoEvent.ExportFailed(e.message.orEmpty()))
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            when (val r = exporter.writeEncryptedZip(config, outputUri, password, ctx)) {
                is ExportResult.Success ->
                    _events.trySend(ConfigIoEvent.ExportSuccess(buildSuggestedFileName(config.exportedAt)))
                is ExportResult.Failure ->
                    _events.trySend(ConfigIoEvent.ExportFailed(r.error.message.orEmpty()))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /**
     * 启动导入流程：读 ZIP → 解密 → 解析 → 算出 [ImportSummary]，挂起到 UiState 等待用户选策略。
     * 主密码缺失时将 [inputUri] 暂存到 pendingUri，同[startExport] 走同一补密码重试逻辑。
     */
    fun startImport(inputUri: Uri, ctx: Context) {
        if (_state.value.busy) return
        val password = repo.getZipPassword()
        if (password.isNullOrEmpty()) {
            _state.value = _state.value.copy(
                needsPasswordFor = PasswordPurpose.IMPORT,
                pendingUri = inputUri,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val r = importer.readAndDecrypt(inputUri, password, ctx)
            when (r) {
                is ImportResult.Loaded -> {
                    // 默认 OVERWRITE：用户主动「导入」的语义就是用这份配置替换本地。
                    // 否则在已有 SELF/同名模板时摘要会是 0/0，造成「看似没生效」的困惑。
                    val strategy = MergeStrategy.OVERWRITE
                    val summary = importer.summarize(r.config, strategy)
                    _state.value = _state.value.copy(
                        busy = false,
                        pendingImport = r.config,
                        pendingSummary = summary,
                        pendingStrategy = strategy,
                    )
                }
                is ImportResult.WrongPassword -> {
                    _events.trySend(ConfigIoEvent.WrongPassword)
                    _state.value = _state.value.copy(busy = false)
                }
                is ImportResult.Malformed -> {
                    _events.trySend(ConfigIoEvent.Malformed(r.reason))
                    _state.value = _state.value.copy(busy = false)
                }
                is ImportResult.VersionUnsupported -> {
                    _events.trySend(ConfigIoEvent.VersionUnsupported(r.version))
                    _state.value = _state.value.copy(busy = false)
                }
                is ImportResult.Failure -> {
                    _events.trySend(ConfigIoEvent.Failure(r.error.message.orEmpty()))
                    _state.value = _state.value.copy(busy = false)
                }
            }
            // 一定要清缓存：解密后的明文 JSON 已落在 cacheDir/configio/
            ConfigExporter.cleanupCache(ctx)
        }
    }

    /**
     * 用户在策略对话框上切换策略时实时重算摘要。
     *
     * 关键：先**同步**把 [strategy] 写入 state，避免用户切完 RadioButton 立刻点「确认」时
     * [confirmImport] 用到旧策略；摘要重算可以稍后异步覆盖。
     */
    fun setStrategy(strategy: MergeStrategy) {
        val pending = _state.value.pendingImport ?: return
        _state.value = _state.value.copy(pendingStrategy = strategy)
        viewModelScope.launch {
            val summary = importer.summarize(pending, strategy)
            // 防止重算期间用户又改了策略，仅在策略仍一致时覆盖摘要
            if (_state.value.pendingStrategy == strategy) {
                _state.value = _state.value.copy(pendingSummary = summary)
            }
        }
    }

    /** 用户确认策略，落盘。 */
    fun confirmImport() {
        val pending = _state.value.pendingImport ?: return
        val strategy = _state.value.pendingStrategy
        val summary = _state.value.pendingSummary
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { importer.apply(pending, strategy) }
                .onSuccess {
                    _events.trySend(
                        ConfigIoEvent.ImportApplied(
                            accounts = summary?.accountsToWrite ?: 0,
                            templates = summary?.templatesToWrite ?: 0,
                        ),
                    )
                }
                .onFailure { e ->
                    _events.trySend(ConfigIoEvent.Failure(e.message.orEmpty()))
                }
            _state.value = _state.value.copy(
                busy = false,
                pendingImport = null,
                pendingSummary = null,
                pendingStrategy = MergeStrategy.OVERWRITE,
            )
        }
    }

    /** 用户取消合并策略框时丢弃 pending。 */
    fun cancelImport() {
        _state.value = _state.value.copy(
            pendingImport = null,
            pendingSummary = null,
            pendingStrategy = MergeStrategy.OVERWRITE,
        )
    }

    /**
     * 在本页补主密码后自动续践。
     *
     * - 保存 [password] 到 vault；
     * - 根据 [ConfigIoUiState.needsPasswordFor] 重新调 [startExport] / [startImport]，
     *   复用之前用户在 SAF 选好的 [ConfigIoUiState.pendingUri]。
     * - 清理 needsPasswordFor / pendingUri 两个引导字段。
     */
    fun submitInlinePassword(password: String, ctx: Context) {
        if (password.isEmpty()) return
        val s = _state.value
        val purpose = s.needsPasswordFor ?: return
        val uri = s.pendingUri ?: return
        repo.setZipPassword(password)
        _state.value = s.copy(
            hasZipPassword = true,
            needsPasswordFor = null,
            pendingUri = null,
        )
        when (purpose) {
            PasswordPurpose.EXPORT -> startExport(uri, ctx)
            PasswordPurpose.IMPORT -> startImport(uri, ctx)
        }
    }

    /** 用户取消「需要主密码」引导框时调用：丢弃未走完的导出/导入意图。 */
    fun cancelInlinePassword() {
        _state.value = _state.value.copy(
            needsPasswordFor = null,
            pendingUri = null,
        )
    }

    companion object {
        /** UI 通过 SAF CreateDocument 时的建议文件名。 */
        fun buildSuggestedFileName(epochMillis: Long = System.currentTimeMillis()): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
            val y = cal.get(java.util.Calendar.YEAR)
            val mo = cal.get(java.util.Calendar.MONTH) + 1
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val mi = cal.get(java.util.Calendar.MINUTE)
            return "agentpost-config-%04d%02d%02d-%02d%02d.zip".format(y, mo, d, h, mi)
        }

        /** 统一的导出/导入 MIME。SAF 上 ZIP 用 application/zip 最稳，避免被某些云盘按 octet-stream 处理。 */
        const val MIME_ZIP: String = "application/zip"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ConfigIoViewModel(
                    repo = AppServiceLocator.mailRepository,
                    prefs = AppServiceLocator.appPreferences,
                )
            }
        }
    }
}
