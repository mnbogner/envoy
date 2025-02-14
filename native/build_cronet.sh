#!/bin/bash
# see https://chromium.googlesource.com/chromium/src/+/master/components/cronet/build_instructions.md

CHROMIUM_SRC_ROOT=${CHROMIUM_SRC_ROOT:-/root/chromium/src}
DEPOT_TOOLS_ROOT=${DEPOT_TOOLS_ROOT:-/root/depot_tools}
export PATH="$DEPOT_TOOLS_ROOT:$PATH"
BUILD_VARIANT=${1:-release}

set -euo pipefail

cd "$(dirname "$0")" || exit 1
PATCH_DIR="$(pwd)"

# build cronet only(without java jni)
cd "$CHROMIUM_SRC_ROOT" || exit 2
# patches are generated by `git ls-files -m | xargs git cl format`, `git ls-files -m | grep -F '.gn' | xargs gn format` and `git diff --patch-with-stat`.
source $CHROMIUM_SRC_ROOT/chrome/VERSION
CHROME_VERSION=$MAJOR.$MINOR.$BUILD.$PATCH
git clean -ffd
git checkout .

patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0001-Add-envoy_url-to-URLRequestContext.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0002-Add-envoy-scheme.patch"

# build cronet with jni and java api
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0003-Add-jni-and-android-interface.patch"
# with dns resolve
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0004-Add-host-map-rules-for-envoy-scheme.patch"
# disabled cipher suites
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0005-Add-disabled-cipher-suites-parameter.patch"
# as feature
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0006-Add-as-features-and-android-preferences.patch"

patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0007-Add-cmdline-switch.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0008-Set-host-header-for-http2.patch"
#patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0009-Add-setting-page-for-envoy-url.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0010-Disable-external-intent.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0011-Add-salt-parameter.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0012-Add-socks5-proxy-for-cronet.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0013-Add-jni-for-cronet-socks5-proxy.patch"
patch --fuzz=0 --no-backup-if-mismatch --forward --strip=1 --reject-file=- --force <"$PATCH_DIR/0014-Add-ss-service.patch"

# autoninja -C out/Default chrome_public_apk
gn gen out/Cronet-Desktop
autoninja -C out/Cronet-Desktop cronet # cronet_sample

for arch in arm arm64 x86 x64; do
    # arm_use_neon = false
    out_dir="$CHROMIUM_SRC_ROOT/out/Cronet-$arch-$BUILD_VARIANT"
    gn_args="--out_dir=$out_dir"
    if [[ $BUILD_VARIANT == release ]]; then
        gn_args="$gn_args --release"
    fi
    if [[ $arch == "x86" || $arch == "x64" ]]; then
        gn_args="$gn_args --x86"
    fi
    # shellcheck disable=SC2086
    "$CHROMIUM_SRC_ROOT/components/cronet/tools/cr_cronet.py" gn $gn_args
    if ! grep "target_cpu = \"$arch\"" "$out_dir/args.gn"; then
        echo "target_cpu = \"$arch\"" >>"$out_dir/args.gn"
    fi
    autoninja -C "$out_dir" cronet_package
    if [[ $arch != "arm" ]]; then
        cp -a "$out_dir/cronet/libs/"* "$out_dir/../Cronet-arm-$BUILD_VARIANT/cronet/libs/"
    fi
done
# gn gen out/Cronet --args='target_os="android" target_cpu="arm"'
# autoninja -C out/Cronet -t clean

# https://gn.googlesource.com/gn/+/master/docs/cross_compiles.md
find "out/Cronet-arm-$BUILD_VARIANT/cronet/libs/" -name "libcronet.*.so" -not -name "libcronet.$CHROME_VERSION.so" -delete
ls "out/Cronet-arm-$BUILD_VARIANT/cronet/"
file "out/Cronet-arm-$BUILD_VARIANT/cronet/libs"/*/*.so

cd "$PATCH_DIR" || exit 3
CRONET_OUTPUT_DIR="$CHROMIUM_SRC_ROOT/out/Cronet-arm-$BUILD_VARIANT/cronet" bash package_aar.sh "$BUILD_VARIANT"
