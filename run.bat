@echo off
setlocal
echo [1/3] Locating Java...

if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
) else if exist "%LOCALAPPDATA%\Programs\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android\Android Studio\jbr"
) else if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)

if "%JAVA_HOME%"=="" (
    echo ERROR: Java not found in standard Android Studio paths!
    pause
    exit /b 1
)

echo Found Java: %JAVA_HOME%

echo [2/3] Building APK...
call .\gradlew assembleDebug
if errorlevel 1 (
    echo Build failed!
    pause
    exit /b 1
)

echo [3/3] Running device smoke test...
powershell -ExecutionPolicy Bypass -File scripts\device-smoke-test.ps1
pause