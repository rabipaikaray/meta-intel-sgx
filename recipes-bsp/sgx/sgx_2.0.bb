SUMMARY = "tool for SGX"
DEPENDS_append_class-target = "openssl isgx curl protobuf protobuf-native sgx-native"
DEPENDS_append_class-native = "ocaml-native"
DEPENDS_append_class-nativesdk = "openssl ocaml-native"
HOMEPAGE = "https://01.org/intel-softwareguard-extensions"

RDEPENDS_${PN}_class-target += " icls-client dal-jhi"

LICENSE = "BSD"

LIC_FILES_CHKSUM = "file://License.txt;md5=c7a6a2fa753b1403cdbc7f1d14e11f65"

SRC_URI = "git://github.com/intel/linux-sgx.git "

SRC_URI_append_class-native = "file://0001-sgx-native-removed-werror.patch"

SRC_URI_append_class-target = " file://0001-Yocto-patch-for-SGX-2.0.patch \
	file://0001-Sample-Code-patch.patch \
	file://aesmd.service \
        file://00021_sgx_target_build.patch \
           "

#SRCREV = "${AUTOREV}"
SRCREV = "1bcdf2ed21b7bc70d9f1f03362e3d6582be62448"

#PV = "2.1+git${SRCPV}"

S = "${WORKDIR}/git"

PACKAGES = "${PN} ${PN}-dev ${PN}-dbg ${PN}-staticdev"
FILES_${PN} = "${sbindir}/* ${sysconfdir}/* ${localstatedir}/opt/*  ${libdir}/* "
FILES_${PN}-dev = "/opt/intel/sgxsdk/*"
INSANE_SKIP_${PN} = "dev-deps libdir"
INSANE_SKIP_${PN}-dev = "staticdev"

# Non Debug build
EXTRA_OEMAKE_class-target = "'MODE=HW' 'psw'"
# Debug build
#EXTRA_OEMAKE_class-target = "'MODE=HW' 'psw' 'DEBUG=1'"
CXXFLAGS_append = " -std=c++0x"

PARALLEL_MAKE = ""

TARGET_CC_ARCH += "${LDFLAGS}"
do_configure_class-target() {
    # configure libunwind
    UNWIND_DIR=${S}/sdk/cpprt/linux/libunwind
    echo "****************************"
    echo "Configure libunwind. Path:"
    echo ${UNWIND_DIR}
    echo "****************************"
    (cd ${UNWIND_DIR} && autoreconf -v --install) || exit $?
    CFLAGS="${CFLAGS} -std=c99 -fno-builtin -DHAVE_SGX=1 -fPIC -DUNW_LOCAL_ONLY"
    find ${UNWIND_DIR} -name "configure" | xargs touch
    cd ${UNWIND_DIR} && ./configure --host=${TARGET_SYS} --build=${BUILD_SYS} \
        --enable-shared=no       \
        --disable-block-signals  \
        --enable-debug=no        \
        --enable-debug-frame=no  \
        --enable-cxx-exceptions
    
    #configure gperftools
    GPERFTOOLS_DIR=${S}/sdk/gperftools/gperftools-2.5
    echo "*****************************"
    echo "Configure gperftools. Path:"
    echo ${GPERFTOOLS_DIR}
    echo "*****************************"
#    (cd ${GPERFTOOLS_DIR} && autoreconf -v --install --force) || exit $?
    COMMON_FLAGS="-g -DNO_HEAP_CHECK -DTCMALLOC_SGX -DTCMALLOC_NO_ALIASES -fstack-protector"
    ENCLAVE_CFLAGS="${COMMON_FLAGS} -ffreestanding -nostdinc -fvisibility=hidden -fPIC"
    ENCLAVE_CXXFLAGS="${ENCLAVE_CFLAGS} -nostdinc++"
    CFLAGS="${CFLAGS} ${ENCLAVE_CFLAGS}"
    CXXFLAGS="${CXXFLAGS} ${ENCLAVE_CXXFLAGS}"
    CPPFLAGS="-I../../../common/inc -I../../../common/inc/tlibc -I../../../common/inc/internal/ -I../../../sdk/tlibstdcxx/stlport -I../../../sdk/trts/"
    export CFLAGS
    export CXXFLAGS
    export CPPFLAGS

    find ${GPERFTOOLS_DIR} -name "configure" | xargs touch
    cd ${GPERFTOOLS_DIR} && ./configure --host=${TARGET_SYS} --build=${BUILD_SYS} \
        --enable-shared=no \
        --disable-cpu-profiler \
        --disable-heap-profiler \
        --disable-heap-checker \
        --disable-debugalloc \
        --enable-minimal

    # configure rdrand
    RDRAND_DIR=${S}/external/rdrand/src
    echo "****************************"
    echo "Configure rdrand. Path:"
    echo ${RDRAND_DIR}
    echo "****************************"
    CFLAGS="${CFLAGS} -fPIC"
    cd ${RDRAND_DIR} && ./configure --host=${TARGET_SYS} --build=${BUILD_SYS}
}

