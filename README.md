## Shadowsocks VPN, A Android VPN base on Shadowsocks.

Compatible with Shadowsocks, based on shadowsocks-android, only added free built-in shadowsocks servers. If you are not satisfied with our built-in Shadowsocks vpn servers, you can add or import your own Shadowsocks server to use.

<p><a href="https://play.google.com/store/apps/details?id=free.shadowsocks.proxy.VPN"><img src="https://camo.githubusercontent.com/bdaf711a93d64d0bb5e5abfc346a8b84ea47f164/68747470733a2f2f706c61792e676f6f676c652e636f6d2f696e746c2f656e5f75732f6261646765732f696d616765732f67656e657269632f656e2d706c61792d62616467652e706e67" style="max-width:100%"></a>
</p>

### Feedback
<a target="_blank" href="https://github.com/bannedbook/ssvpn/issues">Issues</a>、<a target="_blank" href="https://t.me/fqchat">Telegram Group</a>


### PREREQUISITES

* JDK 1.8
* Android SDK
  - Android NDK

### BUILD

You can check whether the latest commit builds under UNIX environment by checking Travis status.

* Clone the repo using `git clone --recurse-submodules <repo>` or update submodules using `git submodule update --init --recursive`
* Build it using Android Studio or gradle script

### BUILD WITH DOCKER

```bash
mkdir build
sudo chown 3434:3434 build
docker run --rm -v ${PWD}/build:/build circleci/android:api-28-ndk bash -c "cd /build; git clone https://github.com/shadowsocks/shadowsocks-android; cd shadowsocks-android; git submodule update --init --recursive; ./gradlew assembleDebug"
```

### CONTRIBUTING

If you are interested in contributing or getting involved with this project, please read the CONTRIBUTING page for more information.  The page can be found [here](https://github.com/shadowsocks/shadowsocks-android/blob/master/CONTRIBUTING.md).


### [TRANSLATE](https://discourse.shadowsocks.org/t/poeditor-translation-main-thread/30)

## OPEN SOURCE LICENSES

<ul>
<li>shadowsocksRb-android: <a href="https://github.com/shadowsocksRb/shadowsocksRb-android/blob/master/LICENSE">GPLv3</a></li>
<li>Shadowsocks for Android: <a href="https://github.com/shadowsocks/shadowsocks-android/blob/master/LICENSE">GPLv3</a></li>
    <li>redsocks: <a href="https://github.com/shadowsocks/redsocks/blob/shadowsocks-android/README">APL 2.0</a></li>
    <li>mbed TLS: <a href="https://github.com/ARMmbed/mbedtls/blob/development/LICENSE">APL 2.0</a></li>
    <li>libevent: <a href="https://github.com/shadowsocks/libevent/blob/master/LICENSE">BSD</a></li>
    <li>tun2socks: <a href="https://github.com/shadowsocks/badvpn/blob/shadowsocks-android/COPYING">BSD</a></li>
    <li>pcre: <a href="https://android.googlesource.com/platform/external/pcre/+/master/dist2/LICENCE">BSD</a></li>
    <li>libancillary: <a href="https://github.com/shadowsocks/libancillary/blob/shadowsocks-android/COPYING">BSD</a></li>
    <li>shadowsocks-libev: <a href="https://github.com/shadowsocks/shadowsocks-libev/blob/master/LICENSE">GPLv3</a></li>
    <li>libev: <a href="https://github.com/shadowsocks/libev/blob/master/LICENSE">GPLv2</a></li>
    <li>libsodium: <a href="https://github.com/jedisct1/libsodium/blob/master/LICENSE">ISC</a></li>
</ul>

### LICENSE
Copyright (C) 2019 by [ssvpn](https://github.com/bannedbook/ssvpn) <br>
Copyright (C) 2017 by Max Lv <<max.c.lv@gmail.com>>  
Copyright (C) 2017 by Mygod Studio <<contact-shadowsocks-android@mygod.be>>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
