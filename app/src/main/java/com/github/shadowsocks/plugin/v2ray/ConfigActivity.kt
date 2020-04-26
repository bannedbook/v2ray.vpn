/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.plugin.v2ray

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import com.github.shadowsocks.plugin.ConfigurationActivity
import com.github.shadowsocks.plugin.PluginOptions

class ConfigActivity : ConfigurationActivity(), Toolbar.OnMenuItemClickListener {
    private val child by lazy { supportFragmentManager.findFragmentById(R.id.content) as ConfigFragment }
    private lateinit var oldOptions: PluginOptions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
            inflateMenu(R.menu.toolbar_config)
            setOnMenuItemClickListener(this@ConfigActivity)
        }
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