do_compile_prepend() {
    if [ -n "${https_proxy}" ]; then
        export https_proxy=${https_proxy}
    fi
    ${B}/download_prebuilt.sh
}

do_compile_class-native() {
    oe_runmake -C sdk/sign_tool/SignTool
    oe_runmake -C sdk/edger8r/linux
}

do_compile_class-nativesdk() {
    oe_runmake -C sdk/sign_tool/SignTool
    oe_runmake -C sdk/edger8r/linux
}

do_install_class-native() {
    install -d ${D}${bindir}
    install -m 0755 ${B}/build/linux/sgx_sign ${D}${bindir}
    install -m 0755 ${B}/build/linux/sgx_edger8r ${D}${bindir}
}

do_install_class-nativesdk() {
    SGX_BUILD_DIR=${B}/build/linux

    install -d ${D}${bindir}
    install -m 0755 ${SGX_BUILD_DIR}/sgx_sign ${D}${bindir}
    install -m 0755 ${SGX_BUILD_DIR}/sgx_edger8r ${D}${bindir}
    
    cat > ${D}${bindir}/sgx-gdb << EOF
#!/usr/bin/env bash
#
#  Copyright (c) 2011-2017, Intel Corporation.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License
#

shopt -s expand_aliases

GDB_SGX_PLUGIN_PATH=\$SDKTARGETSYSROOT/opt/intel/sgxsdk/lib64/gdb-sgx-plugin
export PYTHONPATH=\$GDB_SGX_PLUGIN_PATH
\$GDB -iex "directory \$GDB_SGX_PLUGIN_PATH" -iex "source \$GDB_SGX_PLUGIN_PATH/gdb_sgx_plugin.py" -iex "set environment LD_PRELOAD" -iex "add-auto-load-safe-path /usr/lib" "\$@"
EOF

    chmod +x ${D}${bindir}/sgx-gdb
}

do_install_class-target() {
    SGX_BUILD_DIR=${B}/build/linux
   
# Install SDK
    SGX_SDK_DIR=${D}/opt/intel/sgxsdk

    install -d ${SGX_SDK_DIR}/bin
    install -d ${SGX_SDK_DIR}/include
    install -d ${SGX_SDK_DIR}/include/libcxx
    install -d ${SGX_SDK_DIR}/lib64/gdb-sgx-plugin
    install -d ${SGX_SDK_DIR}/license
    install -d ${SGX_SDK_DIR}/ptrace
    install -d ${SGX_SDK_DIR}/SampleCode

    install -m 0755 ${SGX_BUILD_DIR}/sgx_config_cpusvn              ${SGX_SDK_DIR}/bin/
    install -m 0755 ${SGX_BUILD_DIR}/sgx_sign                       ${SGX_SDK_DIR}/bin/
    install -m 0755 ${SGX_BUILD_DIR}/sgx_edger8r                    ${SGX_SDK_DIR}/bin/
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_ptrace.so               ${SGX_SDK_DIR}/lib64/libsgx_ptrace.so.2.0
    ln -s libsgx_ptrace.so.2.0 ${SGX_SDK_DIR}/lib64/libsgx_ptrace.so 
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_uae_service_deploy.so   ${SGX_SDK_DIR}/lib64/libsgx_uae_service.so.2.0
    ln -s libsgx_uae_service.so.2.0 ${SGX_SDK_DIR}/lib64/libsgx_uae_service.so 
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_urts_deploy.so          ${SGX_SDK_DIR}/lib64/libsgx_urts.so.2.0
    ln -s libsgx_urts.so.2.0 ${SGX_SDK_DIR}/lib64/libsgx_urts.so 
    install -m 0755 ${SGX_BUILD_DIR}/libsgx*.a                      ${SGX_SDK_DIR}/lib64/
    install -m 0755 ${SGX_BUILD_DIR}/gdb-sgx-plugin/*.py            ${SGX_SDK_DIR}/lib64/gdb-sgx-plugin/
    install -m 0644 ${SGX_BUILD_DIR}/gdb-sgx-plugin/gdb_sgx_cmd     ${SGX_SDK_DIR}/lib64/gdb-sgx-plugin/

    cp ${B}/common/inc/*.h                  ${SGX_SDK_DIR}/include
    cp ${B}/common/inc/*.edl                ${SGX_SDK_DIR}/include
    cp -r ${B}/common/inc/tlibc/            ${SGX_SDK_DIR}/include
    cp -r ${B}/common/inc/stdc++/           ${SGX_SDK_DIR}/include
    cp -r ${B}/sdk/tlibstdcxx/stlport/      ${SGX_SDK_DIR}/include
    cp -r ${B}/sdk/tlibcxx/include/*        ${SGX_SDK_DIR}/include/libcxx
    cp -r ${B}/SampleCode/SampleEnclave     ${SGX_SDK_DIR}/SampleCode/
    cp -r ${B}/SampleCode/PowerTransition   ${SGX_SDK_DIR}/SampleCode/
    cp -r ${B}/SampleCode/Cxx11SGXDemo      ${SGX_SDK_DIR}/SampleCode/
    cp -r ${B}/SampleCode/SealedData        ${SGX_SDK_DIR}/SampleCode/
    cp -r ${B}/SampleCode/LocalAttestation  ${SGX_SDK_DIR}/SampleCode/
    cp -r ${B}/SampleCode/RemoteAttestation ${SGX_SDK_DIR}/SampleCode/
    install -d ${SGX_SDK_DIR}/SampleCode/RemoteAttestation/sample_libcrypto
    install -m 0755 ${SGX_BUILD_DIR}/libsample_libcrypto.so ${SGX_SDK_DIR}/SampleCode/RemoteAttestation/sample_libcrypto/libsample_libcrypto.so.2.0
    ln -s libsample_libcrypto.so.2.0 ${SGX_SDK_DIR}/SampleCode/RemoteAttestation/sample_libcrypto/libsample_libcrypto.so
    cp ${B}/sdk/sample_libcrypto/sample_libcrypto.h ${SGX_SDK_DIR}/SampleCode/RemoteAttestation/sample_libcrypto/

    cp ${B}/common/src/se_memory.c          ${SGX_SDK_DIR}/ptrace/
    cp ${B}/common/src/se_trace.c           ${SGX_SDK_DIR}/ptrace/
    cp ${B}/sdk/debugger_interface/linux/se_ptrace.c  ${SGX_SDK_DIR}/ptrace/

    cp ${B}/License.txt      ${SGX_SDK_DIR}/license/

    cat > ${SGX_SDK_DIR}/bin/sgx-gdb << EOF
#!/usr/bin/env bash
#
#  Copyright (c) 2011-2017, Intel Corporation.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License
#
shopt -s expand_aliases

GDB_SGX_PLUGIN_PATH=/opt/intel/sgxsdk/lib64/gdb-sgx-plugin
SGX_LIBRARY_PATH=/opt/intel/sgxsdk/lib64/
export PYTHONPATH=\$GDB_SGX_PLUGIN_PATH
LD_PRELOAD=\$SGX_LIBRARY_PATH/libsgx_ptrace.so /usr/bin/gdb -iex "directory \$GDB_SGX_PLUGIN_PATH" -iex "source \$GDB_SGX_PLUGIN_PATH/gdb_sgx_plugin.py" -iex "set environment LD_PRELOAD" -iex "add-auto-load-safe-path /usr/lib" "\$@"
EOF

    chmod +x ${SGX_SDK_DIR}/bin/sgx-gdb

	cat > ${SGX_SDK_DIR}/environment <<EOF
export SGX_SDK=/opt/intel/sgxsdk/
export PATH=\$PATH:\$SGX_SDK/bin
EOF

# Install urts/uae_service to /usr/lib
    install -d ${D}${libdir}
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_urts.so ${D}${libdir}/libsgx_urts.so
#    ln -s libsgx_urts.so.2.0 ${D}${libdir}/libsgx_urts.so
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_uae_service.so ${D}${libdir}/libsgx_uae_service.so
#    ln -s libsgx_uae_service.so.2.0 ${D}${libdir}/libsgx_uae_service.so
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_urts_sim.so ${D}${libdir}/libsgx_urts_sim.so
#    ln -s libsgx_urts_sim.so.2.0 ${D}${libdir}/libsgx_urts_sim.so
    install -m 0755 ${SGX_BUILD_DIR}/libsgx_uae_service_sim.so ${D}${libdir}/libsgx_uae_service_sim.so
#    ln -s libsgx_uae_service_sim.so.2.0  ${D}${libdir}/libsgx_uae_service_sim.so

# Install AEs
    install -d ${D}${sbindir}/sgx
    install -m 0755 ${SGX_BUILD_DIR}/aesm_service ${D}${sbindir}/sgx
    install -m 0755 ${SGX_BUILD_DIR}/libsgx*.signed.so ${D}${sbindir}/sgx
    install -m 0644 ${SGX_BUILD_DIR}/le_prod_css.bin ${D}${sbindir}/sgx
    install -m 0644 ${SGX_BUILD_DIR}/PSDA.dalp ${D}${sbindir}/sgx
    install -m 0755 ${SGX_BUILD_DIR}/cse_provision_tool  ${D}${sbindir}/sgx
    install -d ${D}${localstatedir}/opt/aesmd/data 
    install -m 0644 ${B}/psw/ae/aesm_service/data/white_list_cert_to_be_verify.bin	 ${D}${localstatedir}/opt/aesmd/data
    install -m 0644 ${B}/psw/ae/aesm_service/data/prebuild_pse_vmc.db  ${D}${localstatedir}/opt/aesmd/data
    install -d ${D}${sysconfdir}
    install -m 0644 ${B}/psw/ae/aesm_service/config/network/aesmd.conf ${D}${sysconfdir}/
#    install -d ${D}/${sysconfdir}/init.d
#    install -m 0755 ${WORKDIR}/aesmd  ${D}/${sysconfdir}/init.d/aesmd
    install -d ${D}/${base_libdir}/systemd/system
    install -m 0644 ${WORKDIR}/aesmd.service  ${D}/${base_libdir}/systemd/system/

}

#sgx_sysroot_preprocess() {
#    SGX_SDK_DIR=${SYSROOT_DESTDIR}/opt/intel/sgxsdk
#    install -d ${SGX_SDK_DIR}/sdk_libs

#	ln -sf ${SGX_SDK_DIR}/lib64/libsgx_urts_sim.so 				${SGX_SDK_DIR}/sdk_libs/libsgx_urts_sim.so
#	ln -sf ${SGX_SDK_DIR}/lib64/libsgx_uae_service_sim.so 		${SGX_SDK_DIR}/sdk_libs/libsgx_uae_service_sim.so
#}

#SYSROOT_PREPROCESS_FUNCS_class-target += "sgx_sysroot_preprocess"
#SYSROOT_PREPROCESS_FUNCS += "sgx_sysroot_preprocess"


BBCLASSEXTEND =+ "native nativesdk"


# Avoid generated binaries stripping.
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT="1"


RDEPENDS_${PN} += "bash"

#inherit update-rc.d
#INITSCRIPT_NAME = "aesmd"
#INITSCRIPT_PARAMS = "defaults"
inherit systemd
SYSTEMD_SERVICE_${PN} = "aesmd.service"
