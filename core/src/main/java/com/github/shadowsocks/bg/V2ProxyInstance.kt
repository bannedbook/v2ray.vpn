package com.github.shadowsocks.bg

import com.github.shadowsocks.Core
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.net.HostsFile
import kotlinx.coroutines.CoroutineScope
import libv2ray.V2RayPoint
import java.io.File

class V2ProxyInstance(override val profile: Profile, private val route: String = profile.route) : ProxyInstance(profile,route) {
    private var v2rayPoint: V2RayPoint? =null
    constructor(v2Point: V2RayPoint,profile: Profile, route: String) : this(profile,route) {
        this.v2rayPoint=v2Point
        trafficMonitor = V2TrafficMonitor(File(Core.deviceStorage.noBackupFilesDir, "stat_main"),v2rayPoint!!)
    }

    override suspend fun init(service: BaseService.Interface, hosts: HostsFile) {
    }
    override fun start(service: BaseService.Interface, stat: File, configFile: File, extraFlag: String?) {
    }
    override fun scheduleUpdate() {
    }
    override fun shutdown(scope: CoroutineScope) {
        trafficMonitor?.apply {
            thread.shutdown(scope)
            persistStats(profile.id)    // Make sure update total traffic when stopping the runner
        }
        trafficMonitor = null
    }
}