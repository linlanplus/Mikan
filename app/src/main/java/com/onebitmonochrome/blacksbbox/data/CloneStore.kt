package com.onebitmonochrome.blacksbbox.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.onebitmonochrome.blacksbbox.app.App

/**
 * Home screen layout. Every clone owns one engine user (so it has its own data and device
 * identity); groups ("users" in the UI) and folders only exist here, the engine never sees them.
 *
 * A group lists its top-level items in order: clone keys and folder keys. A folder lists clone keys.
 */
object CloneStore {

    data class Clone(val userId: Int, val packageName: String, var label: String?) {
        val key: String get() = cloneKey(userId, packageName)
    }

    data class Folder(val id: Long, var name: String, val items: MutableList<String> = mutableListOf()) {
        val key: String get() = folderKey(id)
    }

    data class Group(val id: Long, var name: String, val items: MutableList<String> = mutableListOf())

    private const val PREFS = "clone_layout"
    private const val KEY_STATE = "state"
    private const val KEY_CURRENT_GROUP = "current_group"

    private val lock = Any()
    private var loaded = false
    private val groups = mutableListOf<Group>()
    private val folders = mutableMapOf<Long, Folder>()
    private val clones = mutableMapOf<String, Clone>()
    private var nextId = 1L
    private var nextUserId = 0

    fun cloneKey(userId: Int, packageName: String) = "c:$userId:$packageName"

    fun folderKey(id: Long) = "f:$id"

    fun isFolderKey(key: String) = key.startsWith("f:")

    private fun prefs() = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- reading ----

    fun groups(): List<Group> = synchronized(lock) {
        ensureLoaded()
        groups.map { it.copy(items = it.items.toMutableList()) }
    }

    fun currentGroup(): Group = synchronized(lock) {
        ensureLoaded()
        val id = prefs().getLong(KEY_CURRENT_GROUP, groups.first().id)
        (groups.firstOrNull { it.id == id } ?: groups.first()).let { it.copy(items = it.items.toMutableList()) }
    }

    fun setCurrentGroup(groupId: Long) {
        prefs().edit().putLong(KEY_CURRENT_GROUP, groupId).apply()
    }

    fun folder(id: Long): Folder? = synchronized(lock) {
        ensureLoaded()
        folders[id]?.let { it.copy(items = it.items.toMutableList()) }
    }

    fun clone(key: String): Clone? = synchronized(lock) {
        ensureLoaded()
        clones[key]?.copy()
    }

    fun clonesOf(packageName: String): List<Clone> = synchronized(lock) {
        ensureLoaded()
        clones.values.filter { it.packageName == packageName }.map { it.copy() }
    }

    /** Folder holding the clone, or null when it sits directly in a group. */
    fun folderOf(cloneKey: String): Folder? = synchronized(lock) {
        ensureLoaded()
        folders.values.firstOrNull { cloneKey in it.items }?.let { it.copy(items = it.items.toMutableList()) }
    }

    fun cloneCount(groupId: Long): Int = synchronized(lock) {
        ensureLoaded()
        val group = groups.firstOrNull { it.id == groupId } ?: return 0
        group.items.sumOf { key -> if (isFolderKey(key)) folders[key.drop(2).toLong()]?.items?.size ?: 0 else 1 }
    }

    /** Engine user for a new clone. IDs are never reused, so a new clone never inherits an old identity. */
    fun allocateUserId(usedIds: Collection<Int>): Int = synchronized(lock) {
        ensureLoaded()
        val id = maxOf(nextUserId, (usedIds.maxOrNull() ?: -1) + 1)
        nextUserId = id + 1
        save()
        id
    }

    // ---- reconciling with the engine ----

    /**
     * Adds clones the engine has but the layout lacks (installs from before this layout existed).
     * Never removes: while the engine service starts it can report nothing, and dropping entries
     * then would lose names and folders. Callers hide entries the engine does not report.
     */
    fun reconcile(installed: Map<Int, List<String>>) = synchronized(lock) {
        ensureLoaded()
        var changed = false
        for ((userId, packages) in installed) {
            for (pkg in packages) {
                val key = cloneKey(userId, pkg)
                if (key !in clones) {
                    clones[key] = Clone(userId, pkg, null)
                    groups.first().items += key
                    changed = true
                }
            }
            if (userId + 1 > nextUserId) {
                nextUserId = userId + 1
                changed = true
            }
        }
        if (changed) save()
    }

