cmake_minimum_required(VERSION 3.22.1)
project(jni_latinime LANGUAGES CXX)

file(
    GLOB_RECURSE LATINIME_CORE_SOURCES
    CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
)

add_library(
    jni_latinime
    SHARED
    com_android_inputmethod_keyboard_ProximityInfo.cpp
    com_android_inputmethod_latin_BinaryDictionary.cpp
    com_android_inputmethod_latin_BinaryDictionaryUtils.cpp
    com_android_inputmethod_latin_DicTraverseSession.cpp
    jni_common.cpp
    ${LATINIME_CORE_SOURCES}
)

target_compile_features(jni_latinime PRIVATE cxx_std_17)
target_compile_options(
    jni_latinime
    PRIVATE
    -Wall
    -Wextra
    -Wno-deprecated-copy
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-vla-cxx-extension
)
target_include_directories(
    jni_latinime
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

find_library(ANDROID_LOG_LIBRARY log)
target_link_libraries(jni_latinime PRIVATE ${ANDROID_LOG_LIBRARY})
