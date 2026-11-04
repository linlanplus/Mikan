package com.onebitmonochrome.blacksbbox.view.gms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.bean.GmsBean
import com.onebitmonochrome.blacksbbox.data.GmsRepository.Action
import com.onebitmonochrome.blacksbbox.databinding.ActivityGmsBinding
import com.onebitmonochrome.blacksbbox.util.InjectionUtil
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity

class GmsManagerActivity : LoadingActivity() {
    private val binding: ActivityGmsBinding by inflate()
    private val model by lazy { ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initToolbar(binding.toolbarLayout.toolbar, R.string.gms_manager, true)
        val adapter = RVAdapter<GmsBean>(this, GmsAdapter { confirm(it, Action.WIPE) })
            .bind(binding.recyclerView).setItemClickListener { _, row, _ ->
                if (row.needsSync) {
                    AlertDialog.Builder(this).setTitle(row.userName)
                        .setItems(arrayOf(getString(R.string.enable_gms), getString(R.string.disable_gms))) { _, i ->
                            confirm(row, if (i == 0) Action.ENABLE else Action.DISABLE)
                        }.show()
                } else confirm(row, if (row.enabled) Action.DISABLE else Action.ENABLE)
            }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        model.rows.observe(this) { adapter.setItems(it) }
        model.busy.observe(this) { if (it) showLoading() else hideLoading() }
        model.message.observe(this) { if (it != null) { toast(it); model.message.value = null } }
    }

    override fun onResume() { super.onResume(); model.refresh() }

    private fun confirm(row: GmsBean, action: Action) {
        val hint = when (action) {
            Action.ENABLE -> getString(R.string.enable_gms_hint) + "\n\n" + getString(R.string.enable_gms_warning)
            Action.DISABLE -> getString(R.string.disable_gms_hint)
            Action.WIPE -> getString(R.string.wipe_gms_hint)
        }
        AlertDialog.Builder(this).setTitle(row.userName)
            .setMessage(hint + "\n\n" + getString(R.string.gms_scope_hint))
            .setPositiveButton(R.string.done) { _, _ -> model.change(row.groupId, action) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, GmsManagerActivity::class.java))
    }
}
