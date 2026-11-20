#!/usr/bin/env python3
"""Turns Elegoo's CC2 system presets into complete ("flattened") preset files.

ElegooSlicer presets inherit from parents ("inherits"); bcslice wants one
file with every setting. Same idea as flatten() in the desktop Bear Cave.

Usage: flatten.py <ElegooSlicer>/resources/profiles/Elegoo <out dir>
Writes out/machine/*.json, out/process/*.json, out/filament/*.json for the
CC2 0.4 nozzle, plus out/index.json listing them.
"""
import json, os, sys

SRC, OUT = sys.argv[1], sys.argv[2]


def load_all(kind):
    found = {}
    for root, _, files in os.walk(os.path.join(SRC, kind)):
        for f in files:
            if f.endswith(".json"):
                p = os.path.join(root, f)
                try:
                    d = json.load(open(p, encoding="utf-8"))
                except Exception:
                    continue
                if "name" in d:
                    found[d["name"]] = d
    return found


def flat(name, table, seen=()):
    d = table[name]
    parent = d.get("inherits")
    base = flat(parent, table, seen + (name,)) if parent and parent in table and parent not in seen else {}
    out = dict(base)
    out.update(d)
    return out


index = {}
for kind in ("machine", "process", "filament"):
    table = load_all(kind)
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    picked = []
    for name, d in table.items():
        if d.get("instantiation") != "true":
            continue
        if kind == "machine" and name != "Elegoo Centauri Carbon 2 0.4 nozzle":
            continue
        if kind == "process" and "@Elegoo CC2 0.4 nozzle" not in name:
            continue
        if kind == "filament" and "@ECC2" not in name:
            continue
        f = flat(name, table)
        f.pop("inherits", None)
        f["from"] = "system"
        f["name"] = name
        for k in [k for k in f if k.startswith("compatible_")]:
            f.pop(k)
        fn = name.replace("/", "_") + ".json"
        json.dump(f, open(os.path.join(OUT, kind, fn), "w", encoding="utf-8"), indent=1)
        picked.append({"name": name, "file": f"{kind}/{fn}"})
    picked.sort(key=lambda x: x["name"])
    index[kind] = picked
    print(kind, len(picked))

# the engine reads these from its resources folder (bcslice --resources OUT)
import shutil
info_src = os.path.join(SRC, "..", "..", "info")
os.makedirs(os.path.join(OUT, "info"), exist_ok=True)
index["info"] = []
for f in sorted(os.listdir(info_src)):
    shutil.copy(os.path.join(info_src, f), os.path.join(OUT, "info", f))
    index["info"].append({"name": f, "file": "info/" + f})
json.dump(index, open(os.path.join(OUT, "index.json"), "w"), indent=1)
