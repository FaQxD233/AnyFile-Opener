package com.anyfile.x.routing

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.anyfile.x.data.DefaultAppRuleScope
import com.anyfile.x.data.DefaultAppRuleStore
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore

class ChooserResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        
        if (component == null || component.packageName == context.packageName) return

        intent.getStringExtra(IntentRouter.EXTRA_CHOOSER_REQUEST_ID)?.let { requestId ->
            ChooserRequestStore.markChosen(context, requestId)
        }

        val mimeType = intent.getStringExtra(IntentRouter.EXTRA_MIME_TYPE) ?: return
        IntentRouter.saveLastApp(context, mimeType, component.packageName)

        val ruleScope = intent.getStringExtra(IntentRouter.EXTRA_RULE_SCOPE)
            ?.let { value ->
                runCatching { DefaultAppRuleScope.valueOf(value) }.getOrNull()
            }
        val ruleKey = intent.getStringExtra(IntentRouter.EXTRA_RULE_KEY)
        if (ruleScope != null && ruleKey != null) {
            val saved = DefaultAppRuleStore.saveRule(
                context,
                ruleScope,
                ruleKey,
                component.packageName
            )
            if (saved) {
                val label = DefaultAppRuleStore.appLabel(context, component.packageName)
                Toast.makeText(
                    context,
                    "$label is now the default for $ruleKey",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val uri = intent.getStringExtra(IntentRouter.EXTRA_FILE_URI)
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?: return
        RecentFileStore.addRecentFileAsync(
            context,
            RecentFile(
                uri = uri.toString(),
                fileName = intent.getStringExtra(IntentRouter.EXTRA_FILE_NAME)
                    ?: uri.lastPathSegment
                    ?: "Unknown file",
                mimeType = mimeType
            )
        )
    }
}
