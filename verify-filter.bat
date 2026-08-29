@echo off
setlocal
set "KOTLIN_ROOT=D:\Android Studio\plugins\Kotlin\kotlinc"
if not exist "%KOTLIN_ROOT%\lib\kotlin-compiler.jar" (
  echo Kotlin compiler not found: %KOTLIN_ROOT%
  exit /b 1
)
if not exist "build\verification" mkdir "build\verification"
java -cp "%KOTLIN_ROOT%\lib\kotlin-preloader.jar" ^
  org.jetbrains.kotlin.preloading.Preloader ^
  -cp "%KOTLIN_ROOT%\lib\kotlin-compiler.jar" ^
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ^
  "app\src\main\java\com\konnisan\dewuauto\config\AutomationConfig.kt" ^
  "app\src\main\java\com\konnisan\dewuauto\automation\TaskCard.kt" ^
  "app\src\main\java\com\konnisan\dewuauto\automation\TaskCardParser.kt" ^
  "app\src\main\java\com\konnisan\dewuauto\automation\TaskEligibilityEvaluator.kt" ^
  "verification\TaskFilterVerifier.kt" ^
  -include-runtime -d "build\verification\task-filter-verifier.jar"
if errorlevel 1 exit /b 1
java -jar "build\verification\task-filter-verifier.jar"
