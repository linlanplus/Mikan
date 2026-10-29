package com.onebitmonochrome.blacksbbox.util

import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import com.onebitmonochrome.blacksbbox.R

/**
 * Update check entry point. No release feed exists yet; once the project is published, fetch the
 * latest release here and show its notes and download link in the same dialog.
 */
object UpdateChecker {

    fun currentVersion(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    fun check(context: Context) {
        MaterialDialog(context).show {
            title(R.string.check_update)
            message(text = context.getString(R.string.check_update_not_ready, currentVersion(context)))
            positiveButton(R.string.done)
        }
    }
}
