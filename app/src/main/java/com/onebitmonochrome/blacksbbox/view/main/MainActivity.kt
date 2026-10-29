package com.onebitmonochrome.blacksbbox.view.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
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
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.databinding.ActivityMainBinding
import com.onebitmonochrome.blacksbbox.databinding.ViewGroupTitleBinding
import com.onebitmonochrome.blacksbbox.util.Resolution
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import com.onebitmonochrome.blacksbbox.view.home.HomeFragment
import com.onebitmonochrome.blacksbbox.view.list.ListActivity
import com.onebitmonochrome.blacksbbox.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var homeFragment: HomeFragment

    private var groupTitleBinding: ViewGroupTitleBinding? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        private const val VPN_PERMISSION_REQUEST_CODE = 1002
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

            
            checkStoragePermission()

            
            checkVpnPermission()

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

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                
                if (!android.os.Environment.isExternalStorageManager()) {
                    Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted")
                    showStoragePermissionDialog()
                }
            } else {
                
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                        this,
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(
                            TAG,
                            "Storage permissions not granted on Android ${android.os.Build.VERSION.SDK_INT}"
                    )
                    requestLegacyStoragePermission()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage permission: ${e.message}")
        }
    }

    private fun requestLegacyStoragePermission() {
        try {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_REQUEST_CODE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting storage permission: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                            grantResults.all {
                                it == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
            ) {
                Log.d(TAG, "Storage permissions granted")
            } else {
                Log.w(TAG, "Storage permissions denied")
            }
        }
    }

    private fun showStoragePermissionDialog() {
        try {
            MaterialDialog(this).show {
                title(text = "Storage Permission Required")
                message(
                        text =
                                "This app needs 'All Files Access' permission to properly run sandboxed apps. Without this permission, some apps may not work correctly.\n\nPlease grant permission in the next screen."
                )
                positiveButton(text = "Grant Permission") { openAllFilesAccessSettings() }
                negativeButton(text = "Later") { Log.w(TAG, "User postponed storage permission") }
                cancelable(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing storage permission dialog: ${e.message}")
        }
    }

    private fun openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent =
                        Intent(
                                android.provider.Settings
                                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        )
                intent.data = Uri.parse("package:$packageName")
                storagePermissionResult.launch(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening storage settings: ${e.message}")
            
            try {
                val intent =
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storagePermissionResult.launch(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening fallback storage settings: ${e2.message}")
            }
        }
    }

    private val storagePermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        if (android.os.Environment.isExternalStorageManager()) {
                            Log.d(TAG, "Storage permission granted!")
                        } else {
                            Log.w(TAG, "Storage permission still not granted")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling storage permission result: ${e.message}")
                }
            }

    
    private fun checkVpnPermission() {
        try {
            val vpnEnabled = try {
                AppManager.mBlackBoxLoader.useVpnNetwork()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading VPN setting: ${e.message}")
                false
            }

            if (android.os.Build.VERSION.SDK_INT >= 34) {
                if (vpnEnabled) {
                    Log.w(TAG, "VPN setting enabled on SDK>=34; forcing disable (unsupported)")
                    try {
                        AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disabling VPN setting: ${e.message}")
                    }
                }
                return
            }

            if (!vpnEnabled) {
                Log.d(TAG, "VPN setting disabled; skipping VPN permission")
                return
            }

            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // Per UX requirement: only prompt for VPN permission when user explicitly
                // enables the toggle in Settings. If permission is missing at startup,
                // disable the toggle and let the user re-enable it to trigger the prompt.
                Log.w(TAG, "VPN setting enabled but permission missing; disabling setting")
                try {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disabling VPN setting: ${e.message}")
                }
            } else {
                
                Log.d(TAG, "VPN permission already granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission: ${e.message}")
        }
    }

    private val vpnPermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                try {
                    if (result.resultCode == RESULT_OK) {
                        Log.d(TAG, "VPN permission granted!")
                        
                    } else {
                        Log.w(TAG, "VPN permission denied by user")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling VPN permission result: ${e.message}")
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
