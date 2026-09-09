cmake_minimum_required(VERSION 3.22.1)

project("soundvisualizer")

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)

# 16 KB 페이지 기기에서 .so 가 정렬되도록 링커에 최대 페이지 크기를 알려준다.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
