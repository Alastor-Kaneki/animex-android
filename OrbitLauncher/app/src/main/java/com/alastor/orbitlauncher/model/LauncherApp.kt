package com.alastor.orbitlauncher.model

import android.content.ComponentName
import android.graphics.Bitmap

data class LauncherApp(
    val label: String,
    val packageName: String,
    val componentName: ComponentName,
    val icon: Bitmap,
) {
    val id: String = componentName.flattenToString()
}
