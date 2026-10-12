# Cross-compile lysh.dll / lysh.exe for Windows x64 from Linux (mingw-w64):
#   cmake -S lysh-c -B lysh-c/build-win -G Ninja -DCMAKE_TOOLCHAIN_FILE=lysh-c/mingw-w64.cmake \
#         -DJAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
#   cmake --build lysh-c/build-win
# jni.h comes from the Linux JDK; the Windows jni_md.h from tools/jni-win32.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER x86_64-w64-mingw32-gcc)
set(CMAKE_RC_COMPILER x86_64-w64-mingw32-windres)
set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_C_FLAGS_INIT "-I${CMAKE_CURRENT_LIST_DIR}/../tools/jni-win32")
# no libgcc / winpthread DLLs next to lysh.dll
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static")