    // ---- editing ----

    fun addClone(userId: Int, packageName: String, label: String?, groupId: Long) = synchronized(lock) {
        ensureLoaded()
        val key = cloneKey(userId, packageName)
        if (key in clones) return@synchronized
        clones[key] = Clone(userId, packageName, label)
        (groups.firstOrNull { it.id == groupId } ?: groups.first()).items += key
        save()
    }

    fun removeClone(key: String) = synchronized(lock) {
        ensureLoaded()
        removeCloneLocked(key)
        save()
    }

    fun renameClone(key: String, label: String?) = synchronized(lock) {
        ensureLoaded()
        clones[key]?.label = label?.trim()?.takeIf { it.isNotEmpty() }
        save()
    }

    fun createGroup(name: String): Long = synchronized(lock) {
        ensureLoaded()
        val group = Group(nextId++, name)
        groups += group
        save()
        group.id
    }

    fun renameGroup(groupId: Long, name: String) = synchronized(lock) {
        ensureLoaded()
        groups.firstOrNull { it.id == groupId }?.name = name
        save()
    }

    /** Deletes an empty group. Returns false if it still has clones or is the last group. */
    fun deleteGroup(groupId: Long): Boolean = synchronized(lock) {
        ensureLoaded()
        val group = groups.firstOrNull { it.id == groupId } ?: return false
        if (groups.size <= 1 || group.items.isNotEmpty()) return false
        groups.remove(group)
        save()
        true
    }

    /** Moves a top-level item or a clone inside a folder to the top level of another group. */
    fun moveToGroup(key: String, groupId: Long) = synchronized(lock) {
        ensureLoaded()
        val target = groups.firstOrNull { it.id == groupId } ?: return@synchronized
        detachLocked(key)
        target.items += key
        cleanFoldersLocked()
        save()
    }

    /** Reorders a top-level item within its group. */
    fun moveInGroup(groupId: Long, key: String, toIndex: Int) = synchronized(lock) {
        ensureLoaded()
        val group = groups.firstOrNull { it.id == groupId } ?: return@synchronized
        if (!group.items.remove(key)) return@synchronized
        group.items.add(toIndex.coerceIn(0, group.items.size), key)
        save()
    }

    /** Drops clone [key] onto [targetKey]: into it if it is a folder, else both form a new folder. */
    fun merge(groupId: Long, key: String, targetKey: String, newFolderName: String) = synchronized(lock) {
        ensureLoaded()
        if (key == targetKey || isFolderKey(key)) return@synchronized
        val group = groups.firstOrNull { it.id == groupId } ?: return@synchronized
        if (isFolderKey(targetKey)) {
            val folder = folders[targetKey.drop(2).toLong()] ?: return@synchronized
            detachLocked(key)
            folder.items += key
        } else {
            val index = group.items.indexOf(targetKey)
            if (index < 0) return@synchronized
            val folder = Folder(nextId++, newFolderName)
            folders[folder.id] = folder
            group.items[index] = folder.key
            detachLocked(key)
            folder.items += targetKey
            folder.items += key
        }
        cleanFoldersLocked()
        save()
    }

    /**
     * Replaces a group's arrangement with what the home screen shows after a drag: [topLevel] in
     * order, and for each folder listed in [folderItems] its clones in order. Keys the screen did not
     * show (clones the engine is not reporting) stay where they were.
     */
    fun applyLayout(groupId: Long, topLevel: List<String>, folderItems: Map<Long, List<String>>) = synchronized(lock) {
        ensureLoaded()
        val group = groups.firstOrNull { it.id == groupId } ?: return@synchronized
        val shown = topLevel.toSet() + folderItems.values.flatten()
        val hidden = group.items.filter { it !in shown }
        group.items.clear()
        group.items.addAll(topLevel)
        group.items.addAll(hidden)
        for ((folderId, keys) in folderItems) {
            val folder = folders[folderId] ?: continue
            val hiddenInFolder = folder.items.filter { it !in shown }
            folder.items.clear()
            folder.items.addAll(keys)
            folder.items.addAll(hiddenInFolder)
        }
        cleanFoldersLocked()
        save()
    }

