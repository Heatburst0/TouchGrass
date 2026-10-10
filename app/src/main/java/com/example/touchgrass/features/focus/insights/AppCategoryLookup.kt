package com.example.touchgrass.features.focus.insights

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up installed application categories for distraction classification. */
@Singleton
class AppCategoryLookup @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Classifies a package using its installed application category when available. */
    fun isDistracting(packageName: String): Boolean {
        val category = try {
            context.packageManager.getApplicationInfo(packageName, 0).category
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return DistractionClassifier.isDistracting(packageName, category)
    }
}
