package com.aternos.pulsoximetergraphs.di

import android.content.Context
import com.aternos.pulsoximetergraphs.data.ble.BleGattClient
import com.aternos.pulsoximetergraphs.data.db.AppDatabase
import com.aternos.pulsoximetergraphs.data.repository.ReadingsRepository
import com.aternos.pulsoximetergraphs.data.settings.ThresholdsRepository

/**
 * Manual DI container — deliberately no Hilt: there are only a handful of singletons here
 * (a database, two repositories, a BLE client), which doesn't justify the build-time and
 * conceptual overhead of a full DI framework.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val readingsRepository: ReadingsRepository by lazy {
        ReadingsRepository(database.readingDao())
    }

    val thresholdsRepository: ThresholdsRepository by lazy {
        ThresholdsRepository(appContext)
    }

    val bleGattClient: BleGattClient by lazy {
        BleGattClient(appContext, readingsRepository)
    }
}
