package me.rerere.rikkahub.ui.pages.setting.locallm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.LITERT_PROVIDER_ID
import me.rerere.ai.provider.LLAMACPP_PROVIDER_ID
import me.rerere.llamacpp.LlamaCppCatalog
import me.rerere.locallm.AcceleratorProbe
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import me.rerere.locallm.litert.LiteRtModelMetadata
import me.rerere.locallm.MemoryGuard
import me.rerere.locallm.ModelInstall
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.api.HuggingFaceAPI
import me.rerere.rikkahub.data.api.HuggingFaceModelSearch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.HfModelSearchResult
import okhttp3.OkHttpClient

/**
 * Drives the LiteRT provider configure pane inside the standard
 * SettingProviderDetailPage pipeline (tab 0).
 *
 * State is split into individual flows so ProviderConfigureLiteRT can observe
 * exactly what it needs:
 *  - [downloadProgress]: non-null while a download is running
 *  - [errorMessage]: non-null when the last action failed
 *  - [accelerator]: the cached accelerator string (null = never probed)
 *
 * The [runtime] parameter is what lets this single class drive both the LiteRT and
 * llama.cpp settings tiles: each call site supplies its own [LocalRuntime] and every flow
 * above fans out on it rather than hardcoding one runtime.
 */
class SettingLocalLlmViewModel(
    val runtime: LocalRuntime,
    private val context: Context,
    private val prefs: LocalRuntimePreferences,
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val hfApi: HuggingFaceAPI,
) : ViewModel() {

    data class Progress(val percent: Int, val bytesRead: Long, val totalBytes: Long?)

    private val _downloadProgress = MutableStateFlow<Progress?>(null)
    val downloadProgress: StateFlow<Progress?> = _downloadProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _hfSearchResults = MutableStateFlow<List<HfModelSearchResult>>(emptyList())
    val hfSearchResults: StateFlow<List<HfModelSearchResult>> = _hfSearchResults.asStateFlow()

    private val _hfSearchInProgress = MutableStateFlow(false)
    val hfSearchInProgress: StateFlow<Boolean> = _hfSearchInProgress.asStateFlow()

    /** Repo id the user tapped into, or null when the tile is showing search results.
     *  Non-null with [hfSelectedRepoFiles] still null means the file listing is loading. */
    private val _hfSelectedRepoId = MutableStateFlow<String?>(null)
    val hfSelectedRepoId: StateFlow<String?> = _hfSelectedRepoId.asStateFlow()

    private val _hfSelectedRepoFiles = MutableStateFlow<HuggingFaceModelSearch.FilesResult?>(null)
    val hfSelectedRepoFiles: StateFlow<HuggingFaceModelSearch.FilesResult?> = _hfSelectedRepoFiles.asStateFlow()

    // Tracks the in-flight coroutine for searchHuggingFace/selectHuggingFaceRepo so a stale
    // response from a superseded call can't land after a newer one and overwrite its result.
    private var hfSearchJob: Job? = null
    private var hfSelectRepoJob: Job? = null

    private val _accelerator = MutableStateFlow<String?>(null)
    val accelerator: StateFlow<String?> = _accelerator.asStateFlow()

    /** True (default) when the runtime is locked to CPU. Off lets the probe pick GPU/NNAPI/QNN.
     *  Auto-flipped to true by [me.rerere.rikkahub.RikkaHubApp] when the prior process exited
     *  with a native crash inside the runtime's JNI lib (see crashRecoveryAccelerator). */
    val forceCpu: StateFlow<Boolean> = prefs.forceCpuFlow(runtime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Override for `EngineConfig.maxNumTokens`. null = use the per-model curated default
     *  from `LiteRtModelDefaults`. Persisted in `LocalRuntimePreferences`. */
    val maxNumTokensOverride: StateFlow<Int?> = prefs.maxNumTokensOverrideFlow(runtime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Non-null when the prior process crashed inside the runtime; carries the accelerator
     *  label that crashed so the UI banner can name it. Cleared via [dismissCrashRecovery]. */
    val crashRecoveryAccelerator: StateFlow<String?> = prefs.crashRecoveryFlow(runtime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set of installed model filenames whose GPU vision encoder failed on this device.
     *  Surfaced in InstalledModelRow as a "Vision unavailable on this device — text-only"
     *  caption + a "Re-try vision" button that clears the flag so the next load attempts
     *  GPU vision again (useful after a GPU driver update). */
    val visionUnavailableSet: StateFlow<Set<String>> = prefs.visionUnavailableFlow(runtime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Last-known tok/s telemetry sample per model. Provider stamps a new sample after each
     *  successful stream. Settings renders "12.4 tok/s prefill · 2.7 tok/s decode" under the
     *  installed-model row; Doctor uses this to WARN when sustained decode tps is below the
     *  device's expected band. */
    val perfTelemetry: StateFlow<Map<String, LocalRuntimePreferences.PerfSample>> =
        prefs.perfTelemetryFlow(runtime)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether the provider is currently enabled in persisted settings. */
    val providerEnabled: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { settings ->
            settings.providers.firstOrNull { it.id == providerIdForRuntime() }?.enabled ?: false
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** File names of every model currently registered on the provider. The catalog UI
     *  uses this to render an "Installed" badge instead of an Install button when the
     *  catalog entry's `modelFile` already lives in `provider.models` (modelId == file
     *  name in the LiteRT case). */
    val installedModelFiles: StateFlow<Set<String>> = settingsStore.settingsFlow
        .map { settings ->
            settings.providers.firstOrNull { it.id == providerIdForRuntime() }?.models?.map { it.modelId }?.toSet() ?: emptySet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The llama.cpp catalog entry `startDefaultDownload()` fetches: the smallest entry,
     *  since it's the one guaranteed to fit any device the memory guard would approve at
     *  all. [LlamaCppCatalog.ENTRIES] is ordered smallest-first. */
    private val defaultLlamaCppEntry = LlamaCppCatalog.ENTRIES.first()

    /**
     * The default model URL for the runtime.
     *
     * LiteRT default: litert-community/Qwen2.5-1.5B-Instruct — q8 multi-prefill variant
     * (~1.6 GB on disk). Public and ungated (Apache-2.0), and its chat template supports
     * tool calling, which the agent loop depends on.
     *
     * paulsp94/Qwen3.5-2B-LiteRT-LM was dropped: that model is packaged for a different
     * runtime version and throws FAILED_PRECONDITION: No KV cache inputs found.
     *
     * llama.cpp default: [defaultLlamaCppEntry] from the curated GGUF catalog.
     */
    private val defaultModelUrl: String = when (runtime) {
        LocalRuntime.LiteRT ->
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
        LocalRuntime.LlamaCpp -> defaultLlamaCppEntry.resolveUrl()
    }

    /** Currently only LiteRT is wired; the helper exists so a future runtime can fan out
     *  by adding a `when` arm without touching every flow above. */
    private fun providerIdForRuntime(): kotlin.uuid.Uuid = when (runtime) {
        LocalRuntime.LiteRT -> LITERT_PROVIDER_ID
        LocalRuntime.LlamaCpp -> LLAMACPP_PROVIDER_ID
    }

    init {
        viewModelScope.launch {
            refreshFromDisk()
            migrateExistingModelMetadata()
        }
    }

    /**
     * One-shot startup migration for users who installed LiteRT models BEFORE the
     * ability auto-detect landed. Walks the persisted [ProviderSetting.LiteRtLocal]
     * models list and adds (never removes) capabilities the catalog declares. Idempotent
     * — re-running finds nothing to change and exits without a write. Migration runs
     * each time Settings → LiteRT mounts; cheap (in-memory comparison + at most one
     * DataStore write).
     */
    private suspend fun migrateExistingModelMetadata() {
        val targetId = providerIdForRuntime()
        val provider = settingsStore.settingsFlow.value.providers
            .firstOrNull { it.id == targetId } ?: return
        if (provider.models.isEmpty()) return
        val originals = provider.models.toList()
        var anyChange = false
        val patched = originals.map { model ->
            val current = LiteRtModelMetadata.Capabilities(
                inputModalities = model.inputModalities,
                abilities = model.abilities,
            )
            val target = deriveLocalModelCapabilities(runtime, model.modelId)
            val merged = LiteRtModelMetadata.mergeAdditive(current, target)
            if (merged.inputModalities == model.inputModalities &&
                merged.abilities == model.abilities
            ) {
                model
            } else {
                anyChange = true
                model.copy(
                    inputModalities = merged.inputModalities,
                    abilities = merged.abilities,
                )
            }
        }
        if (!anyChange) return
        val changedCount = patched.indices.count { patched[it] != originals[it] }
        settingsStore.update { old ->
            old.copy(providers = old.providers.map { p ->
                if (p.id == targetId) {
                    var next = p
                    patched.forEach { newModel -> next = next.editModel(newModel) }
                    next
                } else p
            })
        }
        android.util.Log.i(
            "SettingLocalLlmVM",
            "migrateExistingModelMetadata: patched $changedCount model(s) to add catalog-declared abilities/modalities",
        )
    }

    private suspend fun refreshFromDisk() {
        val installed = prefs.installedModels(runtime)

        // Scan for stale HTML files or files with invalid magic bytes masquerading as model
        // binaries. HTML files land when a previous download received an HTML error page
        // (e.g. HF viewer URL). All-zero prefixes land from the old sparse-fill resume bug.
        val brokenFiles = installed.entries.filter { (fileName, path) ->
            runCatching {
                val f = java.io.File(path)
                if (!f.exists()) return@runCatching false
                f.inputStream().use { stream ->
                    val buf = ByteArray(64)
                    val n = stream.read(buf)
                    if (n <= 0) return@runCatching false
                    val sample = String(buf, 0, n, Charsets.UTF_8).trimStart().lowercase()
                    val isHtml = sample.startsWith("<!doctype") ||
                        sample.startsWith("<html") ||
                        sample.startsWith("<head") ||
                        sample.startsWith("<?xml")
                    if (isHtml) return@runCatching true
                    // Magic-byte check: file must look like a valid model for its extension.
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    !ModelInstall.isValidMagicForExtension(ext, buf.copyOf(n))
                }
            }.getOrDefault(false)
        }
        if (brokenFiles.isNotEmpty()) {
            for ((fileName, path) in brokenFiles) {
                runCatching { java.io.File(path).delete() }
                prefs.removeInstalledModel(runtime, fileName)
                updateMyProvider { p ->
                    val modelToRemove = p.models.firstOrNull { it.modelId == fileName }
                    if (modelToRemove != null) p.delModel(modelToRemove) else p
                }
            }
            _errorMessage.value =
                "Removed ${brokenFiles.size} broken model file(s) (HTML response or invalid magic bytes). Re-download to retry."
            return
        }

        // brokenFiles was empty here (non-empty branch returned above), so use the already-read map.
        val finalInstalled = installed

        // Reconcile: any disk-side model that isn't in provider.models gets added.
        // Backfills downloads that landed before the persistence fix at commit 75ea6443.
        val targetId = providerIdForRuntime()
        val currentProvider = settingsStore.settingsFlow.value.providers.firstOrNull { it.id == targetId }
        if (currentProvider != null) {
            val knownModelIds = currentProvider.models.map { it.modelId }.toSet()
            val missing = finalInstalled.keys.filter { it !in knownModelIds }
            for (fileName in missing) {
                val caps = deriveLocalModelCapabilities(runtime, fileName)
                val model = Model(
                    modelId = fileName,
                    displayName = fileName,
                    inputModalities = caps.inputModalities,
                    abilities = caps.abilities,
                )
                updateMyProvider { provider -> provider.addModel(model) }
            }
        }

        // Restore cached accelerator so the UI can display it without re-probing.
        _accelerator.value = prefs.acceleratorFlow(runtime).first()
    }

    private suspend fun probeAndCache(): String {
        val forceCpuNow = prefs.forceCpu(runtime)
        val accel = when (runtime) {
            LocalRuntime.LiteRT -> AcceleratorProbe.probeLiteRt(context, forceCpu = forceCpuNow)
            // llama.cpp is CPU-only in this build (no GPU backend compiled in), so there is
            // nothing to probe and no force-CPU toggle to honour.
            LocalRuntime.LlamaCpp -> "cpu"
        }
        prefs.setAccelerator(runtime, accel)
        _accelerator.value = accel
        return accel
    }

    /** Flip the runtime's force-CPU override AND re-probe so the cached accelerator
     *  flips with it. Off → next probe picks GPU/NNAPI/QNN; on → CPU. Called from the
     *  "Try GPU acceleration" toggle on the LiteRT settings page. */
    fun setForceCpu(force: Boolean) {
        viewModelScope.launch {
            prefs.setForceCpu(runtime, force)
            prefs.clearAccelerator(runtime)
            _accelerator.value = null
            probeAndCache()
        }
    }

    /** Acknowledge the crash-recovery banner — clears the persisted notice so it
     *  doesn't show again on the next launch. */
    fun dismissCrashRecovery() {
        viewModelScope.launch { prefs.clearCrashRecovery(runtime) }
    }

    /** Set the max-context override. Pass null to clear and revert to the curated default. */
    fun setMaxNumTokensOverride(value: Int?) {
        viewModelScope.launch { prefs.setMaxNumTokensOverride(runtime, value) }
    }

    /** Clear the "vision unavailable" flag for [fileName] so the next inference attempts the
     *  GPU vision encoder again. Used by the "Re-try vision" button — the user opts back in
     *  after a GPU driver update or a ROM change. If the encoder fails again, the runtime
     *  will re-stamp the flag automatically. */
    fun retryVisionEncoder(fileName: String) {
        viewModelScope.launch { prefs.clearVisionUnavailable(runtime, fileName) }
    }

    /**
     * Mutates the LiteRtLocal ProviderSetting in the persisted settings store.
     * Identity is established by the stable provider ID constant, not by position.
     */
    private suspend fun updateMyProvider(transform: (ProviderSetting) -> ProviderSetting) {
        val targetId = providerIdForRuntime()
        settingsStore.update { old ->
            old.copy(providers = old.providers.map { p ->
                if (p.id == targetId) transform(p) else p
            })
        }
    }

    fun reDetectAccelerator() {
        viewModelScope.launch {
            prefs.clearAccelerator(runtime)
            _accelerator.value = null
            probeAndCache()
        }
    }

    /**
     * Both entry points below set [_downloadProgress] to a non-null placeholder
     * synchronously, before [viewModelScope.launch] ever suspends, and refuse to start a
     * second download while one is already in flight. The Install buttons in
     * `ProviderConfigure.kt` disable on `downloadProgress != null`, but that value used to
     * stay null until [ModelInstall.download]'s flow reached its first [ModelInstall.Progress.Started]
     * event - after the HTTP response headers arrive - leaving a window in which a fast
     * double-tap launched two concurrent downloads racing to write the same `.partial`
     * file. Setting the placeholder here closes that window at the moment the tap is
     * handled instead of once the network responds.
     */
    fun startDefaultDownload() {
        if (_downloadProgress.value != null) return
        _errorMessage.value = null
        _downloadProgress.value = Progress(0, 0L, null)
        viewModelScope.launch {
            val url = defaultModelUrl
            val mem = MemoryGuard.canLoad(context, modelFileBytes = estimatedSize(runtime))
            if (mem is MemoryGuard.Decision.TooLarge) {
                _downloadProgress.value = null
                _errorMessage.value = context.getString(
                    R.string.local_llm_insufficient_memory_format,
                    mem.requiredFreeBytes / 1_000_000,
                    mem.modelFileBytes / 1_000_000,
                    mem.availMemBytes / 1_000_000,
                )
                return@launch
            }
            executeDownload(url)
        }
    }

    fun startManualDownload(url: String) {
        // Normalise HuggingFace blob URLs → resolve URLs before validation and download.
        // A user pasting the HF viewer URL (/blob/main/<file>) gets a 200 OK that returns
        // HTML, not the model binary. Normalising first makes both URL forms work.
        val normalizedUrl = ModelInstall.normalizeHuggingFaceUrl(url)
        if (!ModelInstall.isValidDownloadUrl(normalizedUrl)) {
            _errorMessage.value = context.getString(R.string.local_llm_invalid_url)
            return
        }
        if (_downloadProgress.value != null) return
        _errorMessage.value = null
        _downloadProgress.value = Progress(0, 0L, null)
        viewModelScope.launch { executeDownload(normalizedUrl) }
    }

    /**
     * Search the public HuggingFace model API for GGUF repos matching [query]. Clears any
     * previously selected repo's file listing, since it belongs to the old search.
     */
    fun searchHuggingFace(query: String) {
        _errorMessage.value = null
        _hfSelectedRepoId.value = null
        _hfSelectedRepoFiles.value = null
        hfSearchJob?.cancel()
        hfSearchJob = viewModelScope.launch {
            _hfSearchInProgress.value = true
            HuggingFaceModelSearch.search(hfApi, query)
                .onSuccess { _hfSearchResults.value = it }
                .onFailure {
                    _hfSearchResults.value = emptyList()
                    _errorMessage.value = context.getString(
                        R.string.local_llm_hf_search_failed_format,
                        it.message ?: it::class.simpleName ?: "",
                    )
                }
            _hfSearchInProgress.value = false
        }
    }

    /** List [repoId]'s `.gguf` files. A gated or private repo lands in [hfSelectedRepoFiles]
     *  as [HuggingFaceModelSearch.FilesResult.RequiresAccess]; see that type's doc for why
     *  this never shows up as a failed or hanging download instead. */
    fun selectHuggingFaceRepo(repoId: String) {
        _hfSelectedRepoId.value = repoId
        _hfSelectedRepoFiles.value = null
        hfSelectRepoJob?.cancel()
        hfSelectRepoJob = viewModelScope.launch {
            _hfSelectedRepoFiles.value = HuggingFaceModelSearch.listGgufFiles(hfApi, repoId)
        }
    }

    fun clearHuggingFaceSelection() {
        _hfSelectedRepoId.value = null
        _hfSelectedRepoFiles.value = null
    }

    /** Install a file found via HuggingFace search through the same [startManualDownload]
     *  path a pasted URL uses: [ModelInstall.download] already resumes and validates. */
    fun installFromHuggingFace(repoId: String, fileName: String) {
        startManualDownload(HuggingFaceModelSearch.resolveUrl(repoId, fileName))
    }

    /**
     * Core download loop shared by [startDefaultDownload] and [startManualDownload].
     * Streams progress into [_downloadProgress], registers the finished file in
     * [LocalRuntimePreferences] and the provider's models list, then calls [refreshFromDisk].
     */
    private suspend fun executeDownload(url: String) {
        val fileName = ModelInstall.extractFileNameFromUrl(url)
        val baseDir = ModelInstall.localModelsDir(context)
        val target = ModelInstall.targetFile(baseDir, runtime, fileName)
        // Belt-and-braces against any future throw from inside the flow layers we
        // don't fully control (OkHttp interceptors, Coroutine cancellation racing the
        // socket close, ...). The flow itself catches IOException and emits Progress.Failed,
        // but a CancellationException or RuntimeException from a deeper layer would
        // propagate through .collect{} to this Main-context coroutine and crash the
        // process. Treat any non-cancellation throw as a download failure.
        try {
            collectDownloadProgress(url, fileName, target)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Cancellation is normal — user navigated away or hit Cancel. Don't surface.
            _downloadProgress.value = null
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w("LocalLlmVM", "Uncaught download failure", t)
            _downloadProgress.value = null
            _errorMessage.value = "Download failed: ${t::class.simpleName}: ${t.message ?: ""}"
        }
    }

    private suspend fun collectDownloadProgress(url: String, fileName: String, target: java.io.File) {
        collectInstallProgress(fileName, ModelInstall.download(httpClient, url, target))
    }

    /**
     * Shared progress + registration handling for both [collectDownloadProgress] (HTTP
     * fetch) and the SAF import in [installPickedFile] (local stream copy) — both emit the
     * same [ModelInstall.Progress] events, so a completed one is registered identically:
     * [fileName] lands in [prefs] and the provider's models the same way a completed
     * download always has.
     */
    private suspend fun collectInstallProgress(fileName: String, progress: Flow<ModelInstall.Progress>) {
        progress.collect { p ->
            when (p) {
                is ModelInstall.Progress.Started ->
                    _downloadProgress.value = Progress(0, 0L, p.totalBytes)
                is ModelInstall.Progress.Tick -> {
                    val total = p.totalBytes
                    val pct = if (total != null && total > 0)
                        ((p.bytesRead * 100) / total).toInt() else 0
                    _downloadProgress.value = Progress(pct, p.bytesRead, total)
                }
                is ModelInstall.Progress.Done -> {
                    _downloadProgress.value = null
                    prefs.addInstalledModel(runtime, fileName, p.file.absolutePath)
                    val caps = deriveLocalModelCapabilities(runtime, fileName)
                    val model = Model(
                        modelId = fileName,
                        displayName = fileName,
                        inputModalities = caps.inputModalities,
                        abilities = caps.abilities,
                    )
                    updateMyProvider { provider -> registerInstalledModel(provider, model) }
                    // Enable the provider automatically after the first successful install.
                    updateMyProvider(::enableAfterFirstDownload)
                    refreshFromDisk()
                }
                is ModelInstall.Progress.Failed -> {
                    _downloadProgress.value = null
                    _errorMessage.value = p.cause.message.orEmpty()
                }
            }
        }
    }

    /**
     * Import a model file the user already has on-device, picked via the SAF file picker on
     * the settings tile. Copies [inputStream] into [ModelInstall.targetFile] for [fileName],
     * validating it against [expectedExtensionForRuntime] the same way a downloaded file is
     * validated, then registers it exactly like [collectDownloadProgress] does on success.
     */
    fun installPickedFile(inputStream: java.io.InputStream, fileName: String) {
        _errorMessage.value = null
        viewModelScope.launch {
            // The document provider's DISPLAY_NAME is untrusted input, so it gets reduced
            // to a bare file name the same way extractFileNameFromUrl() does for downloads,
            // before it ever reaches ModelInstall.targetFile()/prefs/Model.modelId. Without
            // this, a crafted DISPLAY_NAME containing '/' or '..' segments could write
            // outside local-models/llamacpp/ or store a path where a bare file name is
            // required.
            val safeFileName = fileName.substringAfterLast('/')
            if (safeFileName.isBlank() || safeFileName == "." || safeFileName == "..") {
                runCatching { inputStream.close() }
                _errorMessage.value = "Import failed: invalid file name from picker: $fileName"
                return@launch
            }
            val baseDir = ModelInstall.localModelsDir(context)
            val target = ModelInstall.targetFile(baseDir, runtime, safeFileName)
            try {
                collectInstallProgress(
                    safeFileName,
                    ModelInstall.copyFromStream(
                        input = inputStream,
                        target = target,
                        totalBytes = null,
                        expectedExtension = expectedExtensionForRuntime(),
                    ),
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // Cancellation is normal — user navigated away. Don't surface as an error.
                _downloadProgress.value = null
                throw cancel
            } catch (t: Throwable) {
                android.util.Log.w("LocalLlmVM", "Uncaught import failure", t)
                _downloadProgress.value = null
                _errorMessage.value = "Import failed: ${t::class.simpleName}: ${t.message ?: ""}"
            }
        }
    }

    /** The file extension a picked-file import must carry the right magic bytes for. */
    private fun expectedExtensionForRuntime(): String = when (runtime) {
        LocalRuntime.LiteRT -> "litertlm"
        LocalRuntime.LlamaCpp -> "gguf"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Delete an installed model file from disk and remove it from the provider's model list.
     * Handles the full three-step cleanup: disk file, LocalRuntimePreferences, provider.models.
     */
    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            val installed = prefs.installedModels(runtime)
            val path = installed[fileName]
            if (path != null) {
                runCatching { java.io.File(path).delete() }
                // Also clean up any leftover partial file from a previous interrupted download.
                runCatching { java.io.File("$path.partial").delete() }
            }
            prefs.removeInstalledModel(runtime, fileName)
            updateMyProvider { p ->
                val modelToRemove = p.models.firstOrNull { it.modelId == fileName }
                if (modelToRemove != null) p.delModel(modelToRemove) else p
            }
            _errorMessage.value = null
        }
    }

    /**
     * Update the display name shown in the chat model picker.
     * Does NOT change modelId or the on-disk filename — the file lookup stays intact.
     */
    fun renameModel(modelId: String, newDisplayName: String) {
        if (newDisplayName.isBlank()) return
        viewModelScope.launch {
            updateMyProvider { p ->
                val current = p.models.firstOrNull { it.modelId == modelId }
                    ?: return@updateMyProvider p
                p.editModel(current.copy(displayName = newDisplayName.trim()))
            }
        }
    }

    private fun estimatedSize(rt: LocalRuntime): Long = when (rt) {
        // Gallery allowlist sizeInBytes = 1_597_931_520 (~1.49 GB) + 200 MB safety pad.
        LocalRuntime.LiteRT -> 1_800_000_000L
        // The real byte size of defaultLlamaCppEntry, straight from the verified catalog.
        LocalRuntime.LlamaCpp -> defaultLlamaCppEntry.sizeBytes
    }
}

/**
 * Auto-enable transform applied after a model download completes: local-runtime provider
 * settings (LiteRT, llama.cpp) flip to `enabled = true` so the just-downloaded model becomes
 * usable without a second trip to Settings. Everything else (cloud providers, AICore) passes
 * through unchanged — this only ever runs against the provider identified by
 * [SettingLocalLlmViewModel.providerIdForRuntime], but stays a total function over
 * [ProviderSetting] so it's directly unit-testable.
 */
internal fun enableAfterFirstDownload(provider: ProviderSetting): ProviderSetting = when (provider) {
    is ProviderSetting.LiteRtLocal -> provider.copy(enabled = true)
    is ProviderSetting.LlamaCppLocal -> provider.copy(enabled = true)
    else -> provider
}

/**
 * Registers a just-installed local model on [provider], shared by both the download and the
 * SAF file-import routes through [SettingLocalLlmViewModel.collectInstallProgress]. [addModel]
 * is a plain list append with no dedup, so calling it unconditionally for a file name that
 * already has a registered [Model] would leave two entries carrying the same `modelId` but
 * different [Model.id]s — the older one an unreachable ghost that [Model.id]-keyed deletion
 * can never clean up. When an existing model already has [model]'s `modelId`, this updates
 * that entry in place (keeping its [Model.id] so [ProviderSetting.editModel] finds it) instead
 * of appending a second one.
 */
internal fun registerInstalledModel(provider: ProviderSetting, model: Model): ProviderSetting {
    val existing = provider.models.firstOrNull { it.modelId == model.modelId }
    return if (existing != null) {
        provider.editModel(model.copy(id = existing.id))
    } else {
        provider.addModel(model)
    }
}

/**
 * Runtime-aware capability derivation for a just-installed or freshly-reconciled model
 * file, shared by [SettingLocalLlmViewModel.migrateExistingModelMetadata],
 * [SettingLocalLlmViewModel.refreshFromDisk]'s reconcile step, and the post-download
 * registration path. LiteRT routes through the catalog-driven
 * [LiteRtModelMetadata.deriveCapabilities]. llama.cpp has no per-file config table and no
 * vision support in this build (vision is a spec non-goal), so it always reports TEXT-only
 * input; TOOL and REASONING come from a matching [LlamaCppCatalog] entry's tags when the
 * file is a catalog pick. A file that isn't in the catalog (e.g. a manually installed GGUF)
 * still gets TOOL — the same "assume tool-tuned" fallback LiteRT applies to files outside
 * its own catalog.
 */
internal fun deriveLocalModelCapabilities(
    runtime: LocalRuntime,
    fileName: String,
): LiteRtModelMetadata.Capabilities = when (runtime) {
    LocalRuntime.LiteRT -> LiteRtModelMetadata.deriveCapabilities(fileName)
    LocalRuntime.LlamaCpp -> {
        val tags = LlamaCppCatalog.ENTRIES.firstOrNull { it.file == fileName }?.tags.orEmpty()
        LiteRtModelMetadata.Capabilities(
            inputModalities = listOf(Modality.TEXT),
            abilities = buildList {
                add(ModelAbility.TOOL)
                if ("thinking" in tags) add(ModelAbility.REASONING)
            },
        )
    }
}
