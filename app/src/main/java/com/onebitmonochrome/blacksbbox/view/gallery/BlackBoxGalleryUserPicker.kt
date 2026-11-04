package com.onebitmonochrome.blacksbbox.view.gallery

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.data.CloneStore

object BlackBoxGalleryUserPicker {
    fun show(context: Context, onSelected: (Long) -> Unit, onCancelled: () -> Unit) {
        val groups = CloneStore.groups()
        AlertDialog.Builder(context)
            .setTitle(R.string.blackbox_gallery_choose_user)
            .setItems(groups.map { it.name }.toTypedArray()) { _, which -> onSelected(groups[which].id) }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .show()
    }
}
