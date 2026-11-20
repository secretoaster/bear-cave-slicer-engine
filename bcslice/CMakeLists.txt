# bcslice: Bear Cave's slicer program on the ElegooSlicer engine.
add_executable(bcslice bcslice.cpp thumb.cpp nanosvg_impl.cpp)
target_link_libraries(bcslice PRIVATE libslic3r cereal::cereal boost_libs)
if (ANDROID)
  target_link_libraries(bcslice PRIVATE log)
  # Runs from the app's native library folder, so it has to be a PIE.
  set_target_properties(bcslice PROPERTIES POSITION_INDEPENDENT_CODE ON)
  target_link_options(bcslice PRIVATE -static-libstdc++)
endif()
install(TARGETS bcslice RUNTIME DESTINATION bin)
