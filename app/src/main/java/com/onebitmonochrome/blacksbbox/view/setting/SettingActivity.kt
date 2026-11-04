package com.onebitmonochrome.blacksbbox.view.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ActivitySettingBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity

class SettingActivity : BaseActivity() {

    private val viewBinding: ActivitySettingBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.setting, true)
        if (savedInstanceState == null) {
            val page = if (intent.getBooleanExtra(PROXY_PAGE, false))
                ProxyManagerFragment().apply { arguments = intent.extras }
            else if (intent.getBooleanExtra(ALBUM_PAGE, false)) AlbumSettingsFragment()
            else SettingFragment()
            supportFragmentManager.beginTransaction().replace(R.id.fragment, page).commit()
        }
    }

    internal fun setPageTitle(text: String) {
        viewBinding.toolbarLayout.toolbar.title = text
    }

    companion object {
        private const val ALBUM_PAGE = "album_page"

        fun openAlbums(context: Context) {
            context.startActivity(Intent(context, SettingActivity::class.java).putExtra(ALBUM_PAGE, true))
        }

        private const val PROXY_PAGE = "proxy_page"
        internal const val PROXY_SOURCE = "proxy_source"

        fun openProxies(context: Context, sourceId: String? = null) {
            context.startActivity(Intent(context, SettingActivity::class.java)
                .putExtra(PROXY_PAGE, true).putExtra(PROXY_SOURCE, sourceId))
        }

        fun start(context: Context){
            val intent = Intent(context,SettingActivity::class.java)
            intent.action = Intent.ACTION_OPEN_DOCUMENT
            context.startActivity(intent)
        }
    }

}