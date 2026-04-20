# PowerShell script to Build and Run the AppLock App via CLI

$ADB_PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

Write-Host "--- AppLock CLI Build & Run System ---" -ForegroundColor Cyan

# 1. Check for connected devices
Write-Host "[1/3] Checking for connected Android devices..."
& $ADB_PATH devices

# 2. Check for Gradlew
if (!(Test-Path ".\gradlew.bat")) {
    Write-Host "[!] gradlew.bat not found. Please open the project in Android Studio once to generate it, or run 'gradle wrapper' if you have gradle installed." -ForegroundColor Yellow
    exit
}

# 3. Build the APK
Write-Host "[2/3] Building Debug APK..." -ForegroundColor Cyan
.\gradlew.bat assembleDebug

# 4. Install and Run
if (Test-Path ".\app\build\outputs\apk\debug\app-debug.apk") {
    Write-Host "[3/3] Installing and Starting App..." -ForegroundColor Green
    & $ADB_PATH install -r .\app\build\outputs\apk\debug\app-debug.apk
    & $ADB_PATH shell am start -n "com.vaish.applock/com.vaish.applock.MainActivity"
} else {
    Write-Host "[!] Build failed or APK not found." -ForegroundColor Red
}
