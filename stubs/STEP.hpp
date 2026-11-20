// Bear Cave phone build: STEP import is left out (it needs OpenCASCADE).
// Same names as ElegooSlicer's Format/STEP.hpp so the rest of the engine builds.
#ifndef slic3r_Format_STEP_hpp_
#define slic3r_Format_STEP_hpp_

#include <boost/filesystem/path.hpp>
#include <boost/filesystem.hpp>
#include <atomic>
#include <functional>
#include <string>

namespace fs = boost::filesystem;

namespace Slic3r {

class TriangleMesh;
class ModelObject;
class Model;

const int LOAD_STEP_STAGE_READ_FILE = 0;
const int LOAD_STEP_STAGE_GET_SOLID = 1;
const int LOAD_STEP_STAGE_GET_MESH  = 2;
const int LOAD_STEP_STAGE_NUM       = 3;
const int LOAD_STEP_STAGE_UNIT_NUM  = 5;

typedef std::function<void(int load_stage, int current, int total, bool& cancel)> ImportStepProgressFn;
typedef std::function<void(bool isUtf8)> StepIsUtf8Fn;

extern bool load_step(const char *path, Model *model, bool& is_cancel,
                      double linear_defletion = 0.003, double angle_defletion = 0.5,
                      bool isSplitCompound = false, ImportStepProgressFn proFn = nullptr,
                      StepIsUtf8Fn isUtf8Fn = nullptr, long& mesh_face_num = *(new long(-1)));

class StepPreProcessor {
public:
    bool preprocess(const char*, std::string&) { return false; }
    static bool isUtf8File(const char*) { return true; }
    static bool isUtf8(const std::string) { return true; }
};

class Step
{
public:
    enum class Step_Status { LOAD_SUCCESS, LOAD_ERROR, CANCEL, MESH_SUCCESS, MESH_ERROR };
    Step(fs::path, ImportStepProgressFn = nullptr, StepIsUtf8Fn = nullptr) {}
    Step(std::string, ImportStepProgressFn = nullptr, StepIsUtf8Fn = nullptr) {}
    Step_Status load() { return Step_Status::LOAD_ERROR; }
    unsigned int get_triangle_num(double, double) { return 0; }
    unsigned int get_triangle_num_tbb(double, double) { return 0; }
    void clean_mesh_data() {}
    Step_Status mesh(Model*, bool&, bool, double = 0.003, double = 0.5) { return Step_Status::MESH_ERROR; }
    std::atomic<bool> m_stop_mesh{false};
    void update_process(int, int, int, bool&) {}
};

} // namespace Slic3r

#endif
