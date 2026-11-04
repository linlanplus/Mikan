package com.onebitmonochrome.blacksbbox.view.home

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.data.CloneStore
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.setting.SettingActivity
import com.onebitmonochrome.blacksbbox.view.setting.nodeTitle
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.network.ProxyConfig

private data class ProxyRow(val title: String, val id: String? = null, val used: Boolean = false)

internal fun HomeFragment.showProxyPicker(clone: CloneStore.Clone) {
    val ctx = requireContext()
    try {
        val nodes = ProxyConfig.choices(clone.userId, clone.packageName)
        val current = ProxyConfig.selectedId(clone.userId, clone.packageName)
        val latency = mutableMapOf<String, Int>()
        fun displayRows(sortByLatency: Boolean): List<ProxyRow> {
            val rows = mutableListOf(ProxyRow(getString(R.string.proxy_direct), ""))
            if (current == "legacy") rows.add(ProxyRow(getString(R.string.proxy_legacy), current))
            for (used in listOf(false, true)) {
                var group = nodes.filter { it.used == used }
                if (sortByLatency) group = group.sortedBy {
                    when (val ms = latency[it.id]) {
                        null, -1 -> Int.MAX_VALUE
                        -2 -> Int.MAX_VALUE - 1
                        else -> ms
                    }
                }
                if (used && group.isNotEmpty()) rows.add(ProxyRow(getString(R.string.proxy_used_group)))
                var lastSource: String? = null
                for (node in group) {
                    if (lastSource != node.sourceId) {
                        rows.add(ProxyRow(node.sourceLabel.ifEmpty { getString(R.string.proxy_legacy) }))
                        lastSource = node.sourceId
                    }
                    rows.add(ProxyRow(nodeTitle(node), node.id, used))
                }
            }
            return rows
        }
        val adapter = ProxyRows(displayRows(false), current, latency)
        val builder = AlertDialog.Builder(ctx).setTitle(R.string.clone_proxy)
            .setNeutralButton(R.string.proxy_manage) { _, _ ->
                if (ProxyConfig.sources().isEmpty()) SettingActivity.start(ctx)
                else SettingActivity.openProxies(ctx)
            }
            .setNegativeButton(R.string.cancel, null)
        if (adapter.count == 1) {
            builder.setMessage(R.string.proxy_no_nodes).show()
            return
        }
        val header = LayoutInflater.from(ctx).inflate(R.layout.dialog_proxy_title, null)
        val test = header.findViewById<Button>(R.id.proxy_test)
        test.isEnabled = nodes.isNotEmpty()
        val dialog = builder.setCustomTitle(header).setAdapter(adapter, null).create()
        var testJob: Job? = null
        test.setOnClickListener {
            test.isEnabled = false
            test.setText(R.string.proxy_testing)
            latency.clear()
            adapter.testing = true
            adapter.notifyDataSetChanged()
            testJob = viewLifecycleOwner.lifecycleScope.launch {
                val queue = nodes.iterator()
                coroutineScope {
                    repeat(minOf(3, nodes.size)) {
                        launch {
                            while (queue.hasNext()) {
                                val node = queue.next()
                                latency[node.id] = try {
                                    runInterruptible(Dispatchers.IO) { node.measureLatency() }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    -2
                                }
                                adapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
                adapter.testing = false
                adapter.rows = displayRows(true)
                adapter.notifyDataSetChanged()
                dialog.listView.setSelection(0)
                test.setText(R.string.proxy_latency)
                test.isEnabled = true
            }
        }
        dialog.setOnDismissListener { testJob?.cancel() }
        fun select(id: String, confirmed: Boolean): Boolean {
            if (id != current) {
                try {
                    if (!ProxyConfig.choose(clone.userId, clone.packageName, id, confirmed)) return false
                    BlackBoxCore.get().stopPackage(clone.packageName, clone.userId)
                    toast(R.string.clone_proxy_saved)
                } catch (e: Exception) {
                    toast(R.string.proxy_save_failed)
                }
            }
            dialog.dismiss()
            return true
        }
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val row = adapter.getItem(position)
                val id = row.id ?: return@setOnItemClickListener
                if (row.used || !select(id, false)) {
                    AlertDialog.Builder(ctx).setTitle(row.title).setMessage(R.string.proxy_used_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.proxy_use_anyway) { _, _ -> select(id, true) }.show()
                }
            }
        }
        dialog.show()
    } catch (e: Exception) {
        toast(R.string.proxy_save_failed)
    }
}

private class ProxyRows(var rows: List<ProxyRow>, private val current: String, private val latency: Map<String, Int>) : BaseAdapter() {
    var testing = false
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = getItem(position).id != null
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (isEnabled(position)) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = getItem(position)
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        if (row.id == null) {
            val header = convertView as? LinearLayout ?: LinearLayout(ctx).apply {
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad / 2)
                gravity = Gravity.CENTER_VERTICAL
                val label = TextView(ctx).apply {
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(label)
                addView(View(ctx).apply { setBackgroundColor(label.currentTextColor); alpha = 0.25f },
                    LinearLayout.LayoutParams(0, (density + 0.5f).toInt(), 1f).apply { marginStart = pad / 2 })
            }
            (header.getChildAt(0) as TextView).text = row.title
            return header
        }
        val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_proxy_node, parent, false)
        val choice = view.findViewById<CheckedTextView>(R.id.proxy_choice)
        choice.text = row.title
        choice.isChecked = row.id == current
        choice.alpha = if (row.used) 0.48f else 1f
        val ms = latency[row.id]
        val value = view.findViewById<TextView>(R.id.proxy_latency_value)
        value.visibility = if (ms == null) View.GONE else View.VISIBLE
        if (ms != null) {
            value.text = when (ms) {
                -1 -> ctx.getString(R.string.proxy_timeout)
                -2 -> ctx.getString(R.string.proxy_unreachable)
                else -> ctx.getString(R.string.proxy_ms, ms)
            }
            val color = when (ms) {
                in 0..40 -> R.color.proxy_fast
                in 41..100 -> R.color.proxy_medium
                else -> R.color.proxy_slow
            }
            value.setTextColor(ContextCompat.getColor(ctx, color))
        }
        val pending = testing && ms == null && row.id.isNotEmpty() && row.id != "legacy"
        view.findViewById<ProgressBar>(R.id.proxy_latency_busy).visibility = if (pending) View.VISIBLE else View.GONE
        return view
    }
}
