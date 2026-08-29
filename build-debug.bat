@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle 8.13 was not found in PATH.
  echo Open the project in Android Studio Quail 3 and use Build ^> Build APK(s),
  echo or install Gradle 8.13 and rerun this script.
  exit /b 1
)
gradle :app:assembleDebug
endlocal
