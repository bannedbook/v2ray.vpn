package com.github.shadowsocks.plugin.obfs_local

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import com.github.shadowsocks.plugin.ConfigurationActivity
import com.github.shadowsocks.plugin.PluginOptions

class ConfigActivity : ConfigurationActivity(), Toolbar.OnMenuItemClickListener {
    //fix activity_main: Error inflating class fragment
    //https://stackoverflow.com/questions/19874882/android-view-inflateexception-binary-xml-file-error-inflating-class-fragment
    private lateinit var child: ConfigFragment
    private lateinit var oldOptions: PluginOptions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        findViewById<View>(android.R.id.content).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                view.updatePadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight)
                @Suppress("DEPRECATION")
                insets.replaceSystemWindowInsets(0, 0, 0, insets.systemWindowInsetBottom)
            }
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        findViewById<Toolbar>(R.id.toolbar).apply {
            title = this@ConfigActivity.title
            setNavigationIcon(R.drawable.ic_navigation_close)
            setNavigationOnClickListener { onBackPressed() }
            inflateMenu(R.menu.menu_config)
            setOnMenuItemClickListener(this@ConfigActivity)
        }

        child = ConfigFragment()
        supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, child)
                .commit()
    }

    override fun onInitializePluginOptions(options: PluginOptions) {
        oldOptions = options
        child.onInitializePluginOptions(options)
    }

    override fun onMenuItemClick(item: MenuItem?) = when (item?.itemId) {
        R.id.action_apply -> {
            saveChanges(child.options)
            finish()
            true
        }
        else -> false
    }

    override fun onBackPressed() {
        if (child.options != oldOptions) AlertDialog.Builder(this).run {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                saveChanges(child.options)
                finish()
            }
            setNegativeButton(R.string.no) { _, _ -> finish() }
            setNeutralButton(android.R.string.cancel, null)
            create()
        }.show() else super.onBackPressed()
    }
}