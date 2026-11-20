// Bear Cave phone build: STEP import is left out.
#include "STEP.hpp"
namespace Slic3r {
bool load_step(const char*, Model*, bool&, double, double, bool, ImportStepProgressFn, StepIsUtf8Fn, long&) { return false; }
}
