@echo off
setlocal EnableDelayedExpansion
title Meowy Mod Compiler (Minecraft 26.2 Fabric)

echo ============================================================================
echo   Meowy - One-Click Fabric Mod Compiler for Minecraft 26.2
echo ============================================================================
echo.

cd /d "%~dp0"

:: Clean up any obsolete mixin files from earlier versions if extracted over an existing folder
if exist "src\client\java\dev\meowy\client\mixin\FramerateLimiterMixin.java" del /F /Q "src\client\java\dev\meowy\client\mixin\FramerateLimiterMixin.java"
if exist "src\client\java\dev\meowy\client\mixin\FramerateLimitTrackerMixin.java" del /F /Q "src\client\java\dev\meowy\client\mixin\FramerateLimitTrackerMixin.java"

:: 1. Verify or Auto-Locate Java 25 (required by Minecraft 26.2)
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [INFO] Java not found on PATH. Searching for Minecraft's bundled Java 25 runtime...
    call :FindMinecraftJava
)

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_STR=%%~g"
)
echo [INFO] Detected Java Runtime: %JAVA_VER_STR%

:: 2. Bootstrap Gradle Wrapper if gradle-wrapper.jar is not yet downloaded
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] Bootstrapping Gradle Wrapper JAR...
    if not exist "gradle\wrapper" mkdir "gradle\wrapper"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference = 'SilentlyContinue'; " ^
        "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
)

:: 3. Ensure gradlew.bat is present, otherwise invoke Gradle via wrapper jar
if exist "gradlew.bat" (
    echo [INFO] Running Gradle build via gradlew.bat...
    call gradlew.bat build --no-daemon
) else (
    echo [INFO] Running Gradle build via GradleLauncher...
    java -Xmx3G -cp "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain build --no-daemon
)

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed! Ensure JDK 25 is installed ^(Adoptium Temurin 25 or Microsoft OpenJDK 25^).
    echo         Download JDK 25 from: https://adoptium.net/temurin/releases/?version=25
    pause
    exit /b 1
)

if not exist "output" mkdir "output"
copy /Y "build\libs\meowy-*.jar" "output\" >nul 2>&1

echo.
echo ============================================================================
echo   [SUCCESS] Compilation Complete!
echo   Mod JAR Location: %~dp0build\libs\
echo   Also copied to  : %~dp0output\
echo   Copy the JAR into your .minecraft\mods\ folder alongside Fabric API.
echo ============================================================================
echo.
pause
exit /b 0

:FindMinecraftJava
set "MC_RUNTIME_1=%APPDATA%\.minecraft\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon\bin"
set "MC_RUNTIME_2=%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon\bin"
if exist "%MC_RUNTIME_1%\java.exe" (
    set "JAVA_HOME=%APPDATA%\.minecraft\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon"
    set "PATH=!JAVA_HOME!\bin;!PATH!"
    exit /b 0
)
if exist "%MC_RUNTIME_2%\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon"
    set "PATH=!JAVA_HOME!\bin;!PATH!"
    exit /b 0
)
exit /b 0
