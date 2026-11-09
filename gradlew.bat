@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set WRAPPER_JAR="%DIRNAME%gradle\wrapper\gradle-wrapper.jar"
if not exist %WRAPPER_JAR% (
    if not exist "%DIRNAME%gradle\wrapper" mkdir "%DIRNAME%gradle\wrapper"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference = 'SilentlyContinue'; " ^
        "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
)
java -Xmx3G -classpath %WRAPPER_JAR% org.gradle.wrapper.GradleWrapperMain %*
