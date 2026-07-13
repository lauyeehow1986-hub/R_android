package com.rmobile.console.data

import android.content.Context
import com.rmobile.console.data.datafiles.LocalSessionDataStore
import com.rmobile.console.data.datafiles.RemoteSessionDataStore
import com.rmobile.console.data.datafiles.SessionDataStore
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.execution.LocalExecutionEngine
import com.rmobile.console.data.execution.RemoteExecutionEngine
import com.rmobile.console.data.execution.WebRController
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.data.settings.SettingsStore
import java.io.File

object ServiceLocator {
    lateinit var settingsStore: SettingsStore
        private set
    private lateinit var appContext: Context

    private val remoteEngine: ExecutionEngine by lazy {
        RemoteExecutionEngine(RExecutionRepository(NetworkModule.rExecutionApi))
    }
    private val localEngine: ExecutionEngine by lazy {
        LocalExecutionEngine(WebRController(appContext))
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
    }

    /** The engine for the currently-selected setting; read fresh each run so a toggle takes effect immediately. */
    fun currentExecutionEngine(): ExecutionEngine =
        when (settingsStore.executionEngine) {
            ExecutionEngineChoice.LOCAL -> localEngine
            ExecutionEngineChoice.REMOTE -> remoteEngine
        }

    /** The data store for the currently-selected engine; read fresh so a toggle takes effect. */
    fun currentSessionDataStore(): SessionDataStore =
        when (settingsStore.executionEngine) {
            ExecutionEngineChoice.LOCAL -> localDataStore
            ExecutionEngineChoice.REMOTE -> remoteDataStore
        }
}
