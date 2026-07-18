cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

set(AICHAT_BACKEND_PROFILE "cpu" CACHE STRING
    "AI Chat backend profile: cpu, snapdragon-opencl, or snapdragon-htp")
set_property(CACHE AICHAT_BACKEND_PROFILE PROPERTY STRINGS
    cpu snapdragon-opencl snapdragon-htp)

set(_aichat_valid_backend_profiles cpu snapdragon-opencl snapdragon-htp)
list(FIND _aichat_valid_backend_profiles "${AICHAT_BACKEND_PROFILE}" _aichat_profile_index)
if(_aichat_profile_index EQUAL -1)
    message(FATAL_ERROR
        "Unsupported AICHAT_BACKEND_PROFILE='${AICHAT_BACKEND_PROFILE}'. "
        "Use cpu, snapdragon-opencl, or snapdragon-htp.")
endif()

set(AICHAT_ENABLE_OPENCL OFF)
set(AICHAT_ENABLE_HEXAGON OFF)
if(AICHAT_BACKEND_PROFILE STREQUAL "snapdragon-opencl")
    set(AICHAT_ENABLE_OPENCL ON)
elseif(AICHAT_BACKEND_PROFILE STREQUAL "snapdragon-htp")
    set(AICHAT_ENABLE_HEXAGON ON)
endif()

# llama.cpp fetches KleidiAI on demand.  Keeping the default upstream fetch is
# convenient for clean environments, but a verified source release can be
# supplied explicitly when a build machine is offline or behind a slow proxy.
set(AICHAT_KLEIDIAI_SOURCE_DIR "" CACHE PATH
    "Optional unpacked KleidiAI v1.24.0 source directory")
if((NOT AICHAT_KLEIDIAI_SOURCE_DIR OR "${AICHAT_KLEIDIAI_SOURCE_DIR}" STREQUAL "")
        AND DEFINED ENV{AICHAT_KLEIDIAI_SOURCE_DIR}
        AND NOT "$ENV{AICHAT_KLEIDIAI_SOURCE_DIR}" STREQUAL "")
    set(AICHAT_KLEIDIAI_SOURCE_DIR "$ENV{AICHAT_KLEIDIAI_SOURCE_DIR}" CACHE PATH
        "Optional unpacked KleidiAI v1.24.0 source directory" FORCE)
endif()
if(NOT "${AICHAT_KLEIDIAI_SOURCE_DIR}" STREQUAL "")
    if(NOT IS_DIRECTORY "${AICHAT_KLEIDIAI_SOURCE_DIR}/kai")
        message(FATAL_ERROR
            "AICHAT_KLEIDIAI_SOURCE_DIR must point to an unpacked KleidiAI source tree containing kai/.")
    endif()
    # This is FetchContent's documented source override for the declaration in
    # ggml-cpu/CMakeLists.txt.  It avoids mutating the pinned llama.cpp source.
    set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI_DOWNLOAD "${AICHAT_KLEIDIAI_SOURCE_DIR}" CACHE PATH
        "Local override for llama.cpp KleidiAI FetchContent" FORCE)
    message(STATUS "AI Chat: using local KleidiAI source at ${AICHAT_KLEIDIAI_SOURCE_DIR}")
endif()

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

