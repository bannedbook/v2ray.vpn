#!/bin/bash

set -e

export ANDROID_HOME="/root/java/android-sdk"
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk-bundle"

export GOROOT="/usr/local/go"
export GOBIN="$GOROOT/bin"
export GOPATH="/root/go"
export GO111MODULE=off
export GOPROXY=direct

export PATH=${PATH}:${GOBIN}:${GOPATH}/bin:${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/platform-tools

# change dir
cd ${GOPATH}/src/AndroidLibV2rayLite

download_data=0
update_go_dep=0

for param in "$@"; do
  case $param in
  data*)
    download_data=1
    ;;
  dep*)
    update_go_dep=1
    ;;
  esac
done

echo "Update go dep......"
if [[ ${update_go_dep} == "1" ]] ; then
  # download dep
  go get -u github.com/golang/protobuf/protoc-gen-go/...
  go get -u golang.org/x/mobile/cmd/...
  go get -u github.com/jteeuwen/go-bindata/...
  go get -u -insecure v2ray.com/core/...

  #go get AndroidLibV2rayLite
fi


if [[ ${download_data} == "1" ]] ; then
  echo "Download geo data....."
  /bin/bash gen_assets.sh download
fi

cd shippedBinarys && make shippedBinary && cd ..

echo "compile aar"
gomobile init && gomobile bind -v  -tags json .
