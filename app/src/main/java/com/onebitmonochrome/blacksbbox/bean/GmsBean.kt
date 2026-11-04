package com.onebitmonochrome.blacksbbox.bean

data class GmsBean(
    val groupId: Long,
    val userName: String,
    val enabled: Boolean,
    val installed: Int,
    val total: Int,
    val hasGmsTraces: Boolean
) {
    val needsSync get() = total > 0 && if (enabled) installed != total else installed != 0
}
