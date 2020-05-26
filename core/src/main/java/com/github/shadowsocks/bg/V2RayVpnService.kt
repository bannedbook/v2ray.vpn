package com.github.shadowsocks.bg
import SpeedUpVPN.VpnEncrypt
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import com.github.shadowsocks.BootReceiver
import com.github.shadowsocks.Core
import com.github.shadowsocks.Core.defaultDPreference
import com.github.shadowsocks.VpnRequestActivity
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.AppConfig
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.database.VmessBean
import com.github.shadowsocks.net.DefaultNetworkListener
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.*
import go.Seq
import kotlinx.coroutines.*
import libv2ray.Libv2ray
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import java.net.UnknownHostException

class V2RayVpnService : VpnService() , BaseService.Interface{
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }
    //for BaseService.Interface start
    override val data = BaseService.Data(this)
    override val tag: String get() = "SSV2Service"
    override fun createNotification(profileName: String): ServiceNotification =
            ServiceNotification(this, profileName, "service-v2vpn", true)
    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<VpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }
    /*
    private var active = false
    private var metered = false
    private var underlyingNetwork: Network? = null
        set(value) {
            field = value
            if (active && Build.VERSION.SDK_INT >= 22) setUnderlyingNetworks(underlyingNetworks)
        }
    private val underlyingNetworks get() =
            // clearing underlyingNetworks makes Android 9 consider the network to be metered
        if (Build.VERSION.SDK_INT == 28 && metered) null else underlyingNetwork?.let { arrayOf(it) }
    override suspend fun preInit() = DefaultNetworkListener.start(this) { underlyingNetwork = it }
    */
    //for BaseService.Interface stop

    private val v2rayPoint = Libv2ray.newV2RayPoint(V2RayCallback())
    private lateinit var configContent: String
    private lateinit var mInterface: ParcelFileDescriptor
    val fd: Int get() = mInterface.fd

    /**
        * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
        *
        * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
        * satisfies default network capabilities but only THE default network. Unfortunately we need to have
        * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
        *
        * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
        */
    private val defaultNetworkRequest by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build()
        } else {
            null
        }
    }


    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val defaultNetworkCallback by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    setUnderlyingNetworks(arrayOf(network))
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities?) {
                    // it's a good idea to refresh capabilities
                    setUnderlyingNetworks(arrayOf(network))
                }
                override fun onLost(network: Network) {
                    setUnderlyingNetworks(null)
                }
            }
        } else {
            null
        }
    }
    private var listeningForDefaultNetwork = false
    lateinit var activeProfile: Profile
    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        v2rayPoint.packageName = packagePath(applicationContext)
        Seq.setContext(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.v2rayVpn) {
            if (prepare(this) != null) {
                Log.e("prepare","prepare != null")
                startActivity(Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                data.connectingJob = GlobalScope.launch(Dispatchers.Main) {
                    try {
                        activeProfile = ProfileManager.getProfile(DataStore.profileId)!!
                        genStoreV2rayConfig()
                        startV2ray()
                    } catch (_: CancellationException) {
                        // if the job was cancelled, it is canceller's responsibility to call stopRunner
                    } catch (_: UnknownHostException) {
                        stopRunner(false, getString(R.string.invalid_server))
                    } catch (exc: Throwable) {
                        if (exc is BaseService.ExpectedException) exc.printStackTrace() else printLog(exc)
                        stopRunner(false, "${getString(R.string.service_failed)}: ${exc.readableMessage}")
                    } finally {
                        data.connectingJob = null
                    }
                }
                return START_NOT_STICKY
            }
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }
    fun profileToVmessBean(profile: Profile): VmessBean {
        var vmess = VmessBean()
        vmess.guid=profile.id.toString()
        vmess.remoteDns=profile.remoteDns
        vmess.address=profile.host
        vmess.alterId=profile.alterId
        vmess.headerType=profile.headerType
        vmess.id=profile.password
        vmess.network=profile.network
        vmess.path=profile.path
        vmess.port=profile.remotePort
        vmess.remarks= profile.name.toString()
        vmess.requestHost=profile.requestHost
        vmess.security=profile.method
        vmess.streamSecurity=profile.streamSecurity
        vmess.subid=profile.url_group
        vmess.testResult=profile.elapsed.toString()

        if(profile.route=="all")vmess.route="0"
        else if(profile.route=="bypass-lan")vmess.route="1"
        else if(profile.route=="bypass-china")vmess.route="2"
        else if(profile.route=="bypass-lan-china")vmess.route="3"
        else vmess.route="0"

        return vmess
    }
    /**
     * gen and store v2ray config file
     */
    fun genStoreV2rayConfig(): Boolean {
        try {
            val result = V2rayConfigUtil.getV2rayConfig(Core.app, profileToVmessBean(activeProfile))
            if (result.status) {
                defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG, result.content)
                defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_GUID, activeProfile.id.toString())
                defaultDPreference.setPrefString(AppConfig.PREF_CURR_CONFIG_NAME, activeProfile.name)
                return true
            } else {
                return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override fun onRevoke() {
        stopV2Ray()
    }

    override fun onLowMemory() {
        stopV2Ray()
        super.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
        //cancelNotification()
    }

    fun setup(parameters: String) {
        Log.e("setup","start...")
        //val prepare = prepare(this)
        //if (prepare != null) {
        //    return
        //}
        Log.e("setup","start1...")
        val builder = Builder().setConfigureIntent(Core.configureIntent(this))
        parameters.split(" ")
                .map { it.split(",") }
                .forEach {
                    when (it[0][0]) {
                        'm' -> builder.setMtu(java.lang.Short.parseShort(it[1]).toInt())
                        's' -> builder.addSearchDomain(it[1])
                        'a' -> builder.addAddress(it[1], Integer.parseInt(it[2]))
                        'r' -> builder.addRoute(it[1], Integer.parseInt(it[2]))
                        'd' -> builder.addDnsServer(it[1])
                    }
                }
        if (activeProfile.ipv6) builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        if(!VpnEncrypt.enableLocalDns) {
                    builder.addDnsServer(activeProfile.remoteDns)
        }

        builder.setSession(defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_NAME, ""))

        if (activeProfile.proxyApps) {
            val me = packageName
            activeProfile.individual.split('\n')
                    .filter { it != me }
                    .forEach {
                        try {
                            if (activeProfile.bypass) builder.addDisallowedApplication(it)
                            else builder.addAllowedApplication(it)
                        } catch (ex: PackageManager.NameNotFoundException) {
                            printLog(ex)
                        }
                    }
            if (!activeProfile.bypass) builder.addAllowedApplication(me)
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            listeningForDefaultNetwork = true
        }

        // Create a new interface using the builder and save the parameters.
        mInterface = builder.establish()?: throw NullConnectionException()
        Log.e("setup-mInterface",mInterface.toString())
        sendFd()
        Log.e("setup","end")
    }

    fun shutdown() {
        stopV2Ray(true)
    }

    fun sendFd() {
        val fd = mInterface.fileDescriptor
        val path = File(packagePath(applicationContext), "sock_path").absolutePath

        GlobalScope.launch {
            var tries = 0
            while (true) try {
                Thread.sleep(50L shl tries)
                Log.d(packageName, "sendFd tries: " + tries.toString())
                LocalSocket().use { localSocket ->
                    localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                    localSocket.setFileDescriptorsForSend(arrayOf(fd))
                    localSocket.outputStream.write(42)
                }
                break
            } catch (e: Exception) {
                Log.d(packageName, e.toString())
                if (tries > 5) break
                tries += 1
            }
        }
    }

    private fun startV2ray() {
        if (!v2rayPoint.isRunning) {
            val broadname="$packageName.SERVICE"
            Log.e("broadname",broadname)
            if (!data.closeReceiverRegistered) {
                registerReceiver(data.closeReceiver, IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                }, broadname, null)
                data.closeReceiverRegistered = true
            }
            data.notification = createNotification(activeProfile.formattedName)
            data.changeState(BaseService.State.Connecting)


            configContent = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG, "")
            v2rayPoint.configureFileContent = configContent
            v2rayPoint.enableLocalDNS = VpnEncrypt.enableLocalDns
            v2rayPoint.forwardIpv6 = activeProfile.ipv6
            v2rayPoint.domainName = defaultDPreference.getPrefString(AppConfig.PREF_CURR_CONFIG_DOMAIN, "")

            try {
                v2rayPoint.runLoop()
            } catch (e: Exception) {
                Log.d(packageName, e.toString())
            }

            if (v2rayPoint.isRunning) {
                data.changeState(BaseService.State.Connected)
            } else {
                //MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
                //cancelNotification()
            }
        }
    }
    override fun stopRunner(restart: Boolean , msg: String? ) {
        Log.e("v2stopRunner",msg.toString())
        if (data.state == BaseService.State.Stopping) return
        // channge the state
        data.changeState(BaseService.State.Stopping)
        GlobalScope.launch(Dispatchers.Main.immediate) {
            data.connectingJob?.cancelAndJoin() // ensure stop connecting first
            stopV2Ray()
            // clean up receivers
            val data = data
            if (data.closeReceiverRegistered) {
                unregisterReceiver(data.closeReceiver)
                data.closeReceiverRegistered = false
            }
            data.notification?.destroy()
            data.notification = null
            // change the state
            data.changeState(BaseService.State.Stopped, msg)

            // stop the service if nothing has bound to it
            if (restart) startRunner() else {
                BootReceiver.enabled = false
                stopSelf()
            }
        }
    }
    private fun stopV2Ray(isForced: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (listeningForDefaultNetwork) {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
                listeningForDefaultNetwork = false
            }
        }
        if (v2rayPoint.isRunning) {
            try {
                v2rayPoint.stopLoop()
            } catch (e: Exception) {
                Log.d(packageName, e.toString())
            }
        }

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()
            try {
                mInterface.close()
            } catch (ignored: Exception) {
            }

        }
    }

    private inner class V2RayCallback : V2RayVPNServiceSupportsSet {
        override fun shutdown(): Long {
            // called by go
            // shutdown the whole vpn service
            try {
                this@V2RayVpnService.shutdown()
                return 0
            } catch (e: Exception) {
                Log.d(packageName, e.toString())
                return -1
            }
        }

        override fun prepare(): Long {
            return 0
        }

        override fun protect(l: Long) = (if (this@V2RayVpnService.protect(l.toInt())) 0 else 1).toLong()

        override fun onEmitStatus(l: Long, s: String?): Long {
            //Logger.d(s)
            return 0
        }

        override fun setup(s: String): Long {
            //Logger.d(s)
            try {
                this@V2RayVpnService.setup(s)
                return 0
            } catch (e: Exception) {
                Log.e(packageName, e.toString(),e)
                return -1
            }
        }

        override fun sendFd(): Long {
            try {
                this@V2RayVpnService.sendFd()
            } catch (e: Exception) {
                Log.d(packageName, e.toString())
                return -1
            }
            return 0
        }
    }



}
