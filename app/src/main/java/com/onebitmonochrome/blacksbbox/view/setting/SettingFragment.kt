package com.onebitmonochrome.blacksbbox.view.setting

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.preference.ListPreference
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.onebitmonochrome.blacksbbox.databinding.ItemCreditBinding
import com.onebitmonochrome.blacksbbox.databinding.ItemCreditHeaderBinding
import com.onebitmonochrome.blacksbbox.databinding.SheetCreditsBinding
import com.onebitmonochrome.blacksbbox.util.LanguageManager
import com.onebitmonochrome.blacksbbox.util.ThemeManager
import com.onebitmonochrome.blacksbbox.util.UpdateChecker
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.FileLog
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.gms.GmsManagerActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingFragment : PreferenceFragmentCompat() {

    companion object {
        private const val CONTACT_EMAIL = "support@lqstudio.uk"
    }

    private val vpnPermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val vpnPref =
                        findPreference<SwitchPreferenceCompat>("use_vpn_network")
                                ?: return@registerForActivityResult
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(true)
                    vpnPref.isChecked = true
                    toast(R.string.restart_module)
                } else {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                    vpnPref.isChecked = false
                    toast(R.string.vpn_permission_denied)
                }
            }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        initTheme()
        initLanguage()

        initHostSigningFallback()
        initInstantFreezeButton()

        if (Build.VERSION.SDK_INT >= 34) {
            val vpnPref = findPreference<SwitchPreferenceCompat>("use_vpn_network")
            if (vpnPref != null) {
                try {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                } catch (_: Exception) {
                }
                vpnPref.isChecked = false
                vpnPref.isEnabled = false
                vpnPref.summary = getString(R.string.vpn_not_supported_android14)
            }
        }

        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }

        initSendLogs()
        initAbout()
    }

    private fun initHostSigningFallback() {
        val pref = findPreference<SwitchPreferenceCompat>("host_signing_fallback") ?: return

        // Ensure UI reflects persisted value.
        try {
            val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
            pref.isChecked = sp.getBoolean(pref.key, false)
        } catch (_: Exception) {
        }

        var programmaticChange = false
        pref.setOnPreferenceChangeListener { _, newValue ->
            if (programmaticChange) {
                return@setOnPreferenceChangeListener true
            }

            val enable = (newValue == true)
            if (!enable) {
                toast(R.string.restart_module)
                return@setOnPreferenceChangeListener true
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.host_signing_fallback_dialog_title)
                .setMessage(R.string.host_signing_fallback_dialog_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    programmaticChange = true
                    try {
                        pref.isChecked = true
                    } finally {
                        programmaticChange = false
                    }
                    toast(R.string.restart_module)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    programmaticChange = true
                    try {
                        pref.isChecked = false
                    } finally {
                        programmaticChange = false
                    }
                }
                .show()

            // Cancel immediate enabling; we'll apply it if the user confirms.
            false
        }
    }

    private fun initInstantFreezeButton() {
        val pref = findPreference<SwitchPreferenceCompat>("freeze_app_instantly") ?: return
        pref.isChecked = AppFreezeManager.isInstantFreezeButtonEnabled()

        var programmaticChange = false
        pref.setOnPreferenceChangeListener { _, newValue ->
            if (programmaticChange) {
                return@setOnPreferenceChangeListener true
            }

            val enable = (newValue == true)
            if (!enable) {
                AppFreezeManager.setInstantFreezeButtonEnabled(false)
                return@setOnPreferenceChangeListener true
            }

            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.freeze_app_instantly_dialog_title)
                    .setMessage(R.string.freeze_app_instantly_dialog_message)
                    .setPositiveButton(R.string.enable) { _, _ ->
                        AppFreezeManager.setInstantFreezeButtonEnabled(true)
                        programmaticChange = true
                        try {
                            pref.isChecked = true
                        } finally {
                            programmaticChange = false
                        }
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        programmaticChange = true
                        try {
                            pref.isChecked = false
                        } finally {
                            programmaticChange = false
                        }
                    }
                    .show()

            false
        }
    }

    private fun initTheme() {
        val themePref = findPreference<ListPreference>(ThemeManager.KEY) ?: return
        // Shows the value carried over from the old dark mode switch.
        themePref.value = ThemeManager.current(requireContext())
        themePref.setOnPreferenceChangeListener { _, newValue ->
            ThemeManager.apply(newValue as String)
            true
        }
    }

    private fun initLanguage() {
        val languagePref = findPreference<ListPreference>(LanguageManager.KEY) ?: return
        languagePref.setOnPreferenceChangeListener { _, newValue ->
            LanguageManager.set(requireContext(), newValue as String)
            // Redraws this screen; screens behind it follow when they come back (BaseActivity).
            requireActivity().recreate()
            true
        }
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                        } catch (_: Exception) {
                        }
                        toast(R.string.vpn_not_supported_android14)
                        return@setOnPreferenceChangeListener false
                    }

                    val enableVpn = tmpHide
                    if (!enableVpn) {
                        AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                        toast(R.string.restart_module)
                        return@setOnPreferenceChangeListener true
                    }

                    val vpnIntent = VpnService.prepare(requireContext())
                    if (vpnIntent == null) {
                        AppManager.mBlackBoxLoader.invalidUseVpnNetwork(true)
                        toast(R.string.restart_module)
                        return@setOnPreferenceChangeListener true
                    }

                    vpnPermissionResult.launch(vpnIntent)
                    return@setOnPreferenceChangeListener false
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }
    private fun initSendLogs() {
        val sendLogsPreference: Preference? = findPreference("send_logs")
        sendLogsPreference?.setOnPreferenceClickListener {
            it.isEnabled = false
            toast(R.string.share_logs_preparing)
            Thread {
                try {
                    val logFile = buildShareableLogFile()
                    activity?.runOnUiThread {
                        sendLogsPreference.isEnabled = true
                        shareLogFile(logFile)
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        sendLogsPreference.isEnabled = true
                        toast(R.string.share_logs_failed)
                    }
                }
            }.start()
            true
        }
    }

    private fun initAbout() {
        findPreference<Preference>("about_authors")?.isSelectable = false

        findPreference<Preference>("about_upstream")?.setOnPreferenceClickListener {
            showCredits()
            true
        }

        findPreference<Preference>("check_update")?.apply {
            summary = getString(R.string.check_update_summary, UpdateChecker.currentVersion(requireContext()))
            setOnPreferenceClickListener {
                UpdateChecker.check(requireContext())
                true
            }
        }

        findPreference<Preference>("footer_contact")?.setOnPreferenceClickListener {
            openLink("mailto:$CONTACT_EMAIL")
            true
        }
    }

    // Upstream projects this build is based on, grouped by what they provide.
    private fun showCredits() {
        val sections = listOf(
            R.string.about_main_base_title to listOf(
                Triple(R.string.about_owner_title, R.string.about_owner_summary, "https://github.com/Black00Z"),
                Triple(R.string.about_newblackbox_title, R.string.about_link_summary, "https://github.com/ALEX5402/NewBlackbox")
            ),
            R.string.about_framework_base_title to listOf(
                Triple(R.string.about_virtualapp_title, R.string.about_link_summary, "https://github.com/asLody/VirtualApp"),
                Triple(R.string.about_virtualapk_title, R.string.about_link_summary, "https://github.com/didi/VirtualAPK")
            ),
            R.string.about_native_hooking_title to listOf(
                Triple(R.string.about_dobby_title, R.string.about_link_summary, "https://github.com/jmpews/Dobby"),
                Triple(R.string.about_xdl_title, R.string.about_link_summary, "https://github.com/hexhacking/xDL")
            ),
            R.string.about_reflection_title to listOf(
                Triple(R.string.about_blackreflection_title, R.string.about_link_summary, "https://github.com/CodingGay/BlackReflection"),
                Triple(R.string.about_freereflection_title, R.string.about_link_summary, "https://github.com/tiann/FreeReflection")
            ),
            R.string.about_ai_note_title to listOf(
                Triple(R.string.about_ai_note_summary, 0, null)
            )
        )

        val dialog = BottomSheetDialog(requireContext())
        val sheet = SheetCreditsBinding.inflate(layoutInflater)
        val rows = mutableListOf<View>()
        for ((header, entries) in sections) {
            val headerView = ItemCreditHeaderBinding.inflate(layoutInflater, sheet.creditsList, false).root
            headerView.setText(header)
            sheet.creditsList.addView(headerView)
            rows += headerView
            for ((title, summary, url) in entries) {
                val row = ItemCreditBinding.inflate(layoutInflater, sheet.creditsList, false)
                row.creditTitle.setText(title)
                if (summary != 0) row.creditSummary.setText(summary) else row.creditSummary.visibility = View.GONE
                if (url != null) {
                    row.root.setOnClickListener { openLink(url) }
                } else {
                    row.root.isClickable = false
                    row.root.background = null
                }
                sheet.creditsList.addView(row.root)
                rows += row.root
            }
        }
        dialog.setContentView(sheet.root)
        dialog.setOnShowListener {
            rows.forEachIndexed { i, view ->
                view.alpha = 0f
                view.translationY = 24f
                view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60L + i * 25L).setDuration(260)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        dialog.show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(R.string.share_logs_failed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_slide_in)
    }

    private fun buildShareableLogFile(): File {
        val context = requireContext()
        val outDir = File(context.cacheDir, "shared_logs")
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        val fileName = "mikan_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { fos ->
            val header = buildString {
                append("Caption: Manual Log Share from Settings\n\n")
                append(getDeviceInfoString())
                append("\n")
            }
            fos.write(header.toByteArray(StandardCharsets.UTF_8))

            // Engine logs mirrored to files by FileLog; logcat alone loses them on ROMs
            // that filter app logs below ERROR.
            val engineLogs = FileLog.getLogDir(context).listFiles()
                ?.sortedWith(compareBy({ it.name.removeSuffix(".old") }, { !it.name.endsWith(".old") }))
                .orEmpty()
            for (logFile in engineLogs) {
                fos.write("\n--- ENGINE LOG: ${logFile.name} ---\n".toByteArray(StandardCharsets.UTF_8))
                logFile.inputStream().use { it.copyTo(fos) }
            }
            fos.write("\n--- LOGCAT (system) ---\n".toByteArray(StandardCharsets.UTF_8))

            val process = Runtime.getRuntime().exec("logcat -d -v threadtime")
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    fos.write(buffer, 0, len)
                }
            }
        }
        return outFile
    }

    private fun shareLogFile(file: File) {
        val context = requireContext()
        val authority = context.packageName + ".fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mikan debug logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_logs_chooser_title)))
    }

    private fun getDeviceInfoString(): String {
        return buildString {
            append("DEVICE INFORMATION\n")
            append("------------------\n")
            append("Android Version: ").append(Build.VERSION.RELEASE).append("\n")
            append("SDK Level: ").append(Build.VERSION.SDK_INT).append("\n")
            append("Build ID: ").append(Build.ID).append("\n")
            append("Build Display: ").append(Build.DISPLAY).append("\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                append("Security Patch: ").append(Build.VERSION.SECURITY_PATCH).append("\n")
            }
            append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
            append("Brand: ").append(Build.BRAND).append("\n")
            append("Model: ").append(Build.MODEL).append("\n")
            append("Device: ").append(Build.DEVICE).append("\n")
            append("Product: ").append(Build.PRODUCT).append("\n")
            append("Board: ").append(Build.BOARD).append("\n")
            append("Hardware: ").append(Build.HARDWARE).append("\n")
            append("Supported ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append("\n")
        }
    }
}
