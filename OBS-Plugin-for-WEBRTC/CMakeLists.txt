cmake_minimum_required(VERSION 3.13)
project(obs-plugin-webrtc LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OBS plugin build dependencies should be provided by your OBS development environment.
# You may need to adapt include/link paths for Windows or Linux.

find_package(PkgConfig REQUIRED)
pkg_check_modules(OBS REQUIRED libobs)

include_directories(${OBS_INCLUDE_DIRS})
link_directories(${OBS_LIBRARY_DIRS})

add_library(obs-plugin-webrtc MODULE
    src/plugin-main.cpp
    src/WebRTCSource.cpp
)

target_include_directories(obs-plugin-webrtc PRIVATE src)
target_link_libraries(obs-plugin-webrtc ${OBS_LIBRARIES})

# On Windows, OBS plugins use .dll extension; on Linux/macOS, use .so.
set_target_properties(obs-plugin-webrtc PROPERTIES PREFIX "" SUFFIX ".so")
