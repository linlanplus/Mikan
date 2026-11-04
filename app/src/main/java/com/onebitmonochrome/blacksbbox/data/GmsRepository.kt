package com.onebitmonochrome.blacksbbox.data

import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.bean.GmsBean
import com.onebitmonochrome.blacksbbox.util.getString
import top.niunaijun.blackbox.BlackBoxCore

object GmsRepository {
    enum class Action { ENABLE, DISABLE, WIPE }

    private val core get() = BlackBoxCore.get()

    /** One engine user can contain several GMS companions. Bind them to the actual app's home user. */
    private fun memberships(): Map<Int, Map<Long, Int>> {
        val live = core.users.map { it.id }.toSet()
        val members = CloneStore.groups().flatMap { group ->
            CloneStore.groupClones(group.id).filter { it.userId in live && core.isInstalled(it.packageName, it.userId) }
                .map { it to group.id }
        }
        return members.groupBy { it.first.userId }.mapValues { (_, entries) ->
            val apps = entries.filter { !CloneStore.isHelper(it.first.packageName) }
            apps.ifEmpty { entries }.groupingBy { it.second }.eachCount()
        }
    }

    private fun profiles(group: Long, owners: Map<Int, Map<Long, Int>>) = owners.filterValues { group in it }.keys

    @Synchronized
    fun list(): List<GmsBean> {
        val owners = memberships()
        return CloneStore.groups().map { group ->
            val ids = profiles(group.id, owners)
            val counts = ids.associateWith { owners.getValue(it).getValue(group.id) }
            val total = counts.values.sum()
            val installed = counts.filterKeys(core::isInstallGms).values.sum()
            val enabled = group.gmsEnabled ?: (total > 0 && installed == total)
            GmsBean(group.id, group.name, enabled, installed, total, ids.any(core::hasGmsTraces))
        }
    }

    /** An unchanged legacy group keeps its previous installs. Mixed legacy groups are resolved explicitly in the UI. */
    private fun rememberPolicy(group: CloneStore.Group, owners: Map<Int, Map<Long, Int>>): Boolean? {
        group.gmsEnabled?.let { return it }
        val ids = profiles(group.id, owners)
        val count = ids.count(core::isInstallGms)
        if (count in 1 until ids.size) return null
        val enabled = count > 0
        CloneStore.setGroupGms(group.id, enabled)
        return enabled
    }

    @Synchronized
    fun change(groupId: Long, action: Action): String? {
        if (CloneStore.groups().none { it.id == groupId }) return getString(R.string.gms_apply_failed)
        val owners = memberships()
        val ids = profiles(groupId, owners)
        if (ids.any { owners.getValue(it).size > 1 }) return getString(R.string.gms_shared_profile)
        if (action == Action.ENABLE && !core.isSupportGms) return getString(R.string.no_gms)
        CloneStore.setGroupGms(groupId, action == Action.ENABLE)
        return applyTo(ids, action)
    }

    private fun applyTo(ids: Set<Int>, action: Action): String? {
        var failure: String? = null
        for (id in ids) {
            try {
                when (action) {
                    Action.ENABLE -> {
                        val result = core.installGms(id)
                        if (!result.success || !core.isInstallGms(id))
                            failure = result.msg?.takeIf { it.isNotBlank() } ?: getString(R.string.gms_apply_failed)
                    }
                    Action.DISABLE -> if (core.hasGmsTraces(id) && !core.uninstallGms(id))
                        failure = getString(R.string.gms_apply_failed)
                    Action.WIPE -> if (!core.wipeGms(id)) failure = getString(R.string.gms_apply_failed)
                }
            } catch (_: Exception) { failure = getString(R.string.gms_apply_failed) }
        }
        return failure
    }

    @Synchronized
    fun addClone(userId: Int, pkg: String, label: String?, groupId: Long): String? {
        val group = CloneStore.groups().let { all -> all.firstOrNull { it.id == groupId } ?: all.first() }
        val enabled = group.gmsEnabled ?: rememberPolicy(group, memberships() - userId)
        CloneStore.addClone(userId, pkg, label, group.id)
        return if (enabled == true) applyTo(setOf(userId), Action.ENABLE) else null
    }

    @Synchronized
    fun move(key: String, target: Long): String? {
        val group = CloneStore.groups().firstOrNull { it.id == target } ?: return null
        val moving = if (CloneStore.isFolderKey(key)) CloneStore.folder(key.drop(2).toLong())?.items.orEmpty() else listOf(key)
        val ids = moving.mapNotNull { CloneStore.clone(it)?.userId }.toSet()
        val before = memberships()
        val old = ids.flatMap { before[it].orEmpty().keys }.toSet()
        for (source in CloneStore.groups().filter { it.id in old }) rememberPolicy(source, before)
        val enabled = rememberPolicy(group, before)
        CloneStore.moveToGroup(key, target)
        val after = memberships()
        val affected = ids.intersect(profiles(target, after))
        if (affected.any { after.getValue(it).size > 1 }) return getString(R.string.gms_shared_profile)
        if (enabled == null) return null
        return applyTo(affected, if (enabled) Action.ENABLE else Action.DISABLE)
    }

    @Synchronized
    fun rememberBeforeRemoval(userId: Int) {
        val groups = CloneStore.groups().filter { it.gmsEnabled == null && CloneStore.groupClones(it.id).any { c -> c.userId == userId } }
        if (groups.isEmpty()) return
        val owners = memberships()
        groups.forEach { rememberPolicy(it, owners) }
    }
}
