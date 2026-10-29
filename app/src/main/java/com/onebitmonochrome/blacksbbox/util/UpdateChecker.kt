package com.onebitmonochrome.blacksbbox.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.MaterialDialog
import com.onebitmonochrome.blacksbbox.R
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases of this project for a newer version. */
object UpdateChecker {
    const val REPO = "1319394233a-ops/Mikan"
    const val REPO_URL = "https://github.com/$REPO"
    private const val RELEASES_URL = "$REPO_URL/releases"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val TAG = "UpdateChecker"
    private const val KEY_LAST_AUTO_CHECK = "update_last_auto_check"
    private const val AUTO_CHECK_INTERVAL = 24L * 60 * 60 * 1000

    data class Release(val version: String, val notes: String, val url: String)

    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** From the settings button: always reports the outcome. */
    fun check(activity: Activity) {
        val checking = MaterialDialog(activity).show {
            title(R.string.check_update)
            message(R.string.check_update_checking)
            cancelOnTouchOutside(false)
        }
        fetchLatest { release, error ->
            checking.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@fetchLatest
            val current = currentVersion(activity)
            when {
                error != null -> MaterialDialog(activity).show {
                    title(R.string.check_update)
                    message(R.string.check_update_failed)
                    positiveButton(R.string.check_update_open_page) { open(activity, RELEASES_URL) }
                    negativeButton(R.string.cancel)
                }
                release != null && isNewer(release.version, current) -> showNewVersion(activity, release)
                else -> MaterialDialog(activity).show {
                    title(R.string.check_update)
                    message(text = activity.getString(R.string.check_update_latest, current))
                    positiveButton(R.string.done)
                }
            }
        }
    }

    /** On app start, at most once a day: speaks up only when a newer version exists. */
    fun checkQuietly(activity: Activity) {
        val sp = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_AUTO_CHECK, 0L) < AUTO_CHECK_INTERVAL) return
        sp.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply()
        fetchLatest { release, _ ->
            if (activity.isFinishing || activity.isDestroyed) return@fetchLatest
            if (release != null && isNewer(release.version, currentVersion(activity))) showNewVersion(activity, release)
        }
    }

    private fun showNewVersion(activity: Activity, release: Release) {
        MaterialDialog(activity).show {
            title(text = activity.getString(R.string.check_update_new_title, release.version))
            message(text = release.notes.ifBlank { activity.getString(R.string.check_update_no_notes) })
            positiveButton(R.string.check_update_download) { open(activity, release.url) }
            negativeButton(R.string.check_update_later)
        }
    }

    private fun fetchLatest(done: (Release?, Exception?) -> Unit) {
        Thread {
            val result = try {
                Result.success(latestRelease())
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                Result.failure(e)
            }
            main.post { done(result.getOrNull(), result.exceptionOrNull() as? Exception) }
        }.start()
    }

    // Newest published release, test builds included; null when there is none yet.
    private fun latestRelease(): Release? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Mikan-Android")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                return Release(
                        version = r.getString("tag_name").removePrefix("v"),
                        notes = r.optString("body"),
                        url = r.optString("html_url", RELEASES_URL)
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Compares versions like "0.9.0-beta" and "1.0.0": numbers first; with equal numbers a final
     * release is newer than a test build, and test builds compare by their suffix ("beta.2" > "beta").
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun split(v: String): Pair<List<Int>, String> {
            val core = v.substringBefore('-')
            val suffix = if ('-' in v) v.substringAfter('-') else ""
            return core.split('.').map { it.toIntOrNull() ?: 0 } to suffix
        }
        val (a, aSuffix) = split(candidate)
        val (b, bSuffix) = split(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return when {
            aSuffix == bSuffix -> false
            aSuffix.isEmpty() -> true
            bSuffix.isEmpty() -> false
            else -> aSuffix > bSuffix
        }
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $url: ${e.message}")
        }
    }
}
