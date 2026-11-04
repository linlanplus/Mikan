package com.onebitmonochrome.blacksbbox.view.gallery

import android.content.Intent
import android.net.Uri
import com.onebitmonochrome.blacksbbox.data.CloneStore
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ActivityBlackboxGalleryImportBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.media.BlackBoxMediaProvider
import top.niunaijun.blackbox.media.BlackBoxMediaStore

class BlackBoxGalleryImportActivity : LoadingActivity() {

    private val viewBinding: ActivityBlackboxGalleryImportBinding by inflate()
    private var hasStartedImport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) {
            toast(R.string.blackbox_gallery_import_empty)
            finish()
            return
        }

        chooseUserAndImport(uris)
    }

    private fun chooseUserAndImport(uris: List<Uri>) {
        if (hasStartedImport) {
            return
        }
        BlackBoxGalleryUserPicker.show(
            context = this,
            onSelected = { groupId ->
                hasStartedImport = true
                importMedia(groupId, uris)
            },
            onCancelled = { finish() }
        )
    }

    private fun importMedia(groupId: Long, uris: List<Uri>) {
        showLoading()
        lifecycleScope.launch {
            val importedCount = withContext(Dispatchers.IO) {
                val owner = CloneStore.galleryOwner(groupId)
                var successCount = 0
                uris.forEach { uri ->
                    runCatching {
                        BlackBoxMediaStore.importMedia(this@BlackBoxGalleryImportActivity, owner, uri, intent.type)
                    }.onSuccess {
                        successCount += 1
                    }
                }
                if (successCount > 0) {
                    BlackBoxMediaProvider.notifyGalleries(this@BlackBoxGalleryImportActivity)
                }
                successCount
            }
            hideLoading()
            if (importedCount <= 0) {
                toast(R.string.blackbox_gallery_import_failed)
                finish()
                return@launch
            }
            toast(getString(R.string.blackbox_gallery_imported_count, importedCount))
            BlackBoxGalleryActivity.start(this@BlackBoxGalleryImportActivity, groupId)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                          else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            streams?.let(result::addAll)
        } else {
            val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                         else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            stream?.let(result::add)
        }
        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(result::add)
            }
        }
        return result.toList()
    }
}
