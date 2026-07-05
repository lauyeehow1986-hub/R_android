package com.rmobile.console.data.network

import com.rmobile.console.BuildConfig
import com.rmobile.console.data.settings.BaseUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual DI: a single Retrofit/OkHttp instance shared by the app. No DI
 * framework needed at this scale.
 *
 * The backend URL and API key are **runtime-configurable** (Settings screen):
 * Retrofit is built once against a placeholder base URL, and
 * [HostSelectionInterceptor] rewrites each outgoing request to the currently
 * configured host and attaches the API key. This avoids rebuilding Retrofit
 * whenever the user changes the URL.
 */
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true }

    private val hostSelectionInterceptor = HostSelectionInterceptor(BuildConfig.R_EXECUTION_BASE_URL)

    /** Apply a new backend URL / API key. Call from settings changes and app start. */
    fun updateConfig(baseUrl: String, apiKey: String) {
        hostSelectionInterceptor.setBaseUrl(baseUrl)
        hostSelectionInterceptor.apiKey = apiKey.takeIf { it.isNotBlank() }
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(hostSelectionInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                },
            )
            .build()
    }

    val rExecutionApi: RExecutionApi by lazy {
        Retrofit.Builder()
            // Placeholder; the real host/scheme/port come from the interceptor.
            .baseUrl(BuildConfig.R_EXECUTION_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RExecutionApi::class.java)
    }

    // A plain client with no host-rewriting interceptor, so a connection test can
    // hit an arbitrary (not-yet-saved) URL directly.
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    /**
     * Liveness check against `<baseUrl>health` (never requires auth server-side,
     * but the key is sent anyway). Confirms the backend is reachable; it does not
     * validate the API key, since only `/execute` is protected.
     */
    suspend fun probeHealth(baseUrl: String, apiKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = BaseUrlValidator.normalize(baseUrl)
                    ?: error("Enter a valid http(s) URL first.")
                val request = Request.Builder()
                    .url(normalized + "health")
                    .get()
                    .apply { if (apiKey.isNotBlank()) header("X-API-Key", apiKey) }
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Backend responded with HTTP ${response.code}.")
                }
            }
        }
}

/**
 * Rewrites each request's scheme/host/port (and path prefix) to the configured
 * base URL, and attaches the optional `X-API-Key` header. Standard OkHttp
 * pattern for a runtime-selectable host. Reads are `@Volatile` because OkHttp
 * calls interceptors on background threads.
 */
private class HostSelectionInterceptor(defaultBaseUrl: String) : Interceptor {

    @Volatile
    private var base = defaultBaseUrl.toHttpUrlOrNull()

    @Volatile
    var apiKey: String? = null

    fun setBaseUrl(url: String) {
        url.toHttpUrlOrNull()?.let { base = it }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = base

        val rewritten = if (target != null) {
            // Preserve any path prefix in the configured base URL (e.g. /api),
            // then append the request's own path segments (e.g. execute).
            val newUrl = target.newBuilder()
                .addPathSegments(request.url.encodedPath.removePrefix("/"))
                .encodedQuery(request.url.encodedQuery)
                .build()
            request.newBuilder().url(newUrl).build()
        } else {
            request
        }

        val withAuth = apiKey?.let {
            rewritten.newBuilder().header("X-API-Key", it).build()
        } ?: rewritten

        return chain.proceed(withAuth)
    }
}
