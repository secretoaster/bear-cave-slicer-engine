# Bear Cave Slicer Checker

The first phone slicer benchmark for 3D printing. It runs the real ElegooSlicer engine (bcslice, built from this repo) on an Android phone or tablet, times three sample models, checks that repeat slices come out identical, and says whether the phone is fast enough to slice: Fast, OK or Slow.

Made by Bear Cave Labs (Design202, LLC). Free and open source under the GNU AGPL v3, like the engine it runs.

## Build

1. Build the engine for Android with `../build.sh` (see the main README). Copy the result to `app/src/main/jniLibs/arm64-v8a/libbcslice.so`.
2. Flatten the Elegoo presets from ElegooSlicer's `resources/profiles/Elegoo` into `app/src/main/assets/presets` with `python3 ../flatten.py <ElegooSlicer>/resources/profiles/Elegoo app/src/main/assets/presets`.
3. Copy `ElegooToleranceTest.stl`, `Stanford_Bunny.3mf` and `Elegoo_stringhell.3mf` from ElegooSlicer's `resources/handy_models` to `app/src/main/assets/models`.
4. `./gradlew assembleRelease`, then sign the APK with your own key.

The engine runs as its own program (`libbcslice.so` started with ProcessBuilder), so the APK is built with legacy JNI packaging so it is unpacked on install.
