// The desktop app compiles nanosvg's code in its screens (BitmapCache.cpp),
// which the phone build leaves out; the engine still calls it, so it lives here.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
