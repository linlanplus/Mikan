package com.onebitmonochrome.blacksbbox.view.base

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.onebitmonochrome.blacksbbox.util.LanguageManager


open class BaseActivity : AppCompatActivity() {

    private var language: String? = null

    override fun attachBaseContext(newBase: Context) {
        val mode = LanguageManager.current(newBase)
        language = mode
        super.attachBaseContext(LanguageManager.wrap(newBase, mode))
    }

    override fun onResume() {
        super.onResume()
        // Screens left in the back stack pick up a language changed elsewhere.
        if (language != LanguageManager.current(this)) recreate()
    }

    protected fun initToolbar(toolbar: Toolbar,title:Int, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        setSupportActionBar(toolbar)
        toolbar.setTitle(title)
        if (showBack) {
            supportActionBar?.let {
                it.setDisplayHomeAsUpEnabled(true)
                toolbar.setNavigationOnClickListener {
                    if (onBack != null) {
                        onBack()
                    }
                    finish()
                }
            }
        }
    }

    protected fun currentUserID():Int{
        return intent.getIntExtra("userID", 0)
    }
}