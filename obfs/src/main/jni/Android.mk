# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
LOCAL_PATH := $(call my-dir)
ROOT_PATH := $(LOCAL_PATH)

BUILD_SHARED_EXECUTABLE := $(LOCAL_PATH)/build-shared-executable.mk

########################################################
## libcork
########################################################

include $(CLEAR_VARS)

cli_src := cli/commands.c
core_src := core/allocator.c core/error.c core/gc.c \
			core/hash.c core/ip-address.c core/mempool.c \
			core/timestamp.c core/u128.c
ds_src := ds/array.c ds/bitset.c ds/buffer.c ds/dllist.c \
		  ds/file-stream.c ds/hash-table.c ds/managed-buffer.c \
		  ds/ring-buffer.c ds/slice.c
posix_src := posix/directory-walker.c posix/env.c posix/exec.c \
			 posix/files.c posix/process.c posix/subprocess.c
pthreads_src := pthreads/thread.c

CORK_SOURCE := $(cli_src) $(core_src) $(ds_src) $(posix_src) $(pthreads_src)

LOCAL_MODULE := libcork
LOCAL_CFLAGS += -O2 -I$(LOCAL_PATH)/simple-obfs/libcork/include \
				-DCORK_API=CORK_LOCAL

LOCAL_SRC_FILES := $(addprefix simple-obfs/libcork/src/libcork/,$(CORK_SOURCE))

include $(BUILD_STATIC_LIBRARY)

########################################################
## libev
########################################################

include $(CLEAR_VARS)

LOCAL_MODULE := libev
LOCAL_CFLAGS += -O2 -DNDEBUG -DHAVE_CONFIG_H \
				-I$(LOCAL_PATH)/include/libev
LOCAL_SRC_FILES := \
	libev/ev.c \
	libev/event.c

include $(BUILD_STATIC_LIBRARY)

########################################################
## libancillary
########################################################

include $(CLEAR_VARS)

ANCILLARY_SOURCE := fd_recv.c fd_send.c

LOCAL_MODULE := libancillary
LOCAL_CFLAGS += -O2 -I$(LOCAL_PATH)/libancillary

LOCAL_SRC_FILES := $(addprefix libancillary/, $(ANCILLARY_SOURCE))

include $(BUILD_STATIC_LIBRARY)

########################################################
## simple-obfs local
########################################################

include $(CLEAR_VARS)

OBFS_SOURCES := utils.c jconf.c json.c encrypt.c netutils.c local.c obfs_http.c obfs_tls.c options.c base64.c android.c

LOCAL_MODULE    := obfs-local
LOCAL_SRC_FILES := $(addprefix simple-obfs/src/, $(OBFS_SOURCES))
LOCAL_CFLAGS    := -Wall -O2 -fno-strict-aliasing -DMODULE_LOCAL \
                    -DANDROID -DHAVE_CONFIG_H \
                    -DCONNECT_IN_PROGRESS=EINPROGRESS \
                    -I$(LOCAL_PATH)/include \
					-I$(LOCAL_PATH)/libancillary \
					-I$(LOCAL_PATH)/simple-obfs/libcork/include \
					-I$(LOCAL_PATH)/libev \
					-I$(LOCAL_PATH)/include/simple-obfs

LOCAL_STATIC_LIBRARIES := libev libcork libancillary

LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_EXECUTABLE)
