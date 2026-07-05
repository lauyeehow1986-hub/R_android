package com.rmobile.console.data

import android.content.Context
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.settings.SettingsStore

/**
 * Tiny manual service locator. Initialized once from [RMobileApplication] and
 * read by the (context-less) ViewModels' default constructors. Production only —
 * unit tests inject their own fakes and never touch this.
 */
object ServiceLocator {

    lateinit var settingsStore: SettingsStore
        private set

    fun init(context: Context) {
        settingsStore = SettingsStore(context)
        // Apply persisted settings to the network layer before any request runs.
        NetworkModule.updateConfig(settingsStore.baseUrl, settingsStore.apiKey)
    }
}
