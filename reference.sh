#!/usr/bin/env bash
# Slices the test models with the official ElegooSlicer Linux release, to
# compare against bcslice. Writes everything to $HOME/out/ref/.
set -x
OUT=$HOME/out/ref; mkdir -p $OUT
gh api repos/ELEGOO-3D/ElegooSlicer/releases --jq '.[0:4][] | .tag_name + " " + ([.assets[].name] | join(", "))' > $OUT/releases.txt
URL=$(gh api repos/ELEGOO-3D/ElegooSlicer/releases --jq '[.[] | .assets[] | select((.name|test("(?i)linux")) and (.name|test("(?i)appimage$")))][0].browser_download_url')
echo "$URL" > $OUT/url.txt
[ -n "$URL" ] && [ "$URL" != "null" ] || { echo "no Linux AppImage"; exit 0; }
sudo apt-get install -y -qq xvfb libfuse2 libopengl0 libglu1-mesa libegl1 libgl1 libgtk-3-0 libwebkit2gtk-4.1-0 >/dev/null || true
cd $HOME && [ -d squashfs-root ] || { curl -fsSL -o es.AppImage "$URL" && chmod +x es.AppImage && ./es.AppImage --appimage-extract >/dev/null; }
EXE=$HOME/squashfs-root/AppRun
P=$HOME/presets-user; rm -rf $P; cp -r $HOME/presets $P
python3 - "$P" <<'PY'
import json, glob, sys
for f in glob.glob(sys.argv[1] + '/*/*.json'):
    if '/info/' in f:
        continue
    d = json.load(open(f))
    d['from'] = 'User'; d['inherits'] = d['name']; d['name'] = d['name'] + ' (BC)'
    if '/machine/' not in f:
        d['compatible_printers'] = ['Elegoo Centauri Carbon 2 0.4 nozzle']
    json.dump(d, open(f, 'w'))
PY
M="$P/machine/Elegoo Centauri Carbon 2 0.4 nozzle.json"
R="$P/process/0.20mm Standard @Elegoo CC2 0.4 nozzle.json"
F="$P/filament/Elegoo ASA-CF @ECC2.json"
for m in ElegooToleranceTest.stl Stanford_Bunny.3mf Elegoo_stringhell.3mf; do
  n=${m%.*}; mkdir -p $OUT/$n
  start=$(date +%s.%N)
  xvfb-run -a $EXE --slice 0 --load-settings "$M;$R" --load-filaments "$F" --allow-newer-file \
    --outputdir $OUT/$n "$HOME/es-src/resources/handy_models/$m" > $OUT/$n.log 2>&1
  echo "exit=$? seconds=$(echo "$(date +%s.%N) - $start" | bc)" >> $OUT/$n.log
  cat $OUT/$n/result.json 2>/dev/null >> $OUT/$n.log
  for g in $OUT/$n/*.gcode; do [ -f "$g" ] && grep -m6 -E "estimated printing time \(normal|total layer number|total filament weight|filament used \[g\]|max_z_height" "$g" >> $OUT/$n.log; done
done
gzip -9 $OUT/*/*.gcode 2>/dev/null || true

# Memory check on the official build (same model and settings as bcslice's check).
# Shows whether official ElegooSlicer also reads memory it never set.
sudo apt-get install -y -qq valgrind >/dev/null || true
mkdir -p $OUT/vg
timeout 2400 xvfb-run -a valgrind --tool=memcheck --trace-children=yes --track-origins=yes --num-callers=14 \
  --log-file=$OUT/vg/official.%p.log \
  $EXE --slice 0 --load-settings "$M;$R" --load-filaments "$F" --allow-newer-file \
  --outputdir $OUT/vg "$HOME/es-src/resources/handy_models/ElegooToleranceTest.stl" > $OUT/vg/run.log 2>&1
echo "valgrind exit=$?" >> $OUT/vg/run.log
for f in $OUT/vg/official.*.log; do
  n=$(grep -c "uninitialised" "$f")
  [ "$n" -gt 0 ] && { echo "== $f: $n"; awk '/uninitialised/{getline; print}' "$f" | sed -E 's/==[0-9]+==\s+at 0x[0-9A-F]+: //' | sort | uniq -c | sort -rn | head -25; } >> $OUT/vg/summary.txt
done
ls -la $OUT/vg >> $OUT/vg/run.log
