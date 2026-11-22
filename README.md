# Bear Cave Slicer Engine

The real ElegooSlicer slicing engine, built to run on Android phones and tablets.
It turns a 3MF or STL into G-code for the Elegoo Centauri Carbon 2 on the phone itself,
with no computer and no cloud. It is the slicer inside the **Bear Cave Printer** app.

By **Bear Cave Labs** (Design202, LLC), built with Claude.
Based on [ElegooSlicer](https://github.com/ELEGOO-3D/ElegooSlicer), which comes from
OrcaSlicer, Bambu Studio and PrusaSlicer. Not made by or affiliated with Elegoo.

## What's here

| File | What it does |
|---|---|
| `deps.sh` | Builds the libraries the engine needs (Boost, TBB, CGAL, OpenCV, OpenSSL and others) for Android arm64 or Linux, at the same versions ElegooSlicer uses |
| `patch.py` | Changes ElegooSlicer's build to an engine-only build (no desktop window, no 3D view) and applies the fixes listed below |
| `stubs/` | Stand-ins for the parts left out (STEP import, Draco, OpenVDB) |
| `build.sh` | Fetches ElegooSlicer at a fixed commit, patches it, and builds `bcslice` |
| `bcslice/` | The small program the app runs: load model, apply presets, slice, write G-code, report |
| `flatten.py` | Turns Elegoo's Centauri Carbon 2 presets into complete stand-alone files |
| `reference.sh` | Slices the same models with the official ElegooSlicer Linux build, to compare |
| `UPSTREAM_REPORT.md` | Bugs found in ElegooSlicer / OrcaSlicer while doing this, with fixes |

## Build

```
TARGET=android ./deps.sh && TARGET=android ./build.sh   # needs ANDROID_NDK_HOME (NDK r28 or newer)
TARGET=linux   ./deps.sh && TARGET=linux   ./build.sh
```

The program lands in `~/out/bcslice-android` or `~/out/bcslice-linux`.
`.github/workflows/build.yml` does both on GitHub and test-slices three models.

On Android the app ships `bcslice` as `lib/arm64-v8a/libbcslice.so` (kept unpacked) and runs it
from the app's native library folder. Android 9 (API 28) or newer, 64-bit ARM.

## Use

```
bcslice --machine printer.json --process print.json --filament filament.json \
        --resources presets/ [--plate N] [--bed "Textured PEI Plate"] [--threads N] \
        [--json report.json] [--preview picture.png] [--color "#RRGGBB"] \
        -o out.gcode model.3mf
```

- Progress lines go to standard output as `PROGRESS <percent> <step>`.
- `--json` writes time, grams, filament length, slicing seconds and peak memory.
- `--preview` writes a picture of the part. The G-code also carries a 144×144 picture for
  the printer's screen. Both are drawn in software, with light grain.
- Loose STL files are placed with ElegooSlicer's own arrange step, which avoids the printer's
  no-go corner.

## Changes from ElegooSlicer

- **Same G-code every run.**
  - The mesh is cut into layers on all cores, and the cut lines used to land in whatever
    order the cores finished. That order set the island print order, so the same model
    could come out different each run.
  - Each layer's lines are now sorted into a fixed order after that step.
  - Result: 1, 2 or 8 cores give byte-identical G-code.
- **Six values that were never given a starting value** (they read leftover memory):
  - plate origin
  - wipe-tower position
  - extruder E and retraction on shared-extruder printers
  - Arachne concentric-infill settings
  - a model-volume cache matrix
  - the paint timestamp
  - Details in `UPSTREAM_REPORT.md`.
- **One-core mode no longer hangs.** `--threads N` runs the slice inside a thread pool of
  that size.
- **Engine-only build:** no desktop window, 3D view, network plugin, STEP import or Draco.

Tested on a Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3):
- the Stanford Bunny slices in about 5–6 s using about 400 MB
- 40 of 40 repeat slices came out identical

## License

GNU Affero General Public License v3.0, the same as ElegooSlicer. See `LICENSE`.
Copyright © 2026 Design202, LLC (Bear Cave Labs) for the changes and the `bcslice` program;
ElegooSlicer and its sources keep their own copyrights.

## Bear Cave Slicer Checker

`checker/` is the free Android app that runs this engine on a phone or tablet and says whether it can slice. See `checker/README.md`.
