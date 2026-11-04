package com.onebitmonochrome.blacksbbox.view.setting

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.fake.network.ProxyConfig
import java.util.Locale

class ProxyManagerFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(preferenceManager.context)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sourceId = arguments?.getString(SettingActivity.PROXY_SOURCE)
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        try {
            val sources = ProxyConfig.sources()
            if (sourceId == null) {
                (requireActivity() as SettingActivity).setProxyPageTitle(getString(R.string.proxy_mine))
                screen.action(getString(R.string.proxy_new)) { editProxySource(null, ::refresh) }
                if (sources.isEmpty()) {
                    requireActivity().finish()
                    return
                }
                for (source in sources) {
                    screen.action(source.label, getString(R.string.proxy_node_count, source.count)) {
                        SettingActivity.openProxies(requireContext(), source.id)
                    }
                }
            } else {
                val source = sources.find { it.id == sourceId }
                if (source == null) { requireActivity().finish(); return }
                (requireActivity() as SettingActivity).setProxyPageTitle(source.label)
                screen.action(getString(R.string.proxy_edit)) { editProxySource(source, ::refresh) }
                screen.action(getString(R.string.proxy_delete)) { confirmDelete(source, null) }
                val group = PreferenceCategory(ctx).apply { setTitle(R.string.proxy_nodes) }
                screen.addPreference(group)
                for (node in ProxyConfig.nodes(source.id)) {
                    group.action(nodeTitle(node), node.protocol.uppercase(Locale.ROOT)) {
                        confirmDelete(source, node)
                    }
                }
            }
        } catch (e: Exception) {
            toast(R.string.proxy_save_failed)
        }
    }

    private fun PreferenceGroup.action(label: String, description: String? = null, click: () -> Unit) {
        addPreference(Preference(preferenceManager.context).apply {
            title = label
            summary = description
            isIconSpaceReserved = false
            setOnPreferenceClickListener { click(); true }
        })
    }

    private fun confirmDelete(source: ProxyConfig.Source, node: ProxyConfig.Choice?) {
        val target = node?.let { nodeTitle(it) } ?: source.label
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.proxy_delete_target, target))
            .setMessage(R.string.proxy_delete_hint)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.proxy_delete_action) { _, _ ->
                runCatching { ProxyConfig.remove(source.id, node?.id) }
                    .onSuccess { refresh() }.onFailure { toast(R.string.proxy_save_failed) }
            }.show()
    }
}

private val numberedNodeName = Regex("""^(?:代理|proxy)\s*\d+\s*[-－–—]""", RegexOption.IGNORE_CASE)

internal fun androidx.fragment.app.Fragment.nodeTitle(node: ProxyConfig.Choice): String =
    if (node.number == 0 || numberedNodeName.containsMatchIn(node.name)) node.name else getString(R.string.proxy_numbered_node, node.number, node.name)

internal fun PreferenceFragmentCompat.editProxySource(source: ProxyConfig.Source?, saved: () -> Unit) {
    val ctx = requireContext()
    val name = EditText(ctx).apply {
        setSingleLine()
        setHint(R.string.proxy_name_hint)
        filters = arrayOf(InputFilter.LengthFilter(100))
        setText(source?.label.orEmpty())
        setSelectAllOnFocus(true)
    }
    val link = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        maxLines = 4
        setHint(if (source == null) R.string.proxy_url_hint else R.string.proxy_replace_hint)
        isSaveEnabled = false
    }
    val fields = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        addView(name)
        addView(link)
    }
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(if (source == null) R.string.proxy_new else R.string.proxy_edit)
        .setView(fields)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(if (source == null) R.string.proxy_import_button else R.string.done, null)
        .create()
    dialog.setOnShowListener {
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val input = link.text.toString().trim()
            val displayName = name.text.toString().trim()
            if (input.isEmpty() && (source == null || displayName.isEmpty())) {
                (if (source == null) link else name).error = getString(
                    if (source == null) R.string.proxy_url_hint else R.string.proxy_name_hint)
                return@setOnClickListener
            }
            submit.isEnabled = false
            name.isEnabled = false
            link.isEnabled = false
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dialog.setTitle(R.string.proxy_import_busy)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (source != null && input.isEmpty()) {
                            ProxyConfig.renameSource(source.id, displayName)
                            null
                        } else ProxyConfig.importSource(source?.id, displayName, input)
                    }
                    dialog.dismiss()
                    if (result != null) toast(getString(R.string.proxy_import_success, result.imported, result.skipped))
                    saved()
                } catch (e: CancellationException) {
                    dialog.dismiss()
                    throw e
                } catch (e: Exception) {
                    dialog.setTitle(if (source == null) R.string.proxy_new else R.string.proxy_edit)
                    link.isEnabled = true
                    name.isEnabled = true
                    submit.isEnabled = true
                    dialog.setCancelable(true)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    link.error = getString(R.string.proxy_import_failed)
                }
            }
        }
    }
    dialog.show()
}
