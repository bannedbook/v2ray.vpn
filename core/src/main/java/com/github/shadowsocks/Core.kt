/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2018 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2018 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.bg.ProxyTestService
import com.github.shadowsocks.bg.V2RayTestService
import com.github.shadowsocks.core.BuildConfig
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.database.SSRSubManager
import com.github.shadowsocks.net.TcpFastOpen
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.subscription.SubscriptionService
import com.github.shadowsocks.utils.*
import com.github.shadowsocks.work.UpdateCheck
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.initialize
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import me.dozen.dpreference.DPreference
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlin.reflect.KClass

object Core {
    const val TAG = "Core"
    lateinit var app: Application
        @VisibleForTesting set
    val defaultDPreference by lazy { DPreference(app, app.packageName + "_preferences") }
    lateinit var configureIntent: (Context) -> PendingIntent
    val activity by lazy { app.getSystemService<ActivityManager>()!! }
    val clipboard by lazy { app.getSystemService<ClipboardManager>()!! }
    val connectivity by lazy { app.getSystemService<ConnectivityManager>()!! }
    val notification by lazy { app.getSystemService<NotificationManager>()!! }
    val packageInfo: PackageInfo by lazy { getPackageInfo(app.packageName) }
    val deviceStorage by lazy { if (Build.VERSION.SDK_INT < 24) app else DeviceStorageApp(app) }
    val directBootSupported by lazy {
        Build.VERSION.SDK_INT >= 24 && app.getSystemService<DevicePolicyManager>()?.storageEncryptionStatus ==
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
    }

    const val appName = BuildConfig.FLAVOR
    val applicationId = when (appName) {
            "ssvpn" -> "free.shadowsocks.proxy.VPN"
            "v2vpn" -> "free.v2ray.proxy.VPN"
            "v2free" -> "v2free.app"
            else -> ""
        }

    val activeProfileIds
        get() = ProfileManager.getProfile(DataStore.profileId).let {
            if (it == null) emptyList() else listOfNotNull(it.id, it.udpFallback)
        }
    val currentProfile: Pair<Profile, Profile?>?
        get() {
            if (DataStore.directBootAware) DirectBoot.getDeviceProfile()?.apply { return this }
            var theOne = ProfileManager.getProfile(DataStore.profileId)
            if (theOne == null) {
                theOne = ProfileManager.getRandomVPNServer()
                if (theOne != null) DataStore.profileId = theOne.id
            }
            return ProfileManager.expand(theOne ?: return null)
        }

    fun switchProfile(id: Long, canStop: Boolean): Profile {
        val oldProfileId = DataStore.profileId
        Log.e("oldProfileId", oldProfileId.toString())
        val result = ProfileManager.getProfile(id) ?: ProfileManager.createProfile()
        if (id == oldProfileId) return result

        val profileTypeChanged = result.profileType != ProfileManager.getProfile(oldProfileId)?.profileType

        if (!canStop)
            DataStore.profileId = result.id
        else if (canStop && profileTypeChanged) {
            stopService()
            while (BaseService.State.values()[DataStore.connection!!.service!!.state] != BaseService.State.Stopped) Thread.sleep(100)
            DataStore.profileId = result.id
            startService()
        } else if (canStop && !profileTypeChanged) {
            DataStore.profileId = result.id
            app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
        }

        Log.e("profileId", DataStore.profileId.toString())
        return result
    }

    //Import built-in subscription
    fun updateBuiltinServers() {
        GlobalScope.launch {
            if (applicationId!="v2free.app") {
/*                val builtinSubUrls = app.resources.getStringArray(R.array.builtinSubUrls)
                for (i in 0 until builtinSubUrls.size) {
                    val builtinSub =
                        SSRSubManager.createSSSub(builtinSubUrls[i], VpnEncrypt.vpnGroupName)
                    if (builtinSub != null) break
                }*/

                val builtinGlobalUrls = app.resources.getStringArray(R.array.builtinGlobalUrls)
                for (i in 0 until builtinGlobalUrls.size) {
                    val addSuccess =
                        SSRSubManager.addProfiles(builtinGlobalUrls[i], VpnEncrypt.vpnGroupName)
                    if (addSuccess) break
                }

                if (DataStore.is_get_free_servers) importFreeSubs()
            }
            try {
                app.startService(Intent(app, SubscriptionService::class.java))
            }
            catch (e:Throwable){
                e.printStackTrace()
            }
        }
    }

    /**
     * import free sub
     */
    fun importFreeSubs() {
        GlobalScope.launch {
            try {
                var freesuburl = app.resources.getStringArray(R.array.freesuburl)
                for (i in freesuburl.indices) {
                    var freeSub = SSRSubManager.createSSSub(freesuburl[i], VpnEncrypt.freesubGroupName)
                    if (freeSub != null) break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun init(app: Application, configureClass: KClass<out Any>) {
        this.app = app
        this.configureIntent = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), 0)
        }

        if (Build.VERSION.SDK_INT >= 24) {  // migrate old files
            deviceStorage.moveDatabaseFrom(app, Key.DB_PUBLIC)
            val old = Acl.getFile(Acl.CUSTOM_RULES, app)
            if (old.canRead()) {
                Acl.getFile(Acl.CUSTOM_RULES).writeText(old.readText())
                old.delete()
            }
        }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        Firebase.initialize(deviceStorage)  // multiple processes needs manual set-up
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (t == null) {
                    if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    FirebaseCrashlytics.getInstance().log("${"XXVDIWEF".getOrElse(priority) { 'X' }}/$tag: $message")
                } else {
                    if (priority >= Log.WARN || priority == Log.DEBUG) Log.println(priority, tag, message)
                    if (priority >= Log.INFO) FirebaseCrashlytics.getInstance().recordException(t)
                }
            }
        })
