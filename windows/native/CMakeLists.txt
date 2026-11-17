cmake_minimum_required(VERSION 3.25)

project(VaultNative VERSION 1.1.0 LANGUAGES C CXX RC)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(VaultNative WIN32
    app.cpp
    vault_core.cpp
    native_windows.rc
    third_party/miniz/miniz.c
    third_party/miniz/miniz_tdef.c
    third_party/miniz/miniz_tinfl.c
    third_party/miniz/miniz_zip.c
)

target_include_directories(VaultNative PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/miniz
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/nlohmann
)

target_compile_definitions(VaultNative PRIVATE
    UNICODE
    _UNICODE
    _CRT_SECURE_NO_WARNINGS
)

target_compile_options(VaultNative PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:/utf-8>"
    "$<$<COMPILE_LANGUAGE:CXX>:/W4>"
    "$<$<COMPILE_LANGUAGE:CXX>:/permissive->"
    "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>"
    "$<$<COMPILE_LANGUAGE:C>:/utf-8>"
)

target_link_libraries(VaultNative PRIVATE
    bcrypt
    crypt32
    ole32
    shell32
    comctl32
    comdlg32
    gdiplus
    msimg32
)

# Use the static MSVC runtime so end users do not need a Visual C++ runtime installer.
set_property(TARGET VaultNative PROPERTY
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
)

set_target_properties(VaultNative PROPERTIES
    OUTPUT_NAME "VaultNative"
    WIN32_EXECUTABLE TRUE
)
