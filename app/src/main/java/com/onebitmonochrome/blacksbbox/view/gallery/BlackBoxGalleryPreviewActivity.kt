package com.onebitmonochrome.blacksbbox.view.gallery

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import androidx.core.view.isVisible
import com.onebitmonochrome.blacksbbox.data.CloneStore
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ActivityBlackboxGalleryPreviewBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.media.BlackBoxMediaEntry
import top.niunaijun.blackbox.media.BlackBoxMediaStore

class BlackBoxGalleryPreviewActivity : LoadingActivity() {

    private val viewBinding: ActivityBlackboxGalleryPreviewBinding by inflate()
    private val groupId by lazy { BlackBoxGalleryActivity.resolveGroup(intent, "extra_user_id") }
    private val guestUser by lazy { intent.getIntExtra(BlackBoxGalleryActivity.EXTRA_GUEST, intent.getIntExtra("extra_user_id", -1)) }
    private val manageGrants by lazy { intent.getBooleanExtra(BlackBoxGalleryActivity.EXTRA_GRANTS, false) }
    private val mediaId by lazy { intent.getLongExtra(EXTRA_MEDIA_ID, -1L) }
    private var currentEntry: BlackBoxMediaEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (groupId <= 0) { finish(); return }
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.blackbox_gallery_title, true)
        initActions()
        loadEntry()
    }

    private fun initActions() {
        viewBinding.shareButton.isVisible = !manageGrants
        if (manageGrants) viewBinding.deleteButton.setText(R.string.album_revoke)
        viewBinding.openGuestGalleryButton.isVisible = !manageGrants && guestUser >= 0
        viewBinding.openGuestGalleryButton.setOnClickListener {
            if (!BlackBoxGuestGalleryInstaller.launch(this, guestUser)) {
                toast(R.string.blackbox_gallery_open_guest_failed)
            }
        }
        viewBinding.shareButton.setOnClickListener {
            shareInsideBlackBox()
        }
        viewBinding.deleteButton.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            showLoading()
            lifecycleScope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    val owner = CloneStore.galleryOwner(groupId)
                    if (BlackBoxMediaStore.isSelectedImage(entry)) BlackBoxMediaStore.revokeSelectedImage(owner, entry.id)
                    else BlackBoxMediaStore.delete(owner, entry.privateUri, null, null) > 0
                }
                hideLoading()
                if (deleted) {
                    top.niunaijun.blackbox.media.BlackBoxMediaProvider.notifyGalleries(this@BlackBoxGalleryPreviewActivity)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast(R.string.blackbox_gallery_delete_failed)
                }
            }
        }
    }

    private fun loadEntry() {
        showLoading()
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                BlackBoxMediaStore.getEntry(CloneStore.galleryOwner(groupId), mediaId)
            }
            hideLoading()
            if (entry == null || (manageGrants && !BlackBoxMediaStore.isSelectedImage(entry))) {
                toast(R.string.blackbox_gallery_item_missing)
                finish()
                return@launch
            }
            currentEntry = entry
            bindEntry(entry)
        }
    }

    private fun bindEntry(entry: BlackBoxMediaEntry) {
        viewBinding.toolbarLayout.toolbar.subtitle = CloneStore.groups().firstOrNull { it.id == groupId }?.name
        if (entry.isVideo) {
            viewBinding.imageView.visibility = android.view.View.GONE
            viewBinding.videoView.visibility = android.view.View.VISIBLE
            viewBinding.videoView.setMediaController(MediaController(this).also { controller ->
                controller.setAnchorView(viewBinding.videoView)
            })
            viewBinding.videoView.setVideoURI(Uri.fromFile(File(entry.filePath)))
            viewBinding.videoView.start()
        } else {
            viewBinding.videoView.stopPlayback()
            viewBinding.videoView.visibility = android.view.View.GONE
            viewBinding.imageView.visibility = android.view.View.VISIBLE
            viewBinding.imageView.setImageURI(Uri.fromFile(File(entry.filePath)))
        }
    }

    private fun shareInsideBlackBox() {
        val entry = currentEntry ?: return
        val mimeType = entry.mimeType ?: if (entry.isVideo) "video/*" else "image/*"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, entry.publicUri)
            clipData = ClipData.newUri(contentResolver, entry.displayName ?: "media", entry.publicUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val targets = findShareTargets(shareIntent, mimeType)

        if (targets.isEmpty()) {
            toast(R.string.blackbox_gallery_share_no_targets)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.blackbox_gallery_share_to_app)
            .setItems(targets.map { it.label }.toTypedArray()) { _, which ->
                startShareTarget(shareIntent, targets[which])
            }
            .show()
    }

    private data class Target(val userId: Int, val info: ResolveInfo, val label: String)

    private fun findShareTargets(baseIntent: Intent, mimeType: String): List<Target> {
        val candidateTypes = linkedSetOf(
            mimeType,
            if (mimeType.startsWith("video/")) "video/*" else "image/*",
            "*/*"
        )
        val targets = linkedMapOf<String, Target>()
        for (clone in CloneStore.groupClones(groupId)) {
            for (type in candidateTypes) {
                val intent = Intent(baseIntent).setPackage(clone.packageName).setType(type)
                for (result in BlackBoxCore.getBPackageManager().queryIntentActivities(intent, 0, type, clone.userId).orEmpty()) {
                    val info = result.activityInfo ?: continue
                    if (info.packageName == clone.packageName) {
                        targets["${clone.key}/${info.name}"] = Target(clone.userId, result, clone.label ?: resolveLabel(result))
                    }
                }
            }
        }
        return targets.values.sortedBy { it.label.lowercase() }
    }

    private fun startShareTarget(baseIntent: Intent, target: Target) {
        val info = target.info.activityInfo ?: return
        val intent = Intent(baseIntent).apply {
            setClassName(info.packageName, info.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            BlackBoxCore.get().startActivity(intent, target.userId)
        } catch (e: Exception) {
            toast(R.string.blackbox_gallery_share_failed)
        }
    }

    private fun resolveLabel(resolveInfo: ResolveInfo): String {
        return try {
            resolveInfo.loadLabel(BlackBoxCore.getPackageManager())?.toString()
        } catch (e: Exception) {
            null
        } ?: resolveInfo.activityInfo?.packageName ?: getString(R.string.blackbox_gallery_share_to_app)
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "extra_media_id"

        fun intentFor(context: Context, groupId: Long, guestUser: Int?, mediaId: Long, manageGrants: Boolean): Intent {
            return Intent(context, BlackBoxGalleryPreviewActivity::class.java)
                .putExtra(BlackBoxGalleryActivity.EXTRA_GROUP, groupId)
                .putExtra(BlackBoxGalleryActivity.EXTRA_GUEST, guestUser ?: -1)
                .putExtra(BlackBoxGalleryActivity.EXTRA_GRANTS, manageGrants)
                .putExtra(EXTRA_MEDIA_ID, mediaId)
        }
    }
}
