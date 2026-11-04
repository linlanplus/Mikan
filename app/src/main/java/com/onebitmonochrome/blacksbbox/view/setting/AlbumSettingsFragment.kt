package com.onebitmonochrome.blacksbbox.view.setting

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.gallery.BlackBoxGalleryActivity
import com.onebitmonochrome.blacksbbox.view.gallery.BlackBoxGalleryUserPicker
import top.niunaijun.blackbox.media.SystemGallery

class AlbumSettingsFragment : PreferenceFragmentCompat() {
    private val galleryPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val allowed = SystemGallery.hasPermission(requireContext())
        SystemGallery.setEnabled(requireContext(), allowed)
        findPreference<SwitchPreferenceCompat>("system_gallery")?.isChecked = allowed
        if (!allowed) toast(R.string.system_gallery_denied)
    }

    private fun initGalleryAccess() {
        val pref = findPreference<SwitchPreferenceCompat>("system_gallery") ?: return
        pref.isChecked = SystemGallery.isEnabled(requireContext())
        pref.setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.system_gallery_title)
                    .setMessage(R.string.system_gallery_warning)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.enable) { _, _ ->
                        if (SystemGallery.hasPermission(requireContext())) {
                            SystemGallery.setEnabled(requireContext(), true)
                            pref.isChecked = true
                        } else {
                            galleryPermission.launch(SystemGallery.permissions(requireContext()))
                        }
                    }.show()
                false
            } else {
                SystemGallery.setEnabled(requireContext(), false)
                true
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.album_management, rootKey)
        initGalleryAccess()
        findPreference<Preference>("selected_photos")?.setOnPreferenceClickListener {
            if (SystemGallery.isEnabled(requireContext())) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.album_partial_mode)
                    .setMessage(R.string.album_switch_partial_hint)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.album_switch_partial) { _, _ ->
                        SystemGallery.setEnabled(requireContext(), false)
                        findPreference<SwitchPreferenceCompat>("system_gallery")?.isChecked = false
                        chooseUser()
                    }.show()
            } else chooseUser()
            true
        }
    }

    private fun chooseUser() {
        BlackBoxGalleryUserPicker.show(requireContext(), { group ->
            BlackBoxGalleryActivity.start(requireContext(), group, manageGrants = true)
        }, {})
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as SettingActivity).setPageTitle(getString(R.string.album_management))
        findPreference<SwitchPreferenceCompat>("system_gallery")?.isChecked = SystemGallery.isEnabled(requireContext())
    }
}