    fun moveToFolder(key: String, folderId: Long) = synchronized(lock) {
        ensureLoaded()
        val folder = folders[folderId] ?: return@synchronized
        detachLocked(key)
        folder.items += key
        cleanFoldersLocked()
        save()
    }

    /** Moves a clone out of its folder, next to the folder. */
    fun moveOutOfFolder(key: String) = synchronized(lock) {
        ensureLoaded()
        val folder = folders.values.firstOrNull { key in it.items } ?: return@synchronized
        val group = groups.firstOrNull { folder.key in it.items } ?: groups.first()
        folder.items.remove(key)
        group.items.add(group.items.indexOf(folder.key) + 1, key)
        cleanFoldersLocked()
        save()
    }

    fun renameFolder(folderId: Long, name: String) = synchronized(lock) {
        ensureLoaded()
        folders[folderId]?.name = name
        save()
    }

    /** Puts a folder's clones back into its group, where the folder was. */
    fun dissolveFolder(folderId: Long) = synchronized(lock) {
        ensureLoaded()
        val folder = folders.remove(folderId) ?: return@synchronized
        val group = groups.firstOrNull { folder.key in it.items } ?: groups.first()
        val index = group.items.indexOf(folder.key)
        group.items.remove(folder.key)
        group.items.addAll(index.coerceIn(0, group.items.size), folder.items)
        save()
    }

    // ---- internals ----

    private fun removeCloneLocked(key: String) {
        clones.remove(key)
        detachLocked(key)
        cleanFoldersLocked()
    }

    private fun detachLocked(key: String) {
        groups.forEach { it.items.remove(key) }
        folders.values.forEach { it.items.remove(key) }
    }

    /**
     * A folder needs at least two clones, as on a launcher: an empty one is removed and the last
     * clone of a single-clone folder takes the folder's place. Returns whether anything changed.
     */
    private fun cleanFoldersLocked(): Boolean {
        val small = folders.values.filter { it.items.size < 2 }
        for (folder in small) {
            folders.remove(folder.id)
            val group = groups.firstOrNull { folder.key in it.items }
            val last = folder.items.firstOrNull()
            if (group == null) {
                if (last != null) groups.first().items += last
            } else {
                val index = group.items.indexOf(folder.key)
                if (last != null) group.items[index] = last else group.items.removeAt(index)
            }
        }
        return small.isNotEmpty()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = prefs().getString(KEY_STATE, null)
        if (raw != null) {
            try {
                val root = JSONObject(raw)
                nextId = root.optLong("nextId", 1L)
                nextUserId = root.optInt("nextUserId", 0)
                root.optJSONArray("clones")?.forEachObject {
                    val clone = Clone(it.getInt("userId"), it.getString("pkg"), it.optString("label").takeIf { s -> s.isNotEmpty() })
                    clones[clone.key] = clone
                }
                root.optJSONArray("folders")?.forEachObject {
                    val folder = Folder(it.getLong("id"), it.getString("name"), it.getJSONArray("items").toStringList())
                    folders[folder.id] = folder
                }
                root.optJSONArray("groups")?.forEachObject {
                    groups += Group(it.getLong("id"), it.getString("name"), it.getJSONArray("items").toStringList())
                }
            } catch (e: Exception) {
                android.util.Log.e("CloneStore", "Corrupt layout, starting over: ${e.message}")
                groups.clear()
                folders.clear()
                clones.clear()
            }
        }
        if (groups.isEmpty()) {
            groups += Group(nextId++, App.getContext().getString(com.onebitmonochrome.blacksbbox.R.string.default_group_name))
        }
        // Single-clone folders made by older builds.
        if (cleanFoldersLocked()) save()
    }

    private fun save() {
        val root = JSONObject()
        root.put("nextId", nextId)
        root.put("nextUserId", nextUserId)
        root.put("clones", JSONArray().apply {
            clones.values.forEach {
                put(JSONObject().put("userId", it.userId).put("pkg", it.packageName).put("label", it.label ?: ""))
            }
        })
        root.put("folders", JSONArray().apply {
            folders.values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("items", JSONArray(it.items))) }
        })
        root.put("groups", JSONArray().apply {
            groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("items", JSONArray(it.items))) }
        })
        prefs().edit().putString(KEY_STATE, root.toString()).apply()
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) block(getJSONObject(i))
    }

    private fun JSONArray.toStringList(): MutableList<String> = MutableList(length()) { getString(it) }
}
