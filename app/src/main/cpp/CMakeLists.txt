cmake_minimum_required(VERSION 3.22.1)

project("soundvisualizer")

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        kiss_fft.c)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)

# Fix for Android 16 KB Alignment warning
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
