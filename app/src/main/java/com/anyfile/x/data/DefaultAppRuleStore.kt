package com.anyfile.x.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

enum class DefaultAppRuleScope(val displayName: String) {
    MIME("MIME type"),
    EXTENSION("File extension")
}

data class DefaultAppRule(
    val scope: DefaultAppRuleScope,
    val key: String,
    val packageName: String
)

/**
 * Stores package-level default application rules.
 *
 * Exact MIME rules take precedence over extension rules. MIME parameters such as
 * charset and codecs are deliberately stripped because Android intent filters match
 * the media type itself.
 */
object DefaultAppRuleStore {
    private const val PREFS_NAME = "default_app_rules"
    private const val MIME_PREFIX = "mime:"
    private const val EXTENSION_PREFIX = "extension:"
    private val MIME_PATTERN = Regex("^[a-z0-9!#&^_.+-]+/[a-z0-9!#&^_.+*-]+\\z")

    fun findRule(context: Context, mimeType: String, fileName: String?): DefaultAppRule? {
        val prefs = prefs(context)
        val normalizedMime = normalizeMime(mimeType)
        if (normalizedMime != null) {
            prefs.getString(MIME_PREFIX + normalizedMime, null)?.let { packageName ->
                return DefaultAppRule(DefaultAppRuleScope.MIME, normalizedMime, packageName)
            }
        }

        val extension = normalizeExtension(fileName)
        if (extension != null) {
            prefs.getString(EXTENSION_PREFIX + extension, null)?.let { packageName ->
                return DefaultAppRule(DefaultAppRuleScope.EXTENSION, extension, packageName)
            }
        }

        return null
    }

    fun saveRule(
        context: Context,
        scope: DefaultAppRuleScope,
        rawKey: String,
        packageName: String
    ): Boolean {
        val normalizedKey = normalizeRuleKey(scope, rawKey) ?: return false
        if (packageName.isBlank() || packageName == context.packageName) return false

        return prefs(context).edit()
            .putString(storageKey(scope, normalizedKey), packageName)
            .commit()
    }

    fun removeRule(context: Context, rule: DefaultAppRule) {
        prefs(context).edit().remove(storageKey(rule.scope, rule.key)).apply()
    }

    fun removeRule(context: Context, scope: DefaultAppRuleScope, rawKey: String) {
        val normalizedKey = normalizeRuleKey(scope, rawKey) ?: return
        prefs(context).edit().remove(storageKey(scope, normalizedKey)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun getRules(context: Context): List<DefaultAppRule> = readRules(prefs(context))

    fun observeRules(context: Context): Flow<List<DefaultAppRule>> = callbackFlow {
        val sharedPreferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readRules(sharedPreferences))
        }

        trySend(readRules(sharedPreferences))
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    fun appLabel(context: Context, packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: Exception) {
        packageName
    }

    fun normalizeMime(mimeType: String?): String? {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { MIME_PATTERN.matches(it) }
        return normalized?.takeUnless { it == "*/*" }
    }

    fun normalizeExtension(fileName: String?): String? {
        val cleanName = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        if (!cleanName.contains('.') || cleanName.endsWith('.')) return null

        return cleanName.substringAfterLast('.')
            .trim()
            .lowercase(Locale.ROOT)
            .removePrefix(".")
            .takeIf { it.isNotBlank() && it.length <= 32 }
    }

    private fun normalizeRuleKey(scope: DefaultAppRuleScope, rawKey: String): String? =
        when (scope) {
            DefaultAppRuleScope.MIME -> normalizeMime(rawKey)
            DefaultAppRuleScope.EXTENSION -> normalizeExtension("file.$rawKey")
        }

    private fun storageKey(scope: DefaultAppRuleScope, normalizedKey: String): String =
        when (scope) {
            DefaultAppRuleScope.MIME -> MIME_PREFIX + normalizedKey
            DefaultAppRuleScope.EXTENSION -> EXTENSION_PREFIX + normalizedKey
        }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readRules(preferences: SharedPreferences): List<DefaultAppRule> =
        preferences.all.mapNotNull { (storageKey, value) ->
            val packageName = value as? String ?: return@mapNotNull null
            when {
                storageKey.startsWith(MIME_PREFIX) -> DefaultAppRule(
                    DefaultAppRuleScope.MIME,
                    storageKey.removePrefix(MIME_PREFIX),
                    packageName
                )
                storageKey.startsWith(EXTENSION_PREFIX) -> DefaultAppRule(
                    DefaultAppRuleScope.EXTENSION,
                    storageKey.removePrefix(EXTENSION_PREFIX),
                    packageName
                )
                else -> null
            }
        }.sortedWith(compareBy({ it.scope.ordinal }, { it.key }))
}
