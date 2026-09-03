package me.rerere.rikkahub.di

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.HuggingFaceAPI
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexCredentialStore
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexProvider
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokCredentialStore
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiCredentialStore
import me.rerere.rikkahub.data.gemini.GeminiOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiProvider
import me.rerere.rikkahub.data.grok.GrokProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import org.koin.core.qualifier.named
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().conversationCompactionDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }

    // Phase 24 — unified AgentRun ledger. DAO + the single shared writer/reader + the
    // boot-recovery sweep. AgentRunRepository has no cross-dependencies (only the DAO), so
    // there is no DI-cycle risk here.
    single { get<AppDatabase>().agentRunDao() }
    single { AgentRunRepository(get()) }
    single { AgentRunBootRecovery(context = get(), repository = get()) }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            systemPromptBuilder = get(),
        )
    }

    single { me.rerere.rikkahub.data.ai.SystemPromptBuilder() }

    single {
        TranslationHandler(providerManager = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .apply {
                // HEADERS-level logging prints Authorization: Bearer <api-key> to logcat.
                // Debug-only so release builds never leak provider keys to logcat.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    })
                }
            }
            .build().also { SearchService.init(it, get()) }
        client
    }

    single<OkHttpClient>(named("codex")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("grok")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("gemini")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single {
        GrokAccountRepository(
            store = GrokCredentialStore(context = get(), json = get()),
            client = get(named("grok")),
            json = get(),
        )
    }

    single {
        GrokOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("grok")),
            repository = get(),
            json = get(),
        )
    }

    single {
        GeminiAccountRepository(
            store = GeminiCredentialStore(context = get(), json = get()),
            client = get(named("gemini")),
            json = get(),
        )
    }

    single {
        GeminiOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("gemini")),
            repository = get(),
        )
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        HuggingFaceAPI.create(get())
    }

    single {
        CodexAccountRepository(
            store = CodexCredentialStore(context = get(), json = get()),
            client = get(named("codex")),
            json = get(),
        )
    }

    single {
        CodexOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("codex")),
            repository = get(),
        )
    }

    single {
        val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = get()
        val codexRepository: CodexAccountRepository = get()
        val json: Json = get()
        ProviderManager(client = get(), context = get()).also { pm ->
            pm.registerProvider(
                "local_litert",
                me.rerere.locallm.litert.LiteRtProvider(
                    context = get(),
                    runtime = get(),
                    prefs = get(),
                    settingsUpdater = { transform ->
                        settingsStore.update { old -> old.copy(providers = transform(old.providers)) }
                    },
                ),
            )
            pm.registerProvider(
                "local_llamacpp",
                me.rerere.llamacpp.LlamaCppProvider(
                    context = get(),
                    runtime = get(),
                    prefs = get(),
                ),
            )
            pm.registerProvider(
                "codex",
                CodexProvider(
                    client = get(named("codex")),
                    repository = codexRepository,
                    json = json,
                    scope = get(),
                )
            )
            pm.registerProvider(
                "grok",
                GrokProvider(
                    client = get(named("grok")),
                    repository = get<GrokAccountRepository>(),
                    json = json,
                    scope = get(),
                )
            )
            pm.registerProvider(
                "gemini_oauth",
                GeminiProvider(
                    client = get(named("gemini")),
                    repository = get<GeminiAccountRepository>(),
                    json = json,
                )
            )
        }
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
