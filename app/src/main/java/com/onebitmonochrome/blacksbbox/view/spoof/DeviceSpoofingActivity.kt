package com.onebitmonochrome.blacksbbox.view.spoof

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ActivityDeviceSpoofingBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity
import top.niunaijun.blackbox.fake.device.DevicePresets
import top.niunaijun.blackbox.fake.device.DeviceSpoofManager
import top.niunaijun.blackbox.fake.device.DeviceSpoofProfile

class DeviceSpoofingActivity : BaseActivity() {

    private val viewBinding: ActivityDeviceSpoofingBinding by inflate()

    private lateinit var shownProfile: DeviceSpoofProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.device_spoofing, true)
        initPresetDropdown()
        bindCurrentProfile()
        initActions()
    }

    private fun initPresetDropdown() {
        val presets = DevicePresets.all()
        val names = presets.map { it.marketName }
        viewBinding.devicePresetDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
        viewBinding.devicePresetDropdown.setOnItemClickListener { _, _, position, _ ->
            fillFields(DeviceSpoofManager.presetProfile(presets[position], shownProfile))
        }
    }

    private fun bindCurrentProfile() {
        fillFields(DeviceSpoofManager.getProfile(currentUserID()))
    }

    private fun initActions() {
        viewBinding.acceptChangeButton.setOnClickListener {
            val marketName = viewBinding.deviceMarketNameEdit.text?.toString().orEmpty().trim()
            val manufacturer = viewBinding.deviceManufacturerEdit.text?.toString().orEmpty().trim()
            val model = viewBinding.deviceModelEdit.text?.toString().orEmpty().trim()
            val androidId = viewBinding.androidIdEdit.text?.toString().orEmpty().trim()

            if (manufacturer.isBlank() || model.isBlank()) {
                toast(R.string.device_spoofing_fill_required)
                return@setOnClickListener
            }
            if (!DeviceSpoofManager.isValidAndroidId(androidId)) {
                toast(R.string.device_spoofing_invalid_android_id)
                return@setOnClickListener
            }

            DeviceSpoofManager.saveUserProfile(currentUserID(), marketName, manufacturer, model, androidId)
            bindCurrentProfile()
            toast(R.string.device_spoofing_saved)
        }

        viewBinding.resetDefaultsButton.setOnClickListener {
            DeviceSpoofManager.resetToDefaults(currentUserID())
            bindCurrentProfile()
            toast(R.string.device_spoofing_reset)
        }

        viewBinding.randomDeviceButton.setOnClickListener {
            fillFields(DeviceSpoofManager.randomProfile())
            toast(R.string.device_spoofing_random_loaded)
        }
    }

    private fun fillFields(profile: DeviceSpoofProfile) {
        shownProfile = profile
        viewBinding.devicePresetDropdown.setText(
            DevicePresets.all().firstOrNull {
                it.manufacturer.equals(profile.manufacturer, true) && it.model.equals(profile.model, true)
            }?.marketName.orEmpty(),
            false
        )
        viewBinding.deviceMarketNameEdit.setText(profile.marketName)
        viewBinding.deviceManufacturerEdit.setText(profile.manufacturer)
        viewBinding.deviceModelEdit.setText(profile.model)
        viewBinding.androidIdEdit.setText(profile.androidId)
    }

    companion object {
        fun start(context: Context, userId: Int) {
            val intent = Intent(context, DeviceSpoofingActivity::class.java)
            intent.putExtra("userID", userId)
            context.startActivity(intent)
        }
    }
}
