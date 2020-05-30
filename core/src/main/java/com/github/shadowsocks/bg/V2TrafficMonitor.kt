package com.github.shadowsocks.bg

import com.github.shadowsocks.aidl.TrafficStats
import libv2ray.V2RayPoint
import rx.Observable
import java.io.File

class V2TrafficMonitor(statFile: File): TrafficMonitor(statFile)  {
    private var v2rayPoint: V2RayPoint? =null

    constructor(statFile: File,v2Point: V2RayPoint) : this(statFile) {
        this.v2rayPoint=v2Point
    }

    override fun requestUpdate(): Pair<TrafficStats, Boolean> {
        var updated = false
        if (v2rayPoint!!.isRunning ) {
            Observable.interval(3, java.util.concurrent.TimeUnit.SECONDS)
                    .subscribe {
                        val uplink  = v2rayPoint!!.queryStats("socks", "uplink")
                        val downlink = v2rayPoint!!.queryStats("socks", "downlink")
                        val zero_speed = (uplink == 0L && downlink == 0L)
                        if (!zero_speed ) {
                            current.txTotal+=uplink
                            current.txRate = uplink / 3
                            current.rxTotal+=downlink
                            current.rxRate = downlink / 3
                        }
                    }
        }
        if (current.txRate>0 || current.rxRate>0) updated = true
        return Pair(current, updated)
    }

}