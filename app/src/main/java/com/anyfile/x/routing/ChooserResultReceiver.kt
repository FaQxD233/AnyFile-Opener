package com.anyfile.x.routing

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.anyfile.x.data.DefaultAppRuleScope
import com.anyfile.x.data.DefaultAppRuleStore

class ChooserResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        
        val mimeType = intent.getStringExtra(IntentRouter.EXTRA_MIME_TYPE)
        if (component != null && mimeType != null && component.packageName != context.packageName) {
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
        }
    }
}