/*        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val config: Configuration = Configuration.Builder().build()
            WorkManager.initialize(app.applicationContext, config)
            UpdateCheck.enqueue() //google play Publishing, prohibiting self-renewal
        }*/

        // handle data restored/crash
        if (Build.VERSION.SDK_INT >= 24 && DataStore.directBootAware &&
                app.getSystemService<UserManager>()?.isUserUnlocked == true) DirectBoot.flushTrafficStats()
        if (DataStore.tcpFastOpen && !TcpFastOpen.sendEnabled) TcpFastOpen.enableTimeout()
        if (DataStore.publicStore.getLong(Key.assetUpdateTime, -1) != packageInfo.lastUpdateTime) {
            val assetManager = app.assets
            try {
                for (file in assetManager.list("acl")!!) assetManager.open("acl/$file").use { input ->
                    File(deviceStorage.noBackupFilesDir, file).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                printLog(e)
            }
            DataStore.publicStore.putLong(Key.assetUpdateTime, packageInfo.lastUpdateTime)
        }
        updateNotificationChannels()
    }

    fun updateNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
            notification.createNotificationChannels(listOf(
                    NotificationChannel("service-vpn", app.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW),   // #1355
                    NotificationChannel("service-v2vpn", app.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW),   // #1355
                    NotificationChannel("service-proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-v2proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-transproxy", app.getText(R.string.service_transproxy),
                            NotificationManager.IMPORTANCE_LOW),
                    SubscriptionService.notificationChannel))
            notification.deleteNotificationChannel("service-nat")   // NAT mode is gone for good
        }
    }

    fun getPackageInfo(packageName: String) = app.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)!!

    fun trySetPrimaryClip(clip: String) = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
        true
    } catch (e: RuntimeException) {
        printLog(e)
        false
    }

    fun startService() = ContextCompat.startForegroundService(app, Intent(app, ShadowsocksConnection.serviceClass))
    fun reloadService(oldProfileId: Long, connection: ShadowsocksConnection) {
        if (ProfileManager.getProfile(oldProfileId)?.profileType == ProfileManager.getProfile(DataStore.profileId)?.profileType)
            app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
        else {
            stopService()
            while (BaseService.State.values()[connection.service!!.state] != BaseService.State.Stopped) Thread.sleep(100)
            connection.binderDied()
            //while (connection.service==null)Thread.sleep(100)
            startService()
        }
    }

    fun reloadServiceForTest(oldProfileId: Long, connection: ShadowsocksConnection) {
        if (ProfileManager.getProfile(oldProfileId)?.profileType == ProfileManager.getProfile(DataStore.profileId)?.profileType) {
            Log.e("reloadServiceForTest", "reload")
            app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
        } else {
            Log.e("reloadServiceForTest", "restart")
            Log.e("reloadServiceForTest", "old:" + ProfileManager.getProfile(oldProfileId)?.formattedName + ",type:" + ProfileManager.getProfile(oldProfileId)?.profileType)
            Log.e("reloadServiceForTest", "cur:" + ProfileManager.getProfile(DataStore.profileId)?.formattedName + ",type:" + ProfileManager.getProfile(DataStore.profileId)?.profileType)
            stopService()
            //while (BaseService.State.values()[connection.service!!.state]!=BaseService.State.Stopped)Thread.sleep(100)
            while (tcping("127.0.0.1", DataStore.portProxy) > 0 || tcping("127.0.0.1", VpnEncrypt.HTTP_PROXY_PORT) > 0) Thread.sleep(100)
            //connection.binderDied()
            startServiceForTest()
        }
    }

    fun stopService() = app.sendBroadcast(Intent(Action.CLOSE).setPackage(app.packageName))
    fun startServiceForTest() {
        val currprofileType = ProfileManager.getProfile(DataStore.profileId)?.profileType
        if ("vmess" == currprofileType || "vless" == currprofileType) {
            Log.e("startServiceForTest", "start V2RayTestService")
            app.startService(Intent(app, V2RayTestService::class.java))
        } else {
            Log.e("startServiceForTest", "start SSProxyService")
            app.startService(Intent(app, ProxyTestService::class.java).putExtra("test", "go"))
        }
    }

    fun showMessage(msg: String) {
        var toast = Toast.makeText(app, msg, Toast.LENGTH_LONG)
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
    }

    fun alertMessage(msg: String, activity: Context) {
        try {
            if (activity == null || (activity as Activity).isFinishing) return
            val builder: AlertDialog.Builder? = activity.let {
                AlertDialog.Builder(activity)
            }
            builder?.setMessage(msg)?.setTitle("Alert")?.setPositiveButton("ok", DialogInterface.OnClickListener { _, _ ->
            })
            val dialog: AlertDialog? = builder?.create()
            dialog?.show()
        } catch (t: Throwable) {
        }
    }

    /**
     * tcping
     */
    fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (one != -1L)
                if (time == -1L || one < time) {
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
            socket.connect(socketAddress, 5000)
            val time = System.currentTimeMillis() - start
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e("testConnection2", e.toString())
        } catch (e: IOException) {
            Log.e("testConnection2", e.toString())
        } catch (e: Exception) {
            Log.e("testConnection2", e.toString())
        }
        return -1
    }
}
