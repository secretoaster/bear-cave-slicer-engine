// Bear Cave phone build: SVG import is left out (it needs OpenCASCADE).
#include <string>
#include "svg.hpp"
namespace Slic3r {
bool load_svg(const char*, Model*, std::string& message) { message = "SVG import is not in the phone build"; return false; }
}
