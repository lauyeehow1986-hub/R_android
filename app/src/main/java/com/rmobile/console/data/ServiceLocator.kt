package com.rmobile.console.data

import android.content.Context
import com.rmobile.console.data.datafiles.LocalSessionDataStore
import com.rmobile.console.data.datafiles.RemoteSessionDataStore
import com.rmobile.console.data.datafiles.SessionDataStore
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.execution.LocalExecutionEngine
import com.rmobile.console.data.execution.RemoteExecutionEngine
import com.rmobile.console.data.execution.WebRController
import com.rmobile.console.data.execution.SwapPhase
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ServiceLocator {
    lateinit var settingsStore: SettingsStore
        private set
    private lateinit var appContext: Context

    // The swap-phase flow lives here (not on the controller) so reading it never
    // forces the WebR WebView to boot — a Remote-only user pays nothing.
    private val swapPhaseSink = MutableStateFlow(SwapPhase.IDLE)
    /** Local session-swap progress, for a "Switching project…" indicator. */
    val swapProgress: StateFlow<SwapPhase> = swapPhaseSink.asStateFlow()

    private val webRController: WebRController by lazy { WebRController(appContext, swapPhaseSink) }

    private val remoteEngine: ExecutionEngine by lazy {
        RemoteExecutionEngine(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localEngine: ExecutionEngine by lazy {
        LocalExecutionEngine(webRController)
    }

    private val remoteDataStore: SessionDataStore by lazy {
        RemoteSessionDataStore(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localDataStore: SessionDataStore by lazy {
        LocalSessionDataStore(File(appContext.filesDir, "localdata"))
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsStore = SettingsStore(context)
        NetworkModule.updateConfig(settingsStore.baseUrl, settingsStore.apiKey)

        // One-time: adopt the pre-per-project shared Local state into the last-open project.
        com.rmobile.console.data.execution.LegacyLocalStateMigration.migrate(
            appContext.filesDir,
            settingsStore.loadLastOpenProjectId()?.let { "proj-$it" },
        )
    }

    fun engineFor(choice: ExecutionEngineChoice): ExecutionEngine =
        when (choice) {
            ExecutionEngineChoice.LOCAL -> localEngine
            ExecutionEngineChoice.REMOTE -> remoteEngine
        }

    fun dataStoreFor(choice: ExecutionEngineChoice): SessionDataStore =
        when (choice) {
            ExecutionEngineChoice.LOCAL -> localDataStore
            ExecutionEngineChoice.REMOTE -> remoteDataStore
        }
}
