package com.outman.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

class AppRepository(private val context: Context) {
    data class App(val component: ComponentName, val label: String, val icon: Drawable?)
    fun load(): List<App> {
        val pm = context.packageManager
        val seen = linkedMapOf<String, App>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (r in pm.queryIntentActivities(intent, 0)) {
                val a = r.activityInfo ?: continue
                if (a.packageName == context.packageName || !a.enabled || !a.exported) continue
                if (seen.containsKey(a.packageName)) continue
                seen[a.packageName] = App(ComponentName(a.packageName, a.name),
                    r.loadLabel(pm).toString(), runCatching { r.loadIcon(pm) }.getOrNull())
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }
    fun launch(app: App) {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
