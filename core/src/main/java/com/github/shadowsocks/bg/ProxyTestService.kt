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

package com.github.shadowsocks.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.os.bundleOf
import com.github.shadowsocks.BootReceiver
import com.github.shadowsocks.Core
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.core.R
import com.github.shadowsocks.net.HostsFile
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Action
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.utils.readableMessage
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.*
import org.bannedbook.app.service.NativeCall
import com.github.shadowsocks.bg.BaseService.Interface
import java.io.*
import java.net.UnknownHostException

/**
 * Shadowsocks service at its minimum.
 */
class ProxyTestService : ProxyService(), Interface {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int{
        val data = data
        if (data.state != BaseService.State.Stopped) return Service.START_NOT_STICKY
        val profilePair = Core.currentProfile
        this as Context
        if (profilePair == null) {
            // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
            stopRunner(false, getString(R.string.profile_empty))
            return Service.START_NOT_STICKY
        }
        val (profile, fallback) = profilePair
        profile.name = profile.formattedName    // save name for later queries
        val proxy = ProxyInstance(profile)
        data.proxy = proxy
        data.udpFallback = if (fallback == null) null else ProxyInstance(fallback, profile.route)

        BootReceiver.enabled = DataStore.persistAcrossReboot
        if (!data.closeReceiverRegistered) {
            registerReceiver(data.closeReceiver, IntentFilter().apply {
                addAction(Action.RELOAD)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Action.CLOSE)
            }, "$packageName.SERVICE", null)
            data.closeReceiverRegistered = true
        }
        data.changeState(BaseService.State.Connecting)
        data.connectingJob = GlobalScope.launch(Dispatchers.Main) {
            try {
                Executable.killAll()    // clean up old processes
                preInit()
                val hosts = HostsFile(DataStore.publicStore.getString(Key.hosts) ?: "")
                proxy.init(this@ProxyTestService, hosts)
                data.udpFallback?.init(this@ProxyTestService, hosts)
                if (profile.route == Acl.CUSTOM_RULES) try {
                    withContext(Dispatchers.IO) {
                        Acl.customRules.flatten(10, this@ProxyTestService::openConnection).also {
                            Acl.save(Acl.CUSTOM_RULES, it)
                        }
                    }
                } catch (e: IOException) {
                    throw BaseService.ExpectedExceptionWrapper(e)
                }

                data.processes = GuardedProcessPool {
                    printLog(it)
                    stopRunner(false, it.readableMessage)
                }
                startProcesses(hosts)

                proxy.scheduleUpdate()
                data.udpFallback?.scheduleUpdate()

                data.changeState(BaseService.State.Connected)
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
        return Service.START_NOT_STICKY
    }

}
