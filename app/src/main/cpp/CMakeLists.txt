cmake_minimum_required(VERSION 3.22.1)
project(credentials)

add_library(credentials SHARED native_credentials.cpp)

find_library(log-lib log)

target_link_libraries(credentials ${log-lib})
