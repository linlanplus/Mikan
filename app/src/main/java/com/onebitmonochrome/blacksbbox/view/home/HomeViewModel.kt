package com.onebitmonochrome.blacksbbox.view.home

import android.content.pm.ApplicationInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.device.DeviceSpoofManager
import com.onebitmonochrome.blacksbbox.app.App
import com.onebitmonochrome.blacksbbox.data.AppsRepository
import com.onebitmonochrome.blacksbbox.data.CloneStore
import com.onebitmonochrome.blacksbbox.view.base.BaseViewModel

sealed class HomeItem {
    abstract val key: String

    data class CloneItem(val clone: CloneStore.Clone, val name: String, val icon: Drawable?) : HomeItem() {
        override val key: String get() = clone.key
    }

    data class FolderItem(val folder: CloneStore.Folder, val clones: List<CloneItem>) : HomeItem() {
        override val key: String get() = folder.key
    }
}

class HomeViewModel(private val repo: AppsRepository) : BaseViewModel() {

    val groupLiveData = MutableLiveData<CloneStore.Group>()

    val itemsLiveData = MutableLiveData<List<HomeItem>>()

    val resultLiveData = MutableLiveData<String>()

    val launchLiveData = MutableLiveData<Boolean>()

    /** Engine apps by clone key, from the last refresh. */
    @Volatile
    private var installed: Map<String, ApplicationInfo> = emptyMap()

    private val iconCache = mutableMapOf<String, Drawable?>()

    fun refresh() {
        launchOnUI { load() }
    }

    /** Re-reads the layout without asking the engine again; for edits that only touch the layout. */
    fun refreshLayout() {
        launchOnUI { publish() }
    }

    fun install(source: String, groupId: Long) {
        launchOnUI {
            val core = BlackBoxCore.get()
            val userId = CloneStore.allocateUserId(core.users.map { it.id })
            val result = repo.installApk(source, userId, resultLiveData)
            if (result != null && result.success && !result.packageName.isNullOrEmpty()) {
                val pkg = result.packageName
                val appInfo = core.getInstalledApplications(0, userId)?.firstOrNull { it.packageName == pkg }
                val baseName = appInfo?.let { repo.safeLoadAppLabel(it) } ?: pkg
                CloneStore.addClone(userId, pkg, numberedLabel(pkg, baseName), groupId)
            }
            load()
        }
    }

    fun delete(clone: CloneStore.Clone) {
        launchOnUI {
            val core = BlackBoxCore.get()
            repo.unInstall(clone.packageName, clone.userId, resultLiveData)
            try {
                val stillUsed = core.users.any { it.id == clone.userId } &&
                        !core.getInstalledApplications(0, clone.userId).isNullOrEmpty()
                if (!stillUsed) {
                    if (core.users.any { it.id == clone.userId }) core.deleteUser(clone.userId)
                    DeviceSpoofManager.resetToDefaults(clone.userId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing user ${clone.userId}: ${e.message}")
            }
            CloneStore.removeClone(clone.key)
            load()
        }
    }

    fun clearData(clone: CloneStore.Clone) {
        launchOnUI { repo.clearApkData(clone.packageName, clone.userId, resultLiveData) }
    }

    fun launch(clone: CloneStore.Clone) {
        launchOnUI { repo.launchApk(clone.packageName, clone.userId, launchLiveData) }
    }

    private fun load() {
        try {
            DeviceSpoofManager.ensureInstallSalt()
            val core = BlackBoxCore.get()
            val byUser = mutableMapOf<Int, List<ApplicationInfo>>()
            for (user in core.users) {
                byUser[user.id] = core.getInstalledApplications(0, user.id) ?: emptyList()
            }
            CloneStore.reconcile(byUser.mapValues { (_, apps) -> apps.map { it.packageName } })
            installed = byUser.flatMap { (userId, apps) ->
                apps.map { CloneStore.cloneKey(userId, it.packageName) to it }
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading installed clones: ${e.message}")
        }
        publish()
    }

    private fun publish() {
        val group = CloneStore.currentGroup()
        val items = group.items.mapNotNull { key ->
            if (CloneStore.isFolderKey(key)) {
                val folder = CloneStore.folder(key.drop(2).toLong()) ?: return@mapNotNull null
                val clones = folder.items.mapNotNull { cloneItem(it) }
                if (clones.isEmpty()) null else HomeItem.FolderItem(folder, clones)
            } else {
                cloneItem(key)
            }
        }
        groupLiveData.postValue(group)
        itemsLiveData.postValue(items)
    }

    private fun cloneItem(key: String): HomeItem.CloneItem? {
        val clone = CloneStore.clone(key) ?: return null
        val appInfo = installed[key] ?: return null
        val name = clone.label ?: repo.safeLoadAppLabel(appInfo)
        val icon = synchronized(iconCache) {
            iconCache.getOrPut(clone.packageName) { repo.safeLoadAppIcon(appInfo)?.let(::rasterize) }
        }
        return HomeItem.CloneItem(clone, name, icon)
    }

    /**
     * Draws the icon once into a bitmap. A Drawable can only belong to one view at a time, so
     * sharing one between clones and folder previews made adaptive icons re-render their mask
     * whenever a different view used them; a bitmap can be shown by any number of views.
     */
    private fun rasterize(icon: Drawable): Drawable {
        if (icon is BitmapDrawable) return icon
        val res = App.getContext().resources
        val px = (ICON_DP * res.displayMetrics.density).toInt().coerceAtLeast(1)
        return BitmapDrawable(res, icon.toBitmap(px, px))
    }

    // The first clone of an app shows the app name; later ones get "Name 2", "Name 3", ...
    private fun numberedLabel(pkg: String, baseName: String): String? {
        val others = CloneStore.clonesOf(pkg)
        if (others.isEmpty()) return null
        val used = others.map { it.label ?: baseName }.toSet()
        var n = others.size + 1
        while ("$baseName $n" in used) n++
        return "$baseName $n"
    }

    companion object {
        private const val TAG = "HomeViewModel"
        // Largest size an icon is shown at (the grid and folder panel use 52dp).
        private const val ICON_DP = 56
    }
}