if((AICHAT_ENABLE_OPENCL OR AICHAT_ENABLE_HEXAGON) AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Snapdragon backend profiles require ANDROID_ABI=arm64-v8a.")
endif()

if(AICHAT_ENABLE_OPENCL)
    if((NOT DEFINED OPENCL_SDK_ROOT OR "${OPENCL_SDK_ROOT}" STREQUAL "")
            AND DEFINED ENV{OPENCL_SDK_ROOT}
            AND NOT "$ENV{OPENCL_SDK_ROOT}" STREQUAL "")
        set(OPENCL_SDK_ROOT "$ENV{OPENCL_SDK_ROOT}" CACHE PATH "Qualcomm OpenCL SDK root" FORCE)
    endif()
    if(NOT IS_DIRECTORY "${OPENCL_SDK_ROOT}")
        message(FATAL_ERROR
            "snapdragon-opencl requires OPENCL_SDK_ROOT. Install the Qualcomm OpenCL SDK, "
            "set OPENCL_SDK_ROOT, and follow docs/snapdragon-backend-profiles.md.")
    endif()

    list(PREPEND CMAKE_PREFIX_PATH "${OPENCL_SDK_ROOT}")
    set(GGML_OPENCL ON CACHE BOOL "Enable ggml OpenCL backend" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels in the backend library" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno OpenCL kernels" FORCE)
endif()

if(AICHAT_ENABLE_HEXAGON)
    if((NOT DEFINED HEXAGON_SDK_ROOT OR "${HEXAGON_SDK_ROOT}" STREQUAL "")
            AND DEFINED ENV{HEXAGON_SDK_ROOT}
            AND NOT "$ENV{HEXAGON_SDK_ROOT}" STREQUAL "")
        set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "Hexagon SDK root" FORCE)
    endif()
    if((NOT DEFINED HEXAGON_TOOLS_ROOT OR "${HEXAGON_TOOLS_ROOT}" STREQUAL "")
            AND DEFINED ENV{HEXAGON_TOOLS_ROOT}
            AND NOT "$ENV{HEXAGON_TOOLS_ROOT}" STREQUAL "")
        set(HEXAGON_TOOLS_ROOT "$ENV{HEXAGON_TOOLS_ROOT}" CACHE PATH "Hexagon SDK tools root" FORCE)
    endif()
    if(NOT IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        message(FATAL_ERROR
            "snapdragon-htp requires HEXAGON_SDK_ROOT. Install the Hexagon SDK, set its root, "
            "and follow docs/snapdragon-backend-profiles.md.")
    endif()

    set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING "Hexagon Android prebuilt library directory" FORCE)
    set(GGML_HEXAGON ON CACHE BOOL "Enable ggml Hexagon HTP backend" FORCE)
endif()

# llama.cpp is a fixed upstream snapshot under the repository root.  Keeping it
# outside the Android module makes its provenance explicit and lets the app
# build the official CPU/KleidiAI path without duplicating its source.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
        AICHAT_ENABLE_OPENCL=$<BOOL:${AICHAT_ENABLE_OPENCL}>
        AICHAT_ENABLE_HEXAGON=$<BOOL:${AICHAT_ENABLE_HEXAGON}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)

# Keep the dynamically discovered backend libraries in this Android build.
# ai_chat loads them from ApplicationInfo.nativeLibraryDir at runtime.
if(AICHAT_ENABLE_OPENCL)
    add_dependencies(${CMAKE_PROJECT_NAME} ggml-opencl)
endif()
if(AICHAT_ENABLE_HEXAGON)
    add_dependencies(${CMAKE_PROJECT_NAME} ggml-hexagon)

    # ggml-hexagon generates DSP skeletons through ExternalProject.  Android
    # Gradle packages shared objects from CMAKE_LIBRARY_OUTPUT_DIRECTORY rather
    # than CMake's install tree, so make the skeleton targets explicit
    # dependencies and copy their runtime artifacts beside libai-chat.so.
    get_target_property(_aichat_hexagon_binary_dir ggml-hexagon BINARY_DIR)
    foreach(_aichat_htp_arch IN ITEMS v73 v75 v79 v81)
        add_dependencies(${CMAKE_PROJECT_NAME} htp-${_aichat_htp_arch})
        add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${_aichat_hexagon_binary_dir}/libggml-htp-${_aichat_htp_arch}.so"
                "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/libggml-htp-${_aichat_htp_arch}.so"
            COMMENT "Packaging libggml-htp-${_aichat_htp_arch}.so for Android")
    endforeach()
endif()
