cmake_minimum_required(VERSION 3.22.1)
project(fondvert_rvm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED NCNN_SDK_ROOT)
    message(FATAL_ERROR "NCNN_SDK_ROOT absent")
endif()

file(GLOB_RECURSE NCNN_CONFIG_FILES
        "${NCNN_SDK_ROOT}/*/ncnnConfig.cmake")
set(NCNN_CONFIG_FOUND "")
foreach(CONFIG_FILE ${NCNN_CONFIG_FILES})
    string(FIND "${CONFIG_FILE}" "/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake" MATCH_POS)
    if(NOT MATCH_POS EQUAL -1)
        get_filename_component(NCNN_CONFIG_FOUND "${CONFIG_FILE}" DIRECTORY)
        break()
    endif()
endforeach()

if(NCNN_CONFIG_FOUND STREQUAL "")
    message(FATAL_ERROR "ncnnConfig.cmake introuvable pour ${ANDROID_ABI} dans ${NCNN_SDK_ROOT}")
endif()

set(ncnn_DIR "${NCNN_CONFIG_FOUND}" CACHE PATH "ncnn cmake directory" FORCE)
find_package(ncnn REQUIRED)

add_library(rvmncnn SHARED rvm_jni.cpp)

target_compile_options(rvmncnn PRIVATE -O3 -fvisibility=hidden)

find_library(LOG_LIB log)
find_library(JNIGRAPHICS_LIB jnigraphics)

target_link_libraries(rvmncnn
        ncnn
        ${LOG_LIB}
        ${JNIGRAPHICS_LIB})
