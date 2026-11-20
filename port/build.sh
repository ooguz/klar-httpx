#!/usr/bin/env bash
# Build Berrak on current Firefox Focus.
#
# Fetches Mozilla's tree at the commit pinned in port/UPSTREAM (shallow, about
# 6 GB checked out), applies port/patches, and builds the klar flavour in mach
# artifact mode: Gecko comes prebuilt from Mozilla's CI, the app is built here.
#
#   port/build.sh                       # release APKs per ABI + App Bundle
#   BERRAK_ABI=x86_64 port/build.sh :app:assembleKlarDebug   # one ABI only
#
# By default the objdir is a "fat" one: mach fetches the GeckoView of each
# Play ABI (arm64-v8a, armeabi-v7a, x86_64) from Mozilla's CI and merges them
# (MOZ_ANDROID_FAT_AAR_ARCHITECTURES), so one Gradle run writes a working
# APK per ABI and a second one the App Bundle with all three.
#
# Environment:
#   BERRAK_FF_DIR   work tree (default: port/work, git-ignored). The script
#                   only resets a tree it created itself (marker .berrak-port).
#   BERRAK_ABI      all (default), x86_64, arm64-v8a, armeabi-v7a or x86;
#                   a single ABI builds faster but only its own APK works
#   BERRAK_VERSION  versionName for release builds (default: from git describe)
#   BERRAK_KEYSTORE_PROPERTIES  release signing (see app/build.gradle)
#   BERRAK_GRADLE_MEMORY  cgroup cap for the Gradle step (default 10G)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=UPSTREAM
. "$HERE/UPSTREAM"
WORK="${BERRAK_FF_DIR:-$HERE/work}"
ABI="${BERRAK_ABI:-all}"
FAT=""
case "$ABI" in
  all) TARGET=aarch64-linux-android; FAT=armeabi-v7a,arm64-v8a,x86_64 ;;
  x86_64) TARGET=x86_64-linux-android ;;
  arm64-v8a) TARGET=aarch64-linux-android ;;
  armeabi-v7a) TARGET=arm-linux-androideabi ;;
  x86) TARGET=i686-linux-android ;;
  *) echo "unknown BERRAK_ABI: $ABI" >&2; exit 1 ;;
esac

# 1. Mozilla's tree at the pinned commit, with the patches on top.
if [ -d "$WORK/.git" ] && [ ! -f "$WORK/.berrak-port" ]; then
  echo "$WORK is a git tree this script did not create; refusing to reset it." >&2
  exit 1
fi
if [ ! -d "$WORK/.git" ]; then
  git init -q "$WORK"
  git -C "$WORK" remote add origin "$UPSTREAM_REPO"
  touch "$WORK/.berrak-port"
fi
if ! git -C "$WORK" cat-file -e "$UPSTREAM_COMMIT^{commit}" 2>/dev/null; then
  git -C "$WORK" fetch --depth 1 origin "$UPSTREAM_COMMIT"
fi
git -C "$WORK" am --abort >/dev/null 2>&1 || true
git -C "$WORK" checkout -q -f -B berrak "$UPSTREAM_COMMIT"
git -C "$WORK" reset -q --hard "$UPSTREAM_COMMIT"
git -C "$WORK" -c user.name="Berrak port" -c user.email="berrak-port@localhost" \
  am -q --keep-cr "$HERE"/patches/*.patch
echo "Applied $(ls "$HERE"/patches/*.patch | wc -l) patches onto $UPSTREAM_VERSION ($UPSTREAM_COMMIT)"

# 2. Toolchain (~/.mozbuild) and the artifact build of Gecko for this ABI.
cd "$WORK"
cat > mozconfig <<MOZ
ac_add_options --enable-project=mobile/android
ac_add_options --enable-artifact-builds
ac_add_options --target=$TARGET
mk_add_options MOZ_OBJDIR=./objdir-$ABI
MOZ
if [ -n "$FAT" ]; then
  echo "export MOZ_ANDROID_FAT_AAR_ARCHITECTURES=$FAT" >> mozconfig
fi
# Not --no-system-changes: that also skips the Android SDK.
./mach --no-interactive bootstrap --application-choice mobile_android_artifact_mode
./mach build

# 3. The app. Upstream's Gradle settings (7 GB heap, a worker per core, a
# separate Kotlin daemon) can exhaust a laptop; cap and slow it down.
VERSION="${BERRAK_VERSION:-$(git -C "$HERE" describe --tags --always 2>/dev/null | sed 's/^v//')}"
gradle() {
  local cmd=(./mach gradle -p mobile/android/focus-android
    --max-workers=3 -Dorg.gradle.parallel=false
    "-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1536m -XX:+UseParallelGC"
    -Pkotlin.compiler.execution.strategy=in-process
    -PversionName="$VERSION" "$@")
  if command -v systemd-run >/dev/null && systemd-run --user --scope --quiet true 2>/dev/null; then
    systemd-run --user --scope --quiet -p MemoryMax="${BERRAK_GRADLE_MEMORY:-10G}" \
      -p MemorySwapMax=2G nice -n 10 "${cmd[@]}"
  else
    nice -n 10 "${cmd[@]}"
  fi
}
if [ $# -gt 0 ]; then
  gradle "$@"
else
  # Two runs: with a bundle task in the same run, the APK comes out universal.
  gradle :app:assembleKlarRelease
  gradle :app:bundleKlarRelease
fi
./mach gradle -p mobile/android/focus-android --stop >/dev/null 2>&1 || true

echo "Outputs: $WORK/objdir-$ABI/gradle/build/mobile/android/focus-android/app/outputs/"
