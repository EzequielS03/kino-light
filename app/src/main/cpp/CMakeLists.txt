cmake_minimum_required(VERSION 3.22.1)
project(credentials)

include(FetchContent)
FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG v3.6.2
    GIT_SHALLOW TRUE
)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mbedtls)

add_library(credentials SHARED native_credentials.cpp)

find_library(log-lib log)

target_link_libraries(credentials mbedcrypto ${log-lib})
