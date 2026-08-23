package com.oxipulse.pulsoximetergraphs.data.update

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Looks up the ESP32 firmware asset attached to this repo's latest GitHub release (the same
 * release `.github/workflows/android-release.yml` already publishes the signed APK to on every
 * push to `main` — this reads the *other* asset in that same release), and downloads it.
 *
 * Deliberately built on plain [HttpURLConnection] rather than adding a networking dependency
 * (OkHttp/Retrofit/Ktor) — this is the only networked feature in an otherwise fully local app,
 * so one small class doing two GET requests isn't worth a new library.
 */
object GithubReleaseChecker {

    /** Mirrors the subset of GitHub's "get the latest release" response this actually uses. */
    @Serializable
    private data class ReleaseResponse(
        @SerialName("tag_name") val tagName: String,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    data class FirmwareRelease(
        val version: String,
        val assetName: String,
        val downloadUrl: String,
    )

    sealed interface CheckResult {
        data class Available(val release: FirmwareRelease) : CheckResult
        data object NoFirmwareAsset : CheckResult
        data class Error(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data class Success(val bytes: ByteArray, val md5Hex: String) : DownloadResult
        data class Error(val message: String) : DownloadResult
    }

    private const val API_URL =
        "https://api.github.com/repos/rico132/PulsoximeterGraphs/releases/latest"

    // Matches whichever hardware env's build the firmware-release CI step publishes — see
    // .github/workflows/android-release.yml. Board-specific rather than a single generic name
    // so both esp32-s3-usb-otg and esp32-s3-devkitc-1 images can be attached to the same
    // release without ambiguity about which is which.
    private const val ASSET_NAME_PREFIX = "firmware-esp32-s3-usb-otg"

    private val json = Json { ignoreUnknownKeys = true }

    /** Network call — must not be invoked from the main thread; suspends onto [Dispatchers.IO]. */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // GitHub's REST API rejects requests with no User-Agent at all.
                setRequestProperty("User-Agent", "PulsoximeterGraphs-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val body = connection.readBodyOrThrow()
            val release = json.decodeFromString(ReleaseResponse.serializer(), body)
            val asset = release.assets.firstOrNull { it.name.startsWith(ASSET_NAME_PREFIX) }
                ?: return@withContext CheckResult.NoFirmwareAsset
            CheckResult.Available(
                FirmwareRelease(
                    version = release.tagName,
                    assetName = asset.name,
                    downloadUrl = asset.downloadUrl,
                ),
            )
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads [release]'s asset and computes its MD5 in the same pass — the digest the ESP32
     * will be told to expect and verify against (see BleFirmwareUpdater), computed fresh from
     * exactly the bytes about to be sent, so this catches download corruption too, not just
     * whatever might go wrong over BLE afterward.
     */
    suspend fun downloadFirmware(release: FirmwareRelease): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PulsoximeterGraphs-Android")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true // release assets are served from a redirect to S3.
            }
            if (connection.responseCode !in 200..299) {
                return@withContext DownloadResult.Error("HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            val md5Hex = digest.joinToString("") { "%02x".format(it) }
            DownloadResult.Success(bytes, md5Hex)
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun HttpURLConnection.readBodyOrThrow(): String {
        if (responseCode !in 200..299) {
            throw java.io.IOException("HTTP $responseCode")
        }
        val output = ByteArrayOutputStream()
        inputStream.use { it.copyTo(output) }
        return output.toString(Charsets.UTF_8.name())
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
}
