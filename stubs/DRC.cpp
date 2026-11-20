// Bear Cave phone build: Draco (.drc) import/export is left out.
#include "DRC.hpp"
namespace Slic3r {
bool load_drc(const char*, TriangleMesh*) { return false; }
bool load_drc(const char*, Model*, const char*) { return false; }
bool store_drc(const char*, TriangleMesh*, int, int) { return false; }
bool store_drc(const char*, ModelObject*, int, int) { return false; }
bool store_drc(const char*, Model*, int, int) { return false; }
}
