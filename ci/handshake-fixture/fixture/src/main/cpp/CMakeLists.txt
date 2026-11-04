cmake_minimum_required(VERSION 3.22.1)
project(handshake_safe C)
add_library(handshake_safe SHARED safe_files.c)
target_compile_options(handshake_safe PRIVATE -Wall -Wextra -Werror)
