/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

package com.github.shadowsocks
import SpeedUpVPN.VpnEncrypt
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import android.util.LongSparseArray
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.crashlytics.android.Crashlytics
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.showAllowingStateLoss
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.datas
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.utils.readableMessage
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.MainListListener
import com.github.shadowsocks.widget.RecyclerViewNoBugLinearLayoutManager
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.formats.NativeAdOptions
import com.google.android.gms.ads.formats.UnifiedNativeAd
import com.google.android.gms.ads.formats.UnifiedNativeAdView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets

class ProfilesFragment : ToolbarFragment(), Toolbar.OnMenuItemClickListener {
    companion object {
        /**
         * used for callback from stateChanged from MainActivity
         */
        var instance: ProfilesFragment? = null

        private const val KEY_URL = "com.github.shadowsocks.QRCodeDialog.KEY_URL"
        private const val REQUEST_IMPORT_PROFILES = 1
        private const val REQUEST_REPLACE_PROFILES = 3
        private const val REQUEST_EXPORT_PROFILES = 2

        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    /**
     * Is ProfilesFragment editable at all.
     */
    private val isEnabled get() = (activity as MainActivity).state.let { it.canStop || it == BaseService.State.Stopped }

    private fun isProfileEditable(id: Long) =
            (activity as MainActivity).state == BaseService.State.Stopped || id !in Core.activeProfileIds

    private var nativeAd: UnifiedNativeAd? = null
    private var nativeAdView: UnifiedNativeAdView? = null
    private var adHost: ProfileViewHolder? = null
    private fun tryBindAd() = lifecycleScope.launchWhenStarted {
        try {
            val fp = layoutManager.findFirstVisibleItemPosition()
            if (fp < 0) return@launchWhenStarted
            for (i in object : Iterator<Int> {
                var first = fp
                var last = layoutManager.findLastCompletelyVisibleItemPosition()
                var flipper = false
                override fun hasNext() = first <= last
                override fun next(): Int {
                    flipper = !flipper
                    return if (flipper) first++ else last--
                }
            }.asSequence().toList().reversed()) {
                try {
                    var viewHolder = profilesList.findViewHolderForAdapterPosition(i)
					if(viewHolder==null)continue
					viewHolder = viewHolder  as ProfileViewHolder
                    if (true /*viewHolder.item.isBuiltin()*/) {
                        viewHolder.populateUnifiedNativeAdView(nativeAd!!, nativeAdView!!)
                        // might be in the middle of a layout after scrolling, need to wait
                        withContext(Dispatchers.Main) { profilesAdapter.notifyItemChanged(i) }
                        break
                    }
                }catch (ex:Exception){
                    Log.e("ssvpn",ex.message,ex)
		    printLog(ex)
                    continue
                }
            }
        }catch (e:Exception){
            Log.e("ssvpn",e.message,e)
	    printLog(e)
        }
    }

    @SuppressLint("ValidFragment")
    class QRCodeDialog() : DialogFragment() {
        constructor(url: String) : this() {
            arguments = bundleOf(Pair(KEY_URL, url))
        }

        /**
         * Based on:
         * https://android.googlesource.com/platform/packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
         * https://android.googlesource.com/platform/packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
         */
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = try {
            val url = arguments?.getString(KEY_URL)!!
            val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)
            val hints = mutableMapOf<EncodeHintType, Any>()
            if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
            val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                setImageBitmap(Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                    for (x in 0 until size) for (y in 0 until size) {
                        setPixel(x, y, if (qrBits.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                })
            }
        } catch (e: WriterException) {
            Crashlytics.logException(e)
            (activity as MainActivity).snackbar().setText(e.readableMessage).show()
            dismiss()
            null
        }
    }

    inner class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view),
            View.OnClickListener, PopupMenu.OnMenuItemClickListener {
        internal lateinit var item: Profile

        private val text1 = itemView.findViewById<TextView>(android.R.id.text1)
        private val text2 = itemView.findViewById<TextView>(android.R.id.text2)
        private val traffic = itemView.findViewById<TextView>(R.id.traffic)
        private val edit = itemView.findViewById<View>(R.id.edit)
        private val subscription = itemView.findViewById<View>(R.id.subscription)
        private val adContainer = itemView.findViewById<LinearLayout>(R.id.ad_container)
        private val share = itemView.findViewById<View>(R.id.share)

        init {
            edit.setOnClickListener {
                item = ProfileManager.getProfile(item.id)!!
                startConfig(item)
            }
            subscription.setOnClickListener {
                item = ProfileManager.getProfile(item.id)!!
                startConfig(item)
            }
            TooltipCompat.setTooltipText(edit, edit.contentDescription)
            TooltipCompat.setTooltipText(subscription, subscription.contentDescription)
            itemView.setOnClickListener(this)
            share.setOnClickListener {
                val popup = PopupMenu(requireContext(), share)
                popup.menuInflater.inflate(R.menu.profile_share_popup, popup.menu)
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }
            TooltipCompat.setTooltipText(share, share.contentDescription)
        }

        fun populateUnifiedNativeAdView(nativeAd: UnifiedNativeAd, adView: UnifiedNativeAdView) {
            // Set other ad assets.
            adView.headlineView = adView.findViewById(R.id.ad_headline)
            adView.bodyView = adView.findViewById(R.id.ad_body)
            adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
            adView.iconView = adView.findViewById(R.id.ad_app_icon)
            adView.starRatingView = adView.findViewById(R.id.ad_stars)
            adView.advertiserView = adView.findViewById(R.id.ad_advertiser)

            // The headline and media content are guaranteed to be in every UnifiedNativeAd.
            (adView.headlineView as TextView).text = nativeAd.headline

            // These assets aren't guaranteed to be in every UnifiedNativeAd, so it's important to
            // check before trying to display them.
            if (nativeAd.body == null) {
                adView.bodyView.visibility = View.INVISIBLE
            } else {
                adView.bodyView.visibility = View.VISIBLE
                (adView.bodyView as TextView).text = nativeAd.body
            }

            if (nativeAd.callToAction == null) {
                adView.callToActionView.visibility = View.INVISIBLE
            } else {
                adView.callToActionView.visibility = View.VISIBLE
                (adView.callToActionView as Button).text = nativeAd.callToAction
            }

            if (nativeAd.icon == null) {
                adView.iconView.visibility = View.GONE
            } else {
                (adView.iconView as ImageView).setImageDrawable(
                        nativeAd.icon.drawable)
                adView.iconView.visibility = View.VISIBLE
            }

            if (nativeAd.starRating == null) {
                adView.starRatingView.visibility = View.INVISIBLE
            } else {
                (adView.starRatingView as RatingBar).rating = nativeAd.starRating!!.toFloat()
                adView.starRatingView.visibility = View.VISIBLE
            }

            if (nativeAd.advertiser == null) {
                adView.advertiserView.visibility = View.INVISIBLE
            } else {
                (adView.advertiserView as TextView).text = nativeAd.advertiser
                adView.advertiserView.visibility = View.VISIBLE
            }

            // This method tells the Google Mobile Ads SDK that you have finished populating your
            // native ad view with this native ad.
            adView.setNativeAd(nativeAd)
            //adView.setBackgroundColor(Color.WHITE) //Adding dividing line for ads
            adContainer.setPadding(0,1,0,0)  //Adding dividing line for ads
            adContainer.addView(adView)
            adHost = this
        }

        fun attach() {
            if (adHost != null /*|| !item.isBuiltin()*/) return
            if (nativeAdView == null) {
                nativeAdView = layoutInflater.inflate(R.layout.ad_unified, adContainer, false) as UnifiedNativeAdView
                AdLoader.Builder(context, "ca-app-pub-2194043486084479/8267385919").apply {
                    forUnifiedNativeAd { unifiedNativeAd ->
                        // You must call destroy on old ads when you are done with them,
                        // otherwise you will have a memory leak.
                        nativeAd?.destroy()
                        nativeAd = unifiedNativeAd
                        tryBindAd()
                    }
                    withNativeAdOptions(NativeAdOptions.Builder().apply {
                        setVideoOptions(VideoOptions.Builder().apply {
                            setStartMuted(true)
                        }.build())
                    }.build())
                }.build().loadAd(AdRequest.Builder().apply {
                    addTestDevice("B08FC1764A7B250E91EA9D0D5EBEB208")
                    addTestDevice("7509D18EB8AF82F915874FEF53877A64")
                    addTestDevice("F58907F28184A828DD0DB6F8E38189C6")
                    addTestDevice("FE983F496D7C5C1878AA163D9420CA97")
                }.build())
            } else if (nativeAd != null) populateUnifiedNativeAdView(nativeAd!!, nativeAdView!!)
        }

        fun detach() {
            if (adHost == this) {
                adHost = null
                adContainer.removeAllViews()
                tryBindAd()
            }
        }

        fun bind(item: Profile) {
            this.item = item
            val editable = isProfileEditable(item.id)
            edit.isEnabled = editable
            share.isEnabled = !item.isBuiltin()
            edit.alpha = if (editable) 1F else .5F
            subscription.isEnabled = editable
            subscription.alpha = if (editable) 1F else .5F
            var tx = item.tx
            var rx = item.rx
            statsCache[item.id]?.apply {
                tx += txTotal
                rx += rxTotal
            }
            text1.text = item.formattedName
            text2.text = ArrayList<String>().apply {
                if (item.url_group.isNotEmpty()) this += item.url_group	    
                else if (!item.name.isNullOrEmpty()) this += item.formattedAddress
                val id = PluginConfiguration(item.plugin ?: "").selected
                if (id.isNotEmpty()) this += getString(R.string.profile_plugin, id)
            }.joinToString("\n")
            val context = requireContext()
            traffic.text =ArrayList<String>().apply {
                if (item.elapsed > 0L ) this += String.format("%dms", item.elapsed)
                if (item.elapsed == -1L ) this += "failed"
                if (item.elapsed == -2L ) this += VpnEncrypt.testing
                if (tx > 0 || rx > 0) this +=  getString(R.string.traffic,
                        Formatter.formatFileSize(context, tx), Formatter.formatFileSize(context, rx))
            }.joinToString(" \t")

            if (item.id == DataStore.profileId) {
                itemView.isSelected = true
                selectedItem = this
            } else {
                itemView.isSelected = false
                if (selectedItem === this) selectedItem = null
            }

            if (item.subscription == Profile.SubscriptionStatus.Active) {
                edit.visibility = View.GONE
                subscription.visibility = View.VISIBLE
            } else {
                edit.visibility = View.VISIBLE
                subscription.visibility = View.GONE
            }
        }

        override fun onClick(v: View?) {
            if (isEnabled) {
                val activity = activity as MainActivity
                val old = DataStore.profileId
                Core.switchProfile(item.id)
                profilesAdapter.refreshId(old)
                itemView.isSelected = true
                if (activity.state.canStop) Core.reloadService()
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_qr_code -> {
                QRCodeDialog(this.item.toString()).showAllowingStateLoss(parentFragmentManager)
                true
            }
            R.id.action_export_clipboard -> {
                clipboard.setPrimaryClip(ClipData.newPlainText(null, this.item.toString()))
                true
            }
            else -> false
        }
    }

    inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>(), ProfileManager.Listener {
        internal val profiles = ProfileManager.getProfilesOrderlySpeed()?.toMutableList() ?: mutableListOf()
        private val updated = HashSet<Profile>()

        init {
            setHasStableIds(true)   // see: http://stackoverflow.com/a/32488059/2245107
        }

        override fun onViewAttachedToWindow(holder: ProfileViewHolder) = holder.attach()
        override fun onViewDetachedFromWindow(holder: ProfileViewHolder) = holder.detach()
        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            try {
                holder.bind(profiles[position])
            }
            catch (e:Exception){
                Log.e("speedup.vpn","",e)
            }

        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder = ProfileViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false))

