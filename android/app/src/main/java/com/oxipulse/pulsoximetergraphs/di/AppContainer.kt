package com.oxipulse.pulsoximetergraphs.di

import android.content.Context
import com.oxipulse.pulsoximetergraphs.data.ble.BleFirmwareUpdateClient
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.db.AppDatabase
import com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository
import com.oxipulse.pulsoximetergraphs.data.settings.TestModePreferenceRepository
import com.oxipulse.pulsoximetergraphs.data.settings.ThemePreferenceRepository
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdsRepository

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

    val testModePreferenceRepository: TestModePreferenceRepository by lazy {
        TestModePreferenceRepository(appContext)
    }

    val bleGattClient: BleGattClient by lazy {
        BleGattClient(appContext, readingsRepository, testModePreferenceRepository)
    }

    val bleFirmwareUpdateClient: BleFirmwareUpdateClient by lazy {
        BleFirmwareUpdateClient(appContext)
    }

    val themePreferenceRepository: ThemePreferenceRepository by lazy {
        ThemePreferenceRepository(appContext)
    }
}
