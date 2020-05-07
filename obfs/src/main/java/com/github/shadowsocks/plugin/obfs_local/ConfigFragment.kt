package com.github.shadowsocks.plugin.obfs_local

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.PluginOptions

class ConfigFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
    private val obfs by lazy { findPreference<ListPreference>("obfs")!! }
    private val obfshost by lazy { findPreference<EditTextPreference>("obfs-host")!! }
    private val obfsuri by lazy { findPreference<EditTextPreference>("obfs-uri")!! }
    val options get() = PluginOptions().apply {
        put("obfs", obfs.value ?: "http")
        put("obfs-host", obfshost.text ?: "cloudfront.net")
        //put("obfs-uri", obfsuri.text ?: "/")
    }

    fun onInitializePluginOptions(options: PluginOptions) {
        //Log.e("obfs.value","onInitializePluginOptions "+ obfs.value)
        //Log.e("obfs.value","options[\"obfs\"] "+ options["obfs"])
        obfs.value = options["obfs"] ?: "http"
        //Log.e("obfs.value","onInitializePluginOptions "+ obfs.value)
        obfshost.text = options["obfs-host"] ?: "cloudfront.net"
        //obfsuri.text = options["obfs-uri"] ?: "/"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.config)
        obfs.onPreferenceChangeListener = this
        obfshost.setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
        obfsuri.isVisible=false
        obfsuri.isEnabled=false
        //obfsuri.setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setOnApplyWindowInsetsListener { v, insets ->
            insets.apply { v.updatePadding(bottom = systemWindowInsetBottom) }
        }

        //if (savedInstanceState != null) {
        //    onInitializePluginOptions(PluginOptions(savedInstanceState.getString(PluginContract.EXTRA_OPTIONS)))
        //}
    }

    /*
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PluginContract.EXTRA_OPTIONS, options.toString())
    }
    */

    override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
         return true
    }
}