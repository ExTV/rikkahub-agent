package me.rerere.rikkahub.data.ninerouter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

private const val TAG = "NineRouterService"

enum class NineRouterStatus {
    ONLINE,
    STOPPED,
    NOT_INSTALLED
}

@Serializable
data class NineRouterUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
)

@Serializable
data class NineRouterProviderConnection(
    val id: String,
    val provider: String,
    val name: String,
    val isActive: Boolean,
    val details: String = "",
)

class NineRouterService(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val workspaceRepository: WorkspaceRepository,
    private val json: Json,
) {
    private val defaultPort = 20128
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    suspend fun checkStatus(): NineRouterStatus = withContext(Dispatchers.IO) {
        // 1. Network Probe
        try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$defaultPort/v1/models")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext NineRouterStatus.ONLINE
                }
            }
        } catch (_: Exception) {
            // Server offline or not listening
        }

        // 2. Filesystem Probe
        val startScript = File("/usr/local/bin/9router-start")
        if (startScript.exists() || File("/opt/npm-global/lib/node_modules/9router").exists()) {
            NineRouterStatus.STOPPED
        } else {
            // Check workspace linux roots
            val hasRootScript = workspaceRepository.getAll().any { ws ->
                File("/data/data/${context.packageName}/files/workspaces/${ws.root}/linux/usr/local/bin/9router-start").exists()
            }
            if (hasRootScript) NineRouterStatus.STOPPED else NineRouterStatus.NOT_INSTALLED
        }
    }

    suspend fun wake(): Boolean = withContext(Dispatchers.IO) {
        val workspaces = workspaceRepository.getAll()
        val targetWorkspace = workspaces.firstOrNull() ?: return@withContext false

        try {
            workspaceRepository.startBackground(
                id = targetWorkspace.id,
                command = "/usr/local/bin/9router-start $defaultPort",
                mode = "service",
            )
        } catch (e: Exception) {
            Log.e(TAG, "wake: failed to spawn 9router-start", e)
            return@withContext false
        }

        // Poll every 500ms up to 16 attempts (~8s)
        for (i in 0 until 16) {
            delay(500)
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:$defaultPort/v1/models")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "wake: 9Router is now ONLINE after ${(i + 1) * 500}ms")
                        return@withContext true
                    }
                }
            } catch (_: Exception) {
            }
        }
        false
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val targetWorkspace = workspaceRepository.getAll().firstOrNull() ?: return@withContext false
        try {
            workspaceRepository.executeCommand(
                root = targetWorkspace.root,
                command = "/usr/local/bin/9router-stop",
                timeoutMillis = 5000L,
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "stop: 9router-stop failed", e)
            false
        }
    }

    suspend fun checkUpdate(): NineRouterUpdateInfo = withContext(Dispatchers.IO) {
        var current = "0.5.112"
        try {
            val pkgJson = File("/opt/npm-global/lib/node_modules/9router/package.json")
            if (pkgJson.exists()) {
                val parsed = json.parseToJsonElement(pkgJson.readText()).jsonObject
                current = parsed["version"]?.jsonPrimitive?.content ?: current
            }
        } catch (_: Exception) {
        }

        var latest = current
        try {
            val req = Request.Builder()
                .url("https://registry.npmjs.org/9router/latest")
                .header("User-Agent", "RikkaHub-Agent")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = json.parseToJsonElement(body).jsonObject
                    latest = parsed["version"]?.jsonPrimitive?.content ?: latest
                }
            }
        } catch (_: Exception) {
        }

        val hasUpdate = latest != current && latest.isNotBlank()
        NineRouterUpdateInfo(
            currentVersion = current,
            latestVersion = latest,
            hasUpdate = hasUpdate,
        )
    }

    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        val targetWorkspace = workspaceRepository.getAll().firstOrNull() ?: return@withContext false
        try {
            workspaceRepository.startBackground(
                id = targetWorkspace.id,
                command = "/usr/local/bin/9router-update",
                mode = "job",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "update: failed to launch 9router-update", e)
            false
        }
    }

    fun getLanIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun getLocalEndpoint(): String = "http://127.0.0.1:$defaultPort/v1"

    fun getLanEndpoint(): String = "http://${getLanIp()}:$defaultPort/v1"
}
