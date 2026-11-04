package com.onebitmonochrome.blacksbbox.view.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.ActivityNotFoundException
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.isVisible
import com.onebitmonochrome.blacksbbox.data.CloneStore
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ActivityBlackboxGalleryBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.media.BlackBoxMediaStore

class BlackBoxGalleryActivity : LoadingActivity() {

    private val viewBinding: ActivityBlackboxGalleryBinding by inflate()
    private val adapter = BlackBoxGalleryAdapter { entry ->
        startActivity(BlackBoxGalleryPreviewActivity.intentFor(this, groupId, guestUser, entry.id, manageGrants))
    }
    private val groupId by lazy { resolveGroup(intent, "userID") }
    private val manageGrants by lazy { intent.getBooleanExtra(EXTRA_GRANTS, false) }
    private val grants by lazy { ViewModelProvider(this)[SelectedPhotosViewModel::class.java] }
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (manageGrants && uris.isNotEmpty()) grants.add(applicationContext, groupId, uris)
    }
    private val guestUser by lazy {
        val members = CloneStore.groupClones(groupId)
        members.firstOrNull { it.userId == intent.getIntExtra(EXTRA_GUEST, -1) }?.userId ?: members.firstOrNull()?.userId
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (groupId <= 0) { finish(); return }
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, if (manageGrants) R.string.album_granted_images else R.string.blackbox_gallery_title, true)
        viewBinding.toolbarLayout.toolbar.subtitle = CloneStore.groups().firstOrNull { it.id == groupId }?.name
        viewBinding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        viewBinding.recyclerView.adapter = adapter
        if (manageGrants) {
            viewBinding.selectImagesButton.isVisible = true
            viewBinding.refreshButton.isVisible = false
            viewBinding.emptyView.setText(R.string.album_grants_empty)
            viewBinding.selectImagesButton.setOnClickListener {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: ActivityNotFoundException) {
                    toast(R.string.album_picker_unavailable)
                }
            }
            grants.saving.observe(this) { busy ->
                viewBinding.selectImagesButton.isEnabled = !busy
                if (busy) showLoading() else hideLoading()
            }
            grants.result.observe(this) { counts ->
                if (counts != null) {
                    toast(getString(R.string.album_grant_result, counts.first, counts.second))
                    grants.result.value = null
                    loadMedia(forceRescan = false)
                }
            }
        }
        viewBinding.refreshButton.setOnClickListener { loadMedia(forceRescan = true) }
        viewBinding.openGuestGalleryButton.isVisible = !manageGrants && guestUser != null
        viewBinding.openGuestGalleryButton.setOnClickListener {
            val userId = guestUser ?: return@setOnClickListener
            if (!BlackBoxGuestGalleryInstaller.launch(this, userId)) {
                toast(R.string.blackbox_gallery_open_guest_failed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) loadMedia(forceRescan = false)
    }

    private fun loadMedia(forceRescan: Boolean) {
        if (forceRescan) {
            showLoading()
        }
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val owner = CloneStore.galleryOwner(groupId)
                if (forceRescan) {
                    BlackBoxMediaStore.rescanUser(owner)
                }
                val media = BlackBoxMediaStore.listMediaEntries(owner)
                if (manageGrants) media.filter(BlackBoxMediaStore::isSelectedImage) else media
            }
            if (forceRescan) {
                hideLoading()
            }
            adapter.submit(entries)
            viewBinding.emptyView.visibility =
                if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    companion object {
        const val EXTRA_GROUP = "gallery_group"
        const val EXTRA_GRANTS = "manage_selected_photos"
        const val EXTRA_GUEST = "gallery_guest"

        internal fun resolveGroup(intent: Intent, legacyKey: String): Long {
            val id = if (intent.hasExtra(EXTRA_GROUP)) intent.getLongExtra(EXTRA_GROUP, -1L)
                     else CloneStore.galleryGroup(intent.getIntExtra(legacyKey, -1), null)
            return CloneStore.groups().firstOrNull { it.id == id }?.id ?: -1L
        }

        fun start(context: Context, groupId: Long, guestUser: Int? = null, manageGrants: Boolean = false) {
            context.startActivity(
                Intent(context, BlackBoxGalleryActivity::class.java)
                    .putExtra(EXTRA_GROUP, groupId).putExtra(EXTRA_GUEST, guestUser ?: -1).putExtra(EXTRA_GRANTS, manageGrants)
            )
        }
    }
}