        override fun getItemCount(): Int = profiles.size
        override fun getItemId(position: Int): Long {
            try {
                return profiles[position].id
            }
            catch (e:Exception){
                Log.e("speedup.vpn","",e)
                return 0
            }
        }

        override fun onAdd(profile: Profile) {
            undoManager.flush()
            val pos = itemCount
            profiles += profile
            (activity as MainActivity).runOnUiThread({
            notifyItemInserted(pos)
            })
        }

        fun move(from: Int, to: Int) {
            undoManager.flush()
            val first = profiles[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(-1, to + 1 downTo from)
            for (i in range) {
                val next = profiles[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                profiles[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            profiles[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            updated.forEach { ProfileManager.updateProfile(it) }
            updated.clear()
        }

        fun remove(pos: Int) {
            profiles.removeAt(pos)
            notifyItemRemoved(pos)
        }

        fun undo(actions: List<Pair<Int, Profile>>) {
            for ((index, item) in actions) {
                profiles.add(index, item)
                notifyItemInserted(index)
            }
        }

        fun commit(actions: List<Pair<Int, Profile>>) {
            for ((_, item) in actions) ProfileManager.delProfile(item.id)
        }

        fun refreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun deepRefreshId(id: Long) {
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return
            profiles[index] = ProfileManager.getProfile(id)!!
            notifyItemChanged(index)
        }

        override fun onRemove(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index < 0) return
            profiles.removeAt(index)
            (activity as MainActivity).runOnUiThread({
            notifyItemRemoved(index)
            })
            if (profileId == DataStore.profileId) DataStore.profileId = 0   // switch to null profile
        }

        override fun onCleared() {
            profiles.clear()
            notifyDataSetChanged()
        }

        override fun reloadProfiles() {
            profiles.clear()
            ProfileManager.getActiveProfiles()?.let { profiles.addAll(it) }
            notifyDataSetChanged()
        }
    }

    private var selectedItem: ProfileViewHolder? = null

    val profilesAdapter by lazy { ProfilesAdapter() }
    private lateinit var profilesList: RecyclerView
    private val layoutManager by lazy { RecyclerViewNoBugLinearLayoutManager(context, RecyclerView.VERTICAL, false) }
    private lateinit var undoManager: UndoSnackbarManager<Profile>
    private val statsCache = LongSparseArray<TrafficStats>()

    private val clipboard by lazy { requireContext().getSystemService<ClipboardManager>()!! }

    private fun startConfig(profile: Profile) {
        profile.serialize()
        startActivity(Intent(context, ProfileConfigActivity::class.java).putExtra(Action.EXTRA_PROFILE_ID, profile.id))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setOnApplyWindowInsetsListener(ListHolderListener)
        toolbar.setTitle(R.string.profiles)
        toolbar.inflateMenu(R.menu.profile_manager_menu)
        toolbar.setOnMenuItemClickListener(this)
        //ProfileManager.ensureNotEmpty() // don't create 198.199.101.152 server

        var recnews = getString(R.string.recommended_news)
        var recommendedNewsView: WebView = view.findViewById(R.id.recommended_news2)
        //recommendedNewsView.settings.javaScriptEnabled = true
        recommendedNewsView.setBackgroundColor(Color.BLACK);
        recommendedNewsView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if(url.isNullOrEmpty() || url.isBlank()) return false
                (activity as MainActivity).launchUrl(url)
                return true
            }
        }
        recommendedNewsView.loadDataWithBaseURL(null,recnews,"text/html; charset=utf-8",  "UTF-8",null)

        profilesList = view.findViewById(R.id.list)
        profilesList.setOnApplyWindowInsetsListener(MainListListener)
        profilesList.layoutManager = layoutManager
        profilesList.addItemDecoration(DividerItemDecoration(context, layoutManager.orientation))
        layoutManager.scrollToPosition(profilesAdapter.profiles.indexOfFirst { it.id == DataStore.profileId })
        val animator = DefaultItemAnimator()
        animator.supportsChangeAnimations = false // prevent fading-in/out when rebinding
        profilesList.itemAnimator = animator
        profilesList.adapter = profilesAdapter
        instance = this
        ProfileManager.listener = profilesAdapter
        undoManager = UndoSnackbarManager(activity as MainActivity, profilesAdapter::undo, profilesAdapter::commit)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isProfileEditable((viewHolder as ProfileViewHolder).item.id) /* && !(viewHolder as ProfileViewHolder).item.isBuiltin() */)
                        super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                    if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.adapterPosition
                profilesAdapter.remove(index)
                undoManager.remove(Pair(index, (viewHolder as ProfileViewHolder).item))
            }

            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                profilesAdapter.move(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                profilesAdapter.commitMove()
            }
        }).attachToRecyclerView(profilesList)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.update_servers -> {
                Core.updateBuiltinServers()
                if(DataStore.is_get_free_servers)Core.importFreeSubs()
                true
            }
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
                true
            }
            R.id.action_import_clipboard -> {
                try {
                    val profiles = Profile.findAllUrls(
                            clipboard.primaryClip!!.getItemAt(0).text,
                            Core.currentProfile?.first
                    ).toList()
                    if (profiles.isNotEmpty()) {
                        profiles.forEach { ProfileManager.createProfile(it) }
                        (activity as MainActivity).snackbar().setText(R.string.action_import_msg).show()
                        return true
                    }
                } catch (exc: Exception) {
                    exc.printStackTrace()
                }
                (activity as MainActivity).snackbar().setText(R.string.action_import_err).show()
                true
            }
            R.id.action_import_file -> {
                startFilesForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/*", "text/*"))
                }, REQUEST_IMPORT_PROFILES)
                true
            }
            R.id.action_replace_file -> {
                startFilesForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/*", "text/*"))
                }, REQUEST_REPLACE_PROFILES)
                true
            }
            R.id.action_manual_settings -> {
                startConfig(ProfileManager.createProfile(
                        Profile().also { Core.currentProfile?.first?.copyFeatureSettingsTo(it) }))
                true
            }
            R.id.action_export_clipboard -> {
                val profiles = ProfileManager.getAllProfilesIgnoreGroup(VpnEncrypt.vpnGroupName)
                (activity as MainActivity).snackbar().setText(if (profiles != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(null, profiles.joinToString("\n")))
                    R.string.action_export_msg
                } else R.string.action_export_err).show()
                true
            }
            R.id.action_export_file -> {
                startFilesForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "profiles.json")   // optional title that can be edited
                }, REQUEST_EXPORT_PROFILES)
                true
            }

            R.id.ping_all -> {
                for (element in profilesAdapter.profiles) {
                    element.elapsed = 0
                }
                profilesAdapter.notifyDataSetChanged()

                for (k in 0 until profilesAdapter.profiles.size) {
                    try {
                        Log.e("tcping", "$k")
                        GlobalScope.launch {
                            profilesAdapter.profiles[k].elapsed = tcping(profilesAdapter.profiles[k].host, profilesAdapter.profiles[k].remotePort)
                            ProfileManager.updateProfile(profilesAdapter.profiles[k])
                            Log.e("tcping", "$k - " + profilesAdapter.profiles[k].elapsed)
                            activity?.runOnUiThread() {
                                Log.e("tcping", "$k - update")
                                profilesAdapter.refreshId(profilesAdapter.profiles[k].id)
                            }
                        }
                    }
                    catch(e:IndexOutOfBoundsException){}
                }
                true
            }

            R.id.real_ping_all -> {
                realTestProfiles(false)
                true
            }

            R.id.retest_invalid_servers -> {
                realTestProfiles(true)
                true
            }

            R.id.remove_invalid_servers -> {
                try {
                    for (k in profilesAdapter.profiles.size-1 downTo  0 ) {
                        if (profilesAdapter.profiles[k].elapsed == -1L){
                            ProfileManager.delProfile(profilesAdapter.profiles[k].id)
                            //profilesAdapter.remove(k)
                        }
                    }
                    val list=profilesAdapter.profiles.sortedWith(compareBy({ it.url_group }, { it.elapsed }))
                    profilesAdapter.profiles.clear()
                    profilesAdapter.profiles.addAll(list)
                    profilesAdapter.notifyDataSetChanged()
                }
                catch (e:Exception){
                    e.printStackTrace()
                }
                true
            }

            R.id.sort_servers_by_speed -> {
                try {
                    profilesAdapter.profiles.sortBy {it.elapsed }
                    //configs.vmess.reverse()
                    profilesAdapter.notifyDataSetChanged()
                }
                catch (e:Exception){
                    e.printStackTrace()
                }
                true
            }
            else -> false
        }
    }

    private fun realTestProfiles(testInvalidOnly:Boolean) {
        GlobalScope.launch {
            val activity = activity as MainActivity
            Core.stopService()
            var isProxyStarted=false
            for (k in 0 until profilesAdapter.profiles.size) {
                try {
                    if(testInvalidOnly && profilesAdapter.profiles[k].elapsed>0)continue
                    Log.e("real_ping_all",k.toString())
                    profilesAdapter.profiles[k].elapsed=-2
                    val old = DataStore.profileId
                    Core.switchProfile(profilesAdapter.profiles[k].id)
                    activity?.runOnUiThread() {
                        layoutManager.scrollToPositionWithOffset(k, 0)
                        layoutManager.stackFromEnd = true
                        profilesAdapter.refreshId(old)
                        profilesAdapter.refreshId(profilesAdapter.profiles[k].id)
                    }

                    var result = tcping(profilesAdapter.profiles[k].host, profilesAdapter.profiles[k].remotePort)
                    if( result > 0) {
                        if(!isProxyStarted)Core.startServiceForTest()
                        else Core.reloadService()

                        var ttt = 0
                        while (tcping("127.0.0.1", DataStore.portProxy) < 0 || tcping("127.0.0.1", VpnEncrypt.HTTP_PROXY_PORT) < 0) {
                            Log.e("starting", "$k try $ttt ...")
                            if (ttt == 5) {
                                activity?.runOnUiThread() {Core.alertMessage(activity.getString(R.string.toast_test_interrupted,profilesAdapter.profiles[k].name),activity)}
                                Log.e("realTestProfiles","Server: "+profilesAdapter.profiles[k].name+" or the one before it caused the test to be interrupted.")
                                Core.stopService()
                                return@launch
                            }
                            Thread.sleep(500)
                            ttt++
                        }
                        Thread.sleep(3_000) //必须等几秒，否则有问题...
                        if(!isProxyStarted)isProxyStarted=true //第一次启动成功
                        result = testConnection2()
                        profilesAdapter.profiles[k].elapsed = result
                    }
                    else{
                        profilesAdapter.profiles[k].elapsed = -1
                    }

                    Log.e("real_ping_all:", "$k,$result")
                    ProfileManager.updateProfile(profilesAdapter.profiles[k])
                    activity?.runOnUiThread() {
                        profilesAdapter.refreshId(profilesAdapter.profiles[k].id)
                    }
                }
                catch (e:Exception){
                    Log.e("real_ping_all",e.toString())
                }
            }
            Core.stopService()
            activity?.runOnUiThread() {
                //profilesAdapter.profiles.sortBy {it.elapsed ; it.url_group} // 低版本工作，但模拟器安卓10不工作
                val list=profilesAdapter.profiles.sortedWith(compareBy({ it.url_group }, { it.elapsed }))
                profilesAdapter.profiles.clear()
                profilesAdapter.profiles.addAll(list)
                profilesAdapter.notifyDataSetChanged()
                try{Core.alertMessage(activity.getString(R.string.toast_test_ended),activity)}catch (t:Throwable){}
            }
        }
    }

    private fun testConnection2(timeout:Int = 10_000): Long {
        var result: Long
        var conn: HttpURLConnection? = null

        try {
            val url = URL("https",
                    "raw.githubusercontent.com",
                    "/")

            //val conn = (if (DataStore.serviceMode != Key.modeVpn) { url.openConnection(Proxy(Proxy.Type.SOCKS, DataStore.proxyAddress))} else url.openConnection()) as HttpURLConnection
            val conn = url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", VpnEncrypt.HTTP_PROXY_PORT))) as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.117 Safari/537.36");
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("Connection", "close")
            conn.instanceFollowRedirects = false
            conn.useCaches = false

            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start

            if (code == 301 && conn.responseLength == 0L) {
                result = elapsed
            } else {
                throw IOException(code.toString())
            }
        } catch (e: IOException) {
            // network exception
            Log.d("testConnection2","IOException: "+Log.getStackTraceString(e))
            result = -1
        } catch (e: Exception) {
            // library exception, eg sumsung
            Log.d("testConnection2","Exception: "+Log.getStackTraceString(e))
            result = -1
        } finally {
            conn?.disconnect()
        }
        return result
    }

    private val URLConnection.responseLength: Long
        get() = if (Build.VERSION.SDK_INT >= 24) contentLengthLong else contentLength.toLong()

    /**
     * tcping
     */
    private fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (one != -1L  )
                if(time == -1L || one < time) {
                    time = one
                }
        }
        return time
    }
    private fun socketConnectTime(url: String, port: Int): Long {
        try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            var socketAddress = InetSocketAddress(url, port)
            socket.connect(socketAddress,5000)
            val time = System.currentTimeMillis() - start
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e("testConnection2",e.toString())
        } catch (e: IOException) {
            Log.e("testConnection2",e.toString())
        } catch (e: Exception) {
            Log.e("testConnection2",e.toString())
        }
        return -1
    }
    private fun startFilesForResult(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), requestCode)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        (activity as MainActivity).snackbar(getString(R.string.file_manager_missing)).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) super.onActivityResult(requestCode, resultCode, data)
        else when (requestCode) {
            REQUEST_IMPORT_PROFILES -> {
                val activity = activity as MainActivity
                try {
                    ProfileManager.createProfilesFromJson(data!!.datas.asSequence().map {
                        activity.contentResolver.openInputStream(it)
                    }.filterNotNull())
                } catch (e: Exception) {
                    activity.snackbar(e.readableMessage).show()
                }
            }
            REQUEST_REPLACE_PROFILES -> {
                val activity = activity as MainActivity
                try {
                    ProfileManager.createProfilesFromJson(data!!.datas.asSequence().map {
                        activity.contentResolver.openInputStream(it)
                    }.filterNotNull(), true)
                } catch (e: Exception) {
                    activity.snackbar(e.readableMessage).show()
                }
            }
            REQUEST_EXPORT_PROFILES -> {
                val profiles = ProfileManager.serializeToJsonIgnoreVPN()
                if (profiles != null) try {
                    requireContext().contentResolver.openOutputStream(data?.data!!)!!.bufferedWriter().use {
                        it.write(profiles.toString(2))
                    }
                } catch (e: Exception) {
                    printLog(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun onTrafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId != 0L) {  // ignore aggregate stats
            statsCache.put(profileId, stats)
            profilesAdapter.refreshId(profileId)
        }
    }

    fun onTrafficPersisted(profileId: Long) {
        statsCache.remove(profileId)
        profilesAdapter.deepRefreshId(profileId)
    }

    override fun onDestroyView() {
        undoManager.flush()
        nativeAd?.destroy()
        super.onDestroyView()
    }

    override fun onDestroy() {
        instance = null
        ProfileManager.listener = null
        super.onDestroy()
    }
}
