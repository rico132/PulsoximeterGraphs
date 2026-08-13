package com.aternos.pulsoximetergraphs

import android.app.Application
import com.aternos.pulsoximetergraphs.di.AppContainer

class PulsoxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
