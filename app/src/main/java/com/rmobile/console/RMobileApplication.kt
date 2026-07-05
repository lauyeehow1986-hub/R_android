package com.rmobile.console

import android.app.Application
import com.rmobile.console.data.ServiceLocator

class RMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
