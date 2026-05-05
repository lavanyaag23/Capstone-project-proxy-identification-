@echo off
setlocal

set "APP_DIR=%~dp0"
set "JDK_DIR=C:\Program Files\Java\jdk-25"

if exist "%JDK_DIR%\bin\java.exe" (
    set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

cd /d "%APP_DIR%"

set "LIB_CACHE=%TEMP%\FaceAttendanceLib"
if not exist "%LIB_CACHE%" mkdir "%LIB_CACHE%"
copy /Y "lib\opencv-4100.jar" "%LIB_CACHE%\opencv-4100.jar" >nul
copy /Y "lib\sqlite-jdbc-3.53.0.0-without-natives.jar" "%LIB_CACHE%\sqlite-jdbc-3.53.0.0-without-natives.jar" >nul
copy /Y "lib\sqlite-jdbc-3.53.0.0-natives-windows.jar" "%LIB_CACHE%\sqlite-jdbc-3.53.0.0-natives-windows.jar" >nul

if not exist "out\com\attendance\Main.class" (
    echo Build output is missing. Compile with JDK 11+ first.
    pause
    exit /b 1
)

"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -cp "out;%LIB_CACHE%\opencv-4100.jar;%LIB_CACHE%\sqlite-jdbc-3.53.0.0-without-natives.jar;%LIB_CACHE%\sqlite-jdbc-3.53.0.0-natives-windows.jar" com.attendance.Main
