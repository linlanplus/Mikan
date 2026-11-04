package com.onebitmonochrome.blacksbbox.view.gms

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.bean.GmsBean
import com.onebitmonochrome.blacksbbox.data.GmsRepository
import com.onebitmonochrome.blacksbbox.util.getString
import com.onebitmonochrome.blacksbbox.view.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GmsViewModel(private val repo: GmsRepository) : BaseViewModel() {
    val rows = MutableLiveData<List<GmsBean>>()
    val busy = MutableLiveData(false)
    val message = MutableLiveData<String?>()

    fun refresh() = update(null, null)
    fun change(group: Long, action: GmsRepository.Action) = update(group, action)

    private fun update(group: Long?, action: GmsRepository.Action?) {
        if (busy.value == true) return
        busy.value = true
        viewModelScope.launch {
            try {
                val (items, error) = withContext(Dispatchers.IO) {
                    val issue = if (group != null && action != null) repo.change(group, action) else null
                    repo.list() to issue
                }
                rows.value = items
                if (group != null) message.value = error ?: getString(
                    if (action == GmsRepository.Action.ENABLE) R.string.install_success else R.string.uninstall_success)
            } catch (_: Exception) {
                message.value = getString(R.string.gms_apply_failed)
            } finally { busy.value = false }
        }
    }
}
