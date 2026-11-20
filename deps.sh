#!/usr/bin/env bash
# Builds the libraries the ElegooSlicer engine needs, same versions as
# ElegooSlicer's own deps/ folder. One folder per library, with a "done"
# marker so a re-run only builds what is missing (the folder is cached).
#
#   TARGET=android ./deps.sh   (needs ANDROID_NDK_HOME)
#   TARGET=linux   ./deps.sh
set -euo pipefail

TARGET=${TARGET:-android}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HOME/bc-deps-$TARGET}
PREFIX=${PREFIX:-$WORK/prefix}
DL=$WORK/dl
JOBS=${JOBS:-$(nproc)}
API=${API:-28}
mkdir -p "$PREFIX" "$DL" "$WORK/src" "$PREFIX/.done"

CMAKE_COMMON=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DCMAKE_PREFIX_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DBUILD_SHARED_LIBS=OFF
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
if [ "$TARGET" = android ]; then
  : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
  TC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
  CMAKE_COMMON+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_static -DANDROID_DISABLE_FORMAT_STRING_CHECKS=ON
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH)
  export CC=$TC/bin/aarch64-linux-android$API-clang CXX=$TC/bin/aarch64-linux-android$API-clang++
  export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip
  HOST=aarch64-linux-android
else
  HOST=""
fi

done_() { [ -f "$PREFIX/.done/$1" ]; }
mark() { touch "$PREFIX/.done/$1"; echo "=== $1 done"; }

# fetch NAME URL SHA256 -> unpacks into $WORK/src/NAME (prints the source dir)
fetch() {
  local name=$1 url=$2 sum=$3 f="$DL/$(basename "$2")"
  [ -f "$f" ] || curl -fsSL --retry 4 -o "$f" "$url"
  echo "$sum  $f" | sha256sum -c - >/dev/null || { echo "bad checksum for $f"; exit 1; }
  rm -rf "$WORK/src/$name" && mkdir -p "$WORK/src/$name"
  case "$f" in
    *.zip) unzip -q "$f" -d "$WORK/src/$name" ;;
    *) tar -xf "$f" -C "$WORK/src/$name" ;;
  esac
  local d; d=$(find "$WORK/src/$name" -mindepth 1 -maxdepth 1 -type d | head -1)
  echo "$d"
}

cmk() { # cmk NAME SRC_DIR [cmake args...]
  local name=$1 src=$2; shift 2
  local b="$WORK/build/$name"; rm -rf "$b"
  cmake -S "$src" -B "$b" "${CMAKE_COMMON[@]}" "$@"
  cmake --build "$b" -j "$JOBS"
  cmake --install "$b"
}

step() { echo; echo "##### $1"; }

# ---------------------------------------------------------------- zlib
# Android: the NDK already has zlib. Linux: use the system one.

