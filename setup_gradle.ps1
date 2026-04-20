# PowerShell script to download and setup the Gradle Wrapper
$GRADLE_VERSION = "8.2"
$BASE_URL = "https://raw.githubusercontent.com/gradle/gradle/v$GRADLE_VERSION.0/gradlew"

Write-Host "--- Downloading Gradle Wrapper ---" -ForegroundColor Cyan

# Create directory structure
New-Item -ItemType Directory -Force -Path "gradle/wrapper"

# Download gradlew (Unix)
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v$GRADLE_VERSION.0/gradlew" -OutFile "gradlew"
# Download gradlew.bat (Windows)
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v$GRADLE_VERSION.0/gradlew.bat" -OutFile "gradlew.bat"
# Download gradlew-wrapper.jar (Binary)
Invoke-WebRequest -Uri "https://github.com/gradle/gradle/raw/v$GRADLE_VERSION.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle/wrapper/gradle-wrapper.jar"

Write-Host "Wrapper scripts and JAR downloaded. You can now build!" -ForegroundColor Green
Write-Host "Alternatively, simply open the project in Android Studio; it will do this automatically." -ForegroundColor Green
