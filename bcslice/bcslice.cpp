// bcslice: Bear Cave's small slicer program on the real ElegooSlicer engine.
//
//   bcslice --machine m.json --process p.json --filament f.json
//           [--plate N] [--bed "Textured PEI Plate"] [--tmp DIR] [--json out.json]
//           [--preview picture.png] [--color "#RRGGBB"]
//           -o out.gcode model.(3mf|stl|obj)
//
// The three .json files are complete ("flattened") ElegooSlicer presets, the
// same files the desktop Bear Cave passes to elegoo-slicer.exe.
// Prints "PROGRESS <percent> <text>" lines while it works, and writes a small
// JSON report (time taken, peak memory, print estimate) at the end.
//
// Copyright (C) 2026 Design202, LLC (Bear Cave Labs). Built with Claude.
// Licensed under the GNU AGPL v3, like ElegooSlicer, which this uses.

#include "libslic3r/libslic3r.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "thumb.hpp"

#include <tbb/global_control.h>
#include <tbb/task_arena.h>
#include <boost/algorithm/string/predicate.hpp>
#include <boost/filesystem.hpp>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

using namespace Slic3r;
namespace fs = boost::filesystem;

static std::string json_escape(const std::string &s)
{
    std::string o;
    for (char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': break;
        case '\t': o += "\\t"; break;
        default: if ((unsigned char)c >= 0x20) o += c;
        }
    }
    return o;
}

// Peak memory (resident) in MB, from /proc on Linux and Android.
static double peak_mb()
{
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line))
        if (line.rfind("VmHWM:", 0) == 0) return std::atof(line.c_str() + 6) / 1024.0;
    return 0;
}

struct Args {
    std::string machine, process, filament, input, output, json, tmp, bed, resources, preview, colour;
    int plate = 0;
    int threads = 0;
};

static int fail(const Args &a, int code, const std::string &msg)
{
    std::cerr << "ERROR " << msg << std::endl;
    if (!a.json.empty()) {
        std::ofstream j(a.json);
        j << "{\"ok\":false,\"code\":" << code << ",\"error\":\"" << json_escape(msg) << "\",\"peakMB\":" << peak_mb() << "}\n";
    }
    return code;
}

static bool load_preset(const std::string &path, DynamicPrintConfig &into, std::string &err, std::string *name = nullptr, std::string *fid = nullptr)
{
    DynamicPrintConfig c;
    std::map<std::string, std::string> kv;
    std::string reason;
    try {
        c.load_from_json(path, ForwardCompatibilitySubstitutionRule::Enable, kv, reason);
    } catch (const std::exception &e) {
        err = "can't read " + path + ": " + e.what();
        return false;
    }
    if (!reason.empty()) {
        err = "can't read " + path + ": " + reason;
        return false;
    }
    c.normalize_fdm();
    into.apply(c, true);
    if (name) *name = kv[BBL_JSON_KEY_NAME];
    if (fid && kv.count(BBL_JSON_KEY_FILAMENT_ID)) *fid = kv[BBL_JSON_KEY_FILAMENT_ID];
    return true;
}

// Size of a bed from a printable_area option (a list of corner points).
static Vec2d bed_size(const DynamicPrintConfig &c, Vec2d fallback)
{
    auto *pa = c.option<ConfigOptionPoints>("printable_area");
    if (!pa || pa->values.empty()) return fallback;
    BoundingBoxf bb;
    for (const Vec2d &p : pa->values) bb.merge(p);
    return bb.size();
}

// Same rule ElegooSlicer uses to lay plates out in a grid.
static int plate_columns(int count)
{
    float v = std::sqrt((float)count), r = std::round(v);
    return v > r ? (int)r + 1 : (int)r;
}

