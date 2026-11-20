#!/usr/bin/env bash
# Builds bcslice (the ElegooSlicer engine + Bear Cave's small program).
#   TARGET=android|linux ./build.sh
# Needs the libraries from deps.sh first.
set -euo pipefail
TARGET=${TARGET:-android}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HOME/bc-deps-$TARGET}
PREFIX=$WORK/prefix
ES_REF=${ES_REF:-2d507e39a96ab9562b35d06b23ef5df8fe613297}
ES=$HOME/es-src
API=${API:-28}

if [ ! -d "$ES/.git" ]; then
  git init -q "$ES" && git -C "$ES" remote add origin https://github.com/ELEGOO-3D/ElegooSlicer.git
  git -C "$ES" fetch -q --depth 1 origin "$ES_REF" && git -C "$ES" checkout -q FETCH_HEAD
  python3 "$HERE/patch.py" "$ES"
fi

ARGS=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DBC_ENGINE_ONLY=ON -DBC_DIR="$HERE/bcslice"
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DELEGOO_INTERNAL_TESTING=0 -DBBL_INTERNAL_TESTING=0 -DSLIC3R_PCH=OFF -DCMAKE_PREFIX_PATH="$PREFIX"
  -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DBUILD_TESTS=OFF)
if [ "$TARGET" = android ]; then
  ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_static -DANDROID_DISABLE_FORMAT_STRING_CHECKS=ON
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH)
  # Android's C++ library (libc++) doesn't pull in headers the way GCC's does;
  # the engine leans on those, so include the common ones everywhere.
  ARGS+=("-DCMAKE_CXX_FLAGS=-include sstream -include iomanip -include numeric -include algorithm -include cstring -include array -include functional -include optional -include iostream -include cmath -include limits")
  # OpenCV's Android install puts its CMake files under sdk/native/jni
  [ -d "$PREFIX/sdk/native/jni" ] && ARGS+=(-DOpenCV_DIR="$PREFIX/sdk/native/jni")
fi
B=$HOME/es-build-$TARGET
cmake -S "$ES" -B "$B" "${ARGS[@]}"
cmake --build "$B" --target bcslice -j "${JOBS:-$(nproc)}" -- -k 0
mkdir -p "$HOME/out"
cp "$B/src/bcslice/bcslice" "$HOME/out/bcslice-$TARGET"
echo "built $HOME/out/bcslice-$TARGET"; ls -la "$HOME/out"
