package com.onebitmonochrome.blacksbbox.view.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.App
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.databinding.ActivityMainBinding
import com.onebitmonochrome.blacksbbox.databinding.ViewGroupTitleBinding
import com.onebitmonochrome.blacksbbox.util.Resolution
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import com.onebitmonochrome.blacksbbox.util.UpdateChecker
import com.onebitmonochrome.blacksbbox.view.home.HomeFragment
import com.onebitmonochrome.blacksbbox.view.list.ListActivity
import com.onebitmonochrome.blacksbbox.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var homeFragment: HomeFragment

    private var groupTitleBinding: ViewGroupTitleBinding? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val HOME_TAG = "home"

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            groupTitle()
            initHome(savedInstanceState)
            initFab()
            initFreezeFab()

            

            if (savedInstanceState == null) UpdateChecker.checkQuietly(this)

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            
            showErrorDialog("Failed to initialize app: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateFreezeFabState()
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing freeze FAB state: ${e.message}")
        }
    }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Error")
                message(text = message)
                positiveButton(text = "OK") { finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    /** Group switcher shown in place of the toolbar title; the home fragment fills it in. */
    fun groupTitle(): ViewGroupTitleBinding {
        groupTitleBinding?.let { return it }
        val toolbar = viewBinding.toolbarLayout.toolbar
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val binding = ViewGroupTitleBinding.inflate(layoutInflater, toolbar, false)
        toolbar.addView(
                binding.root,
                Toolbar.LayoutParams(Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        groupTitleBinding = binding
        return binding
    }

    private fun initHome(savedInstanceState: Bundle?) {
        val existing = supportFragmentManager.findFragmentByTag(HOME_TAG) as? HomeFragment
        if (existing != null) {
            homeFragment = existing
            return
        }
        homeFragment = HomeFragment()
        supportFragmentManager.beginTransaction()
                .replace(R.id.home_container, homeFragment, HOME_TAG)
                .commitNow()
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener { fab ->
                fab.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                    fab.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }.start()
                try {
                    // Every clone gets a fresh engine user, so nothing in the list counts as installed.
                    val intent = Intent(this, ListActivity::class.java)
                    intent.putExtra("userID", -1)
                    apkPathResult.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ListActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    private fun initFreezeFab() {
        try {
            updateFreezeFabState()
            viewBinding.freezeFab.setOnClickListener {
                try {
                    if (AppFreezeManager.isGlobalAutoFreezeEnabled()) {
                        AppFreezeManager.setGlobalAutoFreezeEnabled(false)
                        updateFreezeFabState()
                        toast(R.string.auto_freeze_master_disabled)
                    } else {
                        AlertDialog.Builder(this)
                                .setTitle(R.string.auto_freeze_toggle_title)
                                .setMessage(R.string.auto_freeze_toggle_message)
                                .setPositiveButton(R.string.enable) { _, _ ->
                                    AppFreezeManager.setGlobalAutoFreezeEnabled(true)
                                    updateFreezeFabState()
                                    toast(R.string.auto_freeze_master_enabled)
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling auto-freeze: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFreezeFab: ${e.message}")
        }
    }

    private fun updateFreezeFabState() {
        val enabled = AppFreezeManager.isGlobalAutoFreezeEnabled()
        viewBinding.freezeFab.setImageResource(
                if (enabled) R.drawable.ic_snowflake else R.drawable.ic_snowflake_off
        )
        viewBinding.freezeFab.imageTintList = null
        viewBinding.freezeFab.backgroundTintList =
                ColorStateList.valueOf(
                        ContextCompat.getColor(
                                this,
                                if (enabled) {
                                    R.color.fab_tint
                                } else {
                                    R.color.surface_variant
                                }
                        )
                )
        viewBinding.freezeFab.contentDescription =
                getString(
                        if (enabled) {
                            R.string.auto_freeze_toggle_on
                        } else {
                            R.string.auto_freeze_toggle_off
                        }
                )
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 200L
            if (show) {
                viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time).start()
                viewBinding.freezeFab
                        .animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(time)
                        .start()
            } else {
                viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time).start()
                viewBinding.freezeFab
                        .animate()
                        .translationY(tranY)
                        .alpha(0f)
                        .setDuration(time)
                        .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showFloatButton: ${e.message}")
        }
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode == RESULT_OK) {
                        it.data?.getStringExtra("source")?.let { source -> homeFragment.install(source) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu: ${e.message}")
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            when (item.itemId) {
                R.id.main_setting -> {
                    SettingActivity.start(this)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item selection: ${e.message}")
            return false
        }
    }
}
