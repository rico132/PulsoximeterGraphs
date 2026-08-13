package com.aternos.pulsoximetergraphs.data.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * minSdk = 31, so only the modern runtime Bluetooth permissions exist — no legacy
 * ACCESS_FINE_LOCATION-for-scanning branch to support.
 */
object BlePermissions {

    /** The permissions to request together before any scan/connect attempt. */
    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun hasScanPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAllPermissions(context: Context): Boolean =
        hasScanPermission(context) && hasConnectPermission(context)
}