static int run(int argc, char **argv)
{
    Args a;
    for (int i = 1; i < argc; ++i) {
        std::string k = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (k == "--machine") a.machine = next();
        else if (k == "--process") a.process = next();
        else if (k == "--filament") a.filament = next();
        else if (k == "--plate") a.plate = std::atoi(next().c_str());
        else if (k == "--bed") a.bed = next();
        else if (k == "--tmp") a.tmp = next();
        else if (k == "--resources") a.resources = next();
        else if (k == "--threads") a.threads = std::atoi(next().c_str());
        else if (k == "--json") a.json = next();
        else if (k == "--preview") a.preview = next();
        else if (k == "--color") a.colour = next();
        else if (k == "-o" || k == "--output") a.output = next();
        else if (k == "--version") { std::cout << "bcslice 1 on " << SLIC3R_APP_NAME << " " << SLIC3R_VERSION << std::endl; return 0; }
        else a.input = k;
    }
    if (a.machine.empty() || a.process.empty() || a.filament.empty() || a.input.empty() || a.output.empty()) {
        std::cerr << "usage: bcslice --machine m.json --process p.json --filament f.json [--plate N] [--bed TYPE] [--tmp DIR] [--resources DIR] [--threads N] [--json out.json] -o out.gcode model.3mf" << std::endl;
        return 1;
    }

    // the engine needs full paths (it creates the output's folder)
    a.output = fs::absolute(a.output).string();
    a.input = fs::absolute(a.input).string();
    if (!a.json.empty()) a.json = fs::absolute(a.json).string();
    if (!a.preview.empty()) a.preview = fs::absolute(a.preview).string();
    auto t0 = std::chrono::steady_clock::now();
    auto secs = [&]() { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };

    std::string tmp = a.tmp.empty() ? (fs::path(a.output).parent_path() / "bcslice_tmp").string() : a.tmp;
    fs::create_directories(tmp);
    set_temporary_dir(tmp);
    set_data_dir(tmp);
    // ElegooSlicer reads a few files from its resources folder (info/nozzle_info.json)
    set_resources_dir(a.resources.empty() ? tmp : a.resources);
    set_logging_level(1);

    // ---- settings: full defaults, then printer, process, filament on top
    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();
    std::string err;
    std::string mname, pname, fname, fid;
    if (!load_preset(a.machine, config, err, &mname) || !load_preset(a.process, config, err, &pname) ||
        !load_preset(a.filament, config, err, &fname, &fid))
        return fail(a, 2, err);
    // record which presets made this, like ElegooSlicer does
    config.set_key_value("printer_settings_id", new ConfigOptionString(mname));
    config.set_key_value("print_settings_id", new ConfigOptionString(pname));
    config.set_key_value("filament_settings_id", new ConfigOptionStrings({fname}));
    if (!fid.empty()) config.set_key_value("filament_ids", new ConfigOptionStrings({fid}));
    // Defaults for enum lists are made without their name table, so they'd be
    // written as blanks in the G-code's settings notes. Give them their names.
    for (const std::string &k : config.keys()) {
        const ConfigOptionDef *d = print_config_def.get(k);
        if (!d || !d->enum_keys_map) continue;
        if (auto *o = dynamic_cast<ConfigOptionEnumsGeneric *>(config.option(k)); o && !o->keys_map) o->keys_map = d->enum_keys_map;
        if (auto *o = dynamic_cast<ConfigOptionEnumsGenericNullable *>(config.option(k)); o && !o->keys_map) o->keys_map = d->enum_keys_map;
    }
    std::cout << "PROGRESS 2 Settings loaded" << std::endl;

    // ---- model
    Model model;
    DynamicPrintConfig file_config;
    PlateDataPtrs plates;
    std::vector<Preset *> project_presets;
    bool is_bbl = false;
    Semver file_version;
    try {
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Enable);
        model = Model::read_from_file(a.input, &file_config, &ctx,
                                      LoadStrategy::LoadModel | LoadStrategy::LoadConfig | LoadStrategy::AddDefaultInstances,
                                      &plates, &project_presets, &is_bbl, &file_version);
    } catch (const std::exception &e) {
        return fail(a, 3, std::string("can't open the model: ") + e.what());
    }
    for (Preset *p : project_presets) delete p;
    if (model.objects.empty()) return fail(a, 3, "the file has no objects");
    std::cout << "PROGRESS 6 Model loaded: " << model.objects.size() << " objects, " << plates.size() << " plates" << std::endl;

    const Vec2d our_bed = bed_size(config, Vec2d(256, 256));
    const bool is_3mf = boost::algorithm::iends_with(a.input, ".3mf");

    // ---- pick one plate: keep its objects, move them onto our bed
    if (is_3mf && plates.size() > 1) {
        int n = a.plate >= 1 && a.plate <= (int)plates.size() ? a.plate : 1;
        PlateData *pd = nullptr;
        for (PlateData *p : plates) if (p && p->plate_index == n - 1) pd = p;
        if (!pd) pd = plates[n - 1];
        std::set<std::pair<int, int>> keep(pd->objects_and_instances.begin(), pd->objects_and_instances.end());
        for (int oi = (int)model.objects.size() - 1; oi >= 0; --oi) {
            ModelObject *mo = model.objects[oi];
            for (int ii = (int)mo->instances.size() - 1; ii >= 0; --ii)
                if (!keep.count({oi, ii})) mo->delete_instance(ii);
            if (mo->instances.empty()) model.delete_object(oi);
        }
        if (model.objects.empty()) return fail(a, 4, "plate " + std::to_string(n) + " is empty");
        Vec2d old_bed = bed_size(file_config, our_bed);
        int cols = plate_columns((int)plates.size());
        int idx = n - 1, row = idx / cols, col = idx % cols;
        Vec3d origin(col * old_bed.x() * 1.2, -row * old_bed.y() * 1.2, 0);
        for (ModelObject *mo : model.objects) mo->translate_instances(-origin);
        std::cout << "PROGRESS 8 Plate " << n << " of " << plates.size() << std::endl;
    }
    for (PlateData *p : plates) delete p;
    plates.clear();

    // ---- one filament: the phone build prints with one spool
    for (ModelObject *mo : model.objects) {
        if (mo->config.has("extruder")) mo->config.set("extruder", 1);
        for (ModelVolume *mv : mo->volumes) {
            if (mv->config.has("extruder")) mv->config.set("extruder", 1);
            mv->mmu_segmentation_facets.reset();
        }
    }

    // ---- placement: loose files go in the middle of the bed; anything that
    // ended up off our bed (a bigger printer's plate) is moved to the middle too
    for (ModelObject *mo : model.objects) mo->ensure_on_bed();
    BoundingBoxf3 bb = model.bounding_box_exact();
    BoundingBoxf ours(Vec2d(0, 0), our_bed);
    bool off_bed = bb.min.x() < -0.5 || bb.min.y() < -0.5 || bb.max.x() > our_bed.x() + 0.5 || bb.max.y() > our_bed.y() + 0.5;
    if (!is_3mf || off_bed) {
        // Same arrange step ElegooSlicer runs on loose files, so the part lands in the same spot.
        bool arranged = false;
        try {
            arrangement::ArrangePolygons selected, unselected;
            arrangement::ArrangeParams ap_cfg;
            for (ModelObject *mo : model.objects)
                for (ModelInstance *mi : mo->instances) {
                    arrangement::ArrangePolygon ap = get_instance_arrange_poly(mi, config);
                    ap.itemid = (int)selected.size();
                    selected.emplace_back(std::move(ap));
                }
            // the printer's no-go zones (the CC2 has one in the front-right corner) are obstacles
            if (auto *ex = config.option<ConfigOptionPoints>("bed_exclude_area"); ex && ex->values.size() >= 3) {
                BoundingBoxf eb;
                for (const Vec2d &p : ex->values) eb.merge(p);
                arrangement::ArrangePolygon ret;
                ret.poly.contour = Polygon({{scaled(eb.min.x()), scaled(eb.min.y())}, {scaled(eb.max.x()), scaled(eb.min.y())},
                                            {scaled(eb.max.x()), scaled(eb.max.y())}, {scaled(eb.min.x()), scaled(eb.max.y())}});
                ret.translation = Vec2crd(0, 0);
                ret.rotation = 0.0f;
                ret.is_virt_object = true;
                ret.bed_idx = 0;
                ret.height = 1;
                ret.name = "ExcludedRegion0";
                unselected.emplace_back(std::move(ret));
            }
            if (auto *ps = config.option<ConfigOptionEnum<PrintSequence>>("print_sequence"))
                ap_cfg.is_seq_print = ps->value == PrintSequence::ByObject;
            if (auto *st = config.option<ConfigOptionEnum<PrinterStructure>>("printer_structure"))
                ap_cfg.align_to_y_axis = st->value == PrinterStructure::psI3;
            arrangement::update_arrange_params(ap_cfg, &config, selected);
            arrangement::update_selected_items_inflation(selected, &config, ap_cfg);
            arrangement::update_unselected_items_inflation(unselected, &config, ap_cfg);
            arrangement::update_selected_items_axis_align(selected, &config, ap_cfg);
            Points beds = arrangement::get_shrink_bedpts(&config, ap_cfg);
            ap_cfg.progressind = [](unsigned, std::string) {};
            arrangement::arrange(selected, unselected, beds, ap_cfg);
            arranged = true;
            for (auto &ap : selected) {
                if (ap.bed_idx != 0) { arranged = false; break; }
            }
            if (arranged) for (auto &ap : selected) ap.apply();
        } catch (const std::exception &) { arranged = false; }
        if (!arranged) model.center_instances_around_point(our_bed / 2.0);
        for (ModelObject *mo : model.objects) mo->ensure_on_bed();
        std::cout << "PROGRESS 9 " << (arranged ? "Arranged" : "Centered") << " on the bed" << std::endl;
    }

    // ---- bed type
    if (!a.bed.empty()) {
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Enable);
        config.set_deserialize("curr_bed_type", a.bed, ctx);
    } else if (auto *db = config.option<ConfigOptionString>("default_bed_type"); db && !db->value.empty()) {
        // Elegoo stores it as a number (4 = Textured PEI Plate)
        if (db->value.find_first_not_of("0123456789") == std::string::npos)
            config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>((BedType)std::atoi(db->value.c_str())));
        else {
            ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Enable);
            try { config.set_deserialize("curr_bed_type", db->value, ctx); } catch (...) {}
        }
    } else if (file_config.has("curr_bed_type")) {
        config.set_key_value("curr_bed_type", file_config.option("curr_bed_type")->clone());
    }

    // single extruder, single filament
    config.option<ConfigOptionInts>("filament_map", true)->values = {1};
    if (!config.has("nozzle_volume_type"))
        config.option<ConfigOptionEnumsGeneric>("nozzle_volume_type", true)->values.resize(1, nvtStandard);

    // ---- slice
    Print print;
    // The GUI always sets this through PartPlate; without it the origin is left unset
    // (Eigen does not zero vectors), which made brim/support/wipe-tower placement vary on Android.
    print.set_plate_origin(Vec3d::Zero());
    print.set_status_callback([](const PrintBase::SlicingStatus &s) {
        if (s.percent >= 0) std::cout << "PROGRESS " << s.percent << " " << s.text << std::endl;
    });
    try {
        print.apply(model, config);
        print.is_BBL_printer() = false;
        StringObjectException warning;
        auto verr = print.validate(&warning);
        if (!verr.string.empty()) return fail(a, 5, verr.string);
        if (!warning.string.empty()) std::cout << "WARNING " << warning.string << std::endl;
        print.process();
        std::cout << "PROGRESS 90 Writing G-code" << std::endl;
        GCodeProcessorResult result;
        // the picture on the printer's screen: the filament colour if the file has one, else --color
        Vec3f colour = bc::parse_colour(a.colour, Vec3f(0.95f, 0.73f, 0.23f));
        if (a.colour.empty())
            if (auto *fc = config.option<ConfigOptionStrings>("filament_colour"); fc && !fc->values.empty())
                colour = bc::parse_colour(fc->values.front(), colour);
        // too dark to see on a dark screen: lift it
        if (colour.maxCoeff() < 0.35f) colour = colour * 0.4f + Vec3f(0.42f, 0.44f, 0.47f);
        ThumbnailsGeneratorCallback thumbs = [&model, colour](const ThumbnailsParams &p) {
            ThumbnailsList list;
            for (const Vec2d &sz : p.sizes)
                list.push_back(bc::render(model, (unsigned)sz.x(), (unsigned)sz.y(), colour, true));
            return list;
        };
        print.export_gcode(a.output, &result, thumbs);
        if (!a.preview.empty()) bc::write_png(bc::render(model, 480, 480, colour, false), a.preview);
    } catch (const std::exception &e) {
        return fail(a, 6, std::string("slicing failed: ") + e.what());
    }

    // the 3MF reader unpacks into the temporary folder; clean it up
    boost::system::error_code ec;
    fs::remove_all(fs::path(tmp) / "elegooslicer_model", ec);
    const PrintStatistics &ps = print.print_statistics();
    double took = secs();
    std::cout << "PROGRESS 100 Done in " << took << " s, peak memory " << peak_mb() << " MB" << std::endl;
    if (!a.json.empty()) {
        std::ofstream j(a.json);
        j << "{\"ok\":true,\"seconds\":" << took << ",\"peakMB\":" << peak_mb()
          << ",\"estimate\":\"" << json_escape(ps.estimated_normal_print_time) << "\""
          << ",\"grams\":" << ps.total_weight << ",\"filamentMM\":" << ps.total_used_filament
          << ",\"output\":\"" << json_escape(a.output) << "\"}\n";
    }
    return 0;
}

// --threads N limits how many cores the engine uses (0 = all). It runs the whole slice inside a
// TBB arena of N threads. (A global_control cap deadlocks: the engine's thread-naming step waits
// for max_concurrency() threads to arrive, which still reports every core.)
int main(int argc, char **argv)
{
    int threads = 0;
    for (int i = 1; i + 1 < argc; ++i)
        if (std::string(argv[i]) == "--threads") threads = std::atoi(argv[i + 1]);
    if (threads <= 0)
        return run(argc, argv);
    tbb::task_arena arena(threads);
    return arena.execute([&] { return run(argc, argv); });
}
