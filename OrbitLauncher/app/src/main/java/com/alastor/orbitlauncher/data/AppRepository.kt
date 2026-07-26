package com.alastor.orbitlauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.alastor.orbitlauncher.model.LauncherApp
import java.text.Collator

class AppRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    fun loadApps(): List<LauncherApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val collator = Collator.getInstance()
        val flags = PackageManager.MATCH_ALL

        return packageManager.queryIntentActivities(launcherIntent, flags)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull { resolveInfo ->
                runCatching {
                    val activityInfo = resolveInfo.activityInfo
                    val icon = resolveInfo.loadIcon(packageManager)
                        .toBitmap(width = 144, height = 144, config = Bitmap.Config.ARGB_8888)

                    LauncherApp(
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = activityInfo.packageName,
                        componentName = android.content.ComponentName(
                            activityInfo.packageName,
                            activityInfo.name,
                        ),
                        icon = icon,
                    )
                }.getOrNull()
            }
            .distinctBy { it.id }
            .sortedWith { first, second -> collator.compare(first.label, second.label) }
            .toList()
    }
}
