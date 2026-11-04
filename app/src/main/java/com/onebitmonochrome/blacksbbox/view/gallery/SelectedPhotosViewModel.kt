package com.onebitmonochrome.blacksbbox.view.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onebitmonochrome.blacksbbox.data.CloneStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.media.BlackBoxMediaProvider
import top.niunaijun.blackbox.media.BlackBoxMediaStore

class SelectedPhotosViewModel : ViewModel() {
    val saving = MutableLiveData(false)
    val result = MutableLiveData<Pair<Int, Int>?>()

    fun add(context: Context, group: Long, images: List<Uri>) {
        if (saving.value == true) return
        saving.value = true
        viewModelScope.launch {
            val selected = images.distinct()
            val count = withContext(Dispatchers.IO) {
                try {
                    if (CloneStore.groups().none { it.id == group }) return@withContext 0
                    val owner = CloneStore.galleryOwner(group)
                    selected.count { uri ->
                        try {
                            BlackBoxMediaStore.grantSelectedImage(context, owner, uri)
                            true
                        } catch (_: Exception) { false }
                    }
                } catch (_: Exception) { 0 }
            }
            if (count > 0) BlackBoxMediaProvider.notifyGalleries(context)
            saving.value = false
            result.value = count to selected.size
        }
    }
}
