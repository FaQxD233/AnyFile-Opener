package com.anyfile.x.files

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Reads package details from an APK. PackageManager only parses archives by file
 * path, so the APK is copied to a temporary cache file that is deleted afterwards.
 */
object ApkInfoReader {

    data class ApkInfo(
        val label: String?,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val minSdk: Int?,
        val targetSdk: Int?,
        /** SHA-256 fingerprints of the signing certificates, colon separated. */
        val signers: List<String>
    )

    private const val DIRECTORY = "apk-info"

    suspend fun read(
        context: Context,
        source: Uri,
        size: Long?,
        onProgress: (Long, Long?) -> Unit
    ): ApkInfo = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val folder = File(appContext.cacheDir, DIRECTORY).apply { mkdirs() }
        val apk = File(folder, "${UUID.randomUUID()}.apk")
        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IOException("Cannot read the file")
            input.use { stream ->
                apk.outputStream().use { output ->
                    StreamPump.pump(stream, size, onProgress) { buffer, length ->
                        output.write(buffer, 0, length)
                    }
                }
            }
            parse(appContext.packageManager, apk.absolutePath)
        } finally {
            apk.delete()
        }
    }

    private fun parse(packageManager: PackageManager, path: String): ApkInfo {
        val info = archiveInfo(packageManager, path)
            ?: throw IOException("Android could not parse this APK")
        val application = info.applicationInfo
        // Needed for the label and resources of an archive that is not installed.
        application?.sourceDir = path
        application?.publicSourceDir = path
        return ApkInfo(
            label = application?.let { packageManager.getApplicationLabel(it).toString() },
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            minSdk = application?.minSdkVersion,
            targetSdk = application?.targetSdkVersion,
            signers = signatures(info).map { fingerprint(it) }
        )
    }

    private fun archiveInfo(packageManager: PackageManager, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            return signers?.toList().orEmpty()
        }
        return info.signatures?.toList().orEmpty()
    }

    private fun fingerprint(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return FileHasher.toHex(digest).uppercase(Locale.ROOT).chunked(2).joinToString(":")
    }
}