if [ "$TARGET" = android ] && ! done_ EXPAT; then step EXPAT
  s=$(fetch EXPAT https://github.com/libexpat/libexpat/releases/download/R_2_6_4/expat-2.6.4.tar.gz fd03b7172b3bd7427a3e7a812063f74754f24542429b634e0db6511b53fb2278)
  cmk EXPAT "$s" -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_DOCS=OFF -DEXPAT_SHARED_LIBS=OFF
  mark EXPAT; fi

if ! done_ PNG; then step PNG
  s=$(fetch PNG https://github.com/glennrp/libpng/archive/refs/tags/v1.6.35.zip 3d22d46c566b1761a0e15ea397589b3a5f36ac09b7c785382e6470156c04247f)
  cmk PNG "$s" -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_PREFIX=prusaslicer_ -DPNG_TESTS=OFF -DPNG_ARM_NEON=off -DDISABLE_DEPENDENCY_TRACKING=OFF
  mark PNG; fi

if ! done_ JPEG; then step JPEG
  s=$(fetch JPEG https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.1.zip d6d99e693366bc03897677650e8b2dfa76b5d6c54e2c9e70c03f0af821b0a52f)
  cmk JPEG "$s" -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_JPEG8=ON -DWITH_SIMD=OFF -DWITH_TURBOJPEG=OFF
  mark JPEG; fi

if ! done_ Boost; then step Boost
  s=$(fetch Boost https://github.com/boostorg/boost/releases/download/boost-1.84.0/boost-1.84.0.tar.gz 4d27e9efed0f6f152dc28db6430b9d3dfb40c0345da7342eaa5a987dde57bd95)
  cmk Boost "$s" "-DBOOST_EXCLUDE_LIBRARIES=contract;fiber;numpy;stacktrace;wave;test;python;mpi;graph_parallel;cobalt" \
    -DBOOST_LOCALE_ENABLE_ICU=OFF -DBOOST_LOCALE_ENABLE_ICONV=ON -DBUILD_TESTING=OFF \
    -DBOOST_IOSTREAMS_ENABLE_BZIP2=OFF -DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF -DBOOST_IOSTREAMS_ENABLE_LZMA=OFF
  mark Boost; fi

if ! done_ Cereal; then step Cereal
  s=$(fetch Cereal https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.0.zip 71642cb54658e98c8f07a0f0d08bf9766f1c3771496936f6014169d3726d9657)
  cmk Cereal "$s" -DJUST_INSTALL_CEREAL=ON -DSKIP_PERFORMANCE_COMPARISON=ON -DBUILD_TESTS=OFF
  mark Cereal; fi

if ! done_ Qhull; then step Qhull
  s=$(fetch Qhull https://github.com/qhull/qhull/archive/v8.0.2.zip a378e9a39e718e289102c20d45632f873bfdc58a7a5f924246ea4b176e185f1e)
  cmk Qhull "$s" -DINCLUDE_INSTALL_DIR=include -DBUILD_APPLICATIONS=OFF
  mark Qhull; fi

if ! done_ TBB; then step TBB
  s=$(fetch TBB https://github.com/oneapi-src/oneTBB/archive/refs/tags/v2021.5.0.zip 83ea786c964a384dd72534f9854b419716f412f9d43c0be88d41874763e7bb47)
  cmk TBB "$s" -DTBB_BUILD_SHARED=OFF -DTBB_BUILD_TESTS=OFF -DTBB_TEST=OFF -DTBB_STRICT=OFF -DCMAKE_DEBUG_POSTFIX=_debug
  mark TBB; fi

autotools() { # autotools NAME SRC [configure args...]
  local name=$1 src=$2; shift 2
  ( cd "$src"
    local hostarg=(); [ -n "$HOST" ] && hostarg=(--host=$HOST)
    CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC" ./configure "${hostarg[@]}" --prefix="$PREFIX" --enable-static --disable-shared "$@"
    make -j"$JOBS" && make install )
}

if ! done_ GMP; then step GMP
  s=$(fetch GMP https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.2.1/gmp-6.2.1.tar.bz2 eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c)
  autotools GMP "$s" --enable-cxx
  mark GMP; fi

if ! done_ MPFR; then step MPFR
  s=$(fetch MPFR https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2 9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b)
  autotools MPFR "$s" --with-gmp="$PREFIX"
  mark MPFR; fi

if ! done_ Eigen; then step Eigen
  s=$(fetch Eigen https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.zip 0dbb1f9e3aaad66f352c03227d8c983f6f0b49e0b07e71a7300f4abcc01aee12)
  cmk Eigen "$s" -DBUILD_TESTING=OFF -DEIGEN_BUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF -DEIGEN_BUILD_BLAS=OFF -DEIGEN_BUILD_LAPACK=OFF
  mark Eigen; fi

if ! done_ CGAL; then step CGAL
  s=$(fetch CGAL https://github.com/CGAL/cgal/releases/download/v5.6.3/CGAL-5.6.3.zip 5d577acb4a9918ccb960491482da7a3838f8d363aff47e14d703f19fd84733d4)
  cmk CGAL "$s"
  mark CGAL; fi

if ! done_ NLopt2; then step NLopt
  s=$(fetch NLopt https://github.com/stevengj/nlopt/archive/v2.5.0.tar.gz c6dd7a5701fff8ad5ebb45a3dc8e757e61d52658de3918e38bab233e7fd3b4ae)
  cmk NLopt "$s" -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF -DNLOPT_SWIG=OFF -DNLOPT_TESTS=OFF
  mark NLopt2; fi

if ! done_ libnoise; then step libnoise
  s=$(fetch libnoise https://github.com/SoftFever/Orca-deps-libnoise/archive/refs/tags/1.0.zip 96ffd6cc47898dd8147aab53d7d1b1911b507d9dbaecd5613ca2649468afd8b6)
  cmk libnoise "$s"
  mark libnoise; fi

if ! done_ OpenSSL; then step OpenSSL
  s=$(fetch OpenSSL https://github.com/openssl/openssl/archive/refs/tags/openssl-3.1.8.zip bbd5cbd8cc8ea852d31c001a9b767eadef0548b098e132b580a1f0c80d1778b7)
  ( cd "$s"
    if [ "$TARGET" = android ]; then
      export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME PATH=$TC/bin:$PATH
      ./Configure android-arm64 -D__ANDROID_API__=$API no-shared no-tests --prefix="$PREFIX" --openssldir="$PREFIX/ssl" --libdir=lib
    else
      ./config no-shared no-tests --prefix="$PREFIX" --openssldir="$PREFIX/ssl" --libdir=lib
    fi
    make -j"$JOBS" build_libs && make install_dev )
  mark OpenSSL; fi

if ! done_ OpenCV2; then step OpenCV
  s=$(fetch OpenCV https://github.com/opencv/opencv/archive/refs/tags/4.6.0.tar.gz 1ec1cba65f9f20fe5a41fda1586e01c70ea0c9a6d7b67c9e13edf0cfe2239277)
  cmk OpenCV "$s" -DBUILD_LIST=core,imgproc -DBUILD_SHARED_LIBS=OFF -DBUILD_opencv_world=OFF -DBUILD_opencv_python3=OFF -DBUILD_opencv_python2=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF \
    -DBUILD_EXAMPLES=OFF -DBUILD_opencv_apps=OFF -DBUILD_JAVA=OFF -DBUILD_opencv_java=OFF -DBUILD_ANDROID_PROJECTS=OFF \
    -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_ZLIB=OFF -DBUILD_TBB=OFF -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_OPENCL=OFF \
    -DWITH_EIGEN=OFF -DWITH_TBB=OFF -DWITH_OPENMP=OFF -DWITH_PROTOBUF=OFF -DWITH_ADE=OFF -DWITH_FFMPEG=OFF -DWITH_GTK=OFF \
    -DWITH_PNG=OFF -DWITH_JPEG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENEXR=OFF -DWITH_JASPER=OFF -DWITH_OPENJPEG=OFF -DWITH_QUIRC=OFF -DBUILD_opencv_objdetect=OFF \
    -DOPENCV_GENERATE_PKGCONFIG=OFF -DCV_TRACE=OFF -DBUILD_ANDROID_SERVICE=OFF -DINSTALL_CREATE_DISTRIB=OFF
  mark OpenCV2; fi

echo; echo "All libraries are in $PREFIX"; ls "$PREFIX/.done"
