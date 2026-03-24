cmake_minimum_required(VERSION 3.12)

project(sqlite3)


add_library(sqlite3 SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/sqlite3.c
    ${CMAKE_CURRENT_SOURCE_DIR}/shell.c
)

target_include_directories(sqlite3 PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/
)
