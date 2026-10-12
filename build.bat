@echo off
rem ==========================================================================
rem  SimulatorPayment - build completo (todos los modulos + jar ejecutable)
rem  Uso:  build.bat            compila y ejecuta los tests
rem        build.bat skiptests  compila sin ejecutar tests
rem        build.bat offline    usa solo el repositorio local de Maven (-o)
rem  (los argumentos se pueden combinar: build.bat skiptests offline)
rem ==========================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "MVN_ARGS=-B clean install"
for %%A in (%*) do (
    if /I "%%~A"=="skiptests" set "MVN_ARGS=!MVN_ARGS! -DskipTests"
    if /I "%%~A"=="offline"   set "MVN_ARGS=!MVN_ARGS! -o"
)

rem ---- Java 17+ -------------------------------------------------------------
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No se encontro Java. Instale JDK 17+ y configure JAVA_HOME.
    exit /b 1
)
set "JAVA_VER="
for /f "tokens=3" %%V in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~V"
for /f "delims=." %%M in ("%JAVA_VER%") do set "JAVA_MAJOR=%%M"
if not defined JAVA_MAJOR set "JAVA_MAJOR=0"
if "%JAVA_MAJOR%"=="1" (
    echo [ERROR] Se requiere Java 17 o superior. Version detectada: %JAVA_VER%
    exit /b 1
)
if %JAVA_MAJOR% LSS 17 (
    echo [ERROR] Se requiere Java 17 o superior. Version detectada: %JAVA_VER%
    exit /b 1
)
echo Java %JAVA_VER% (%JAVA_EXE%)

rem ---- Maven: PATH -> MAVEN_HOME -> M2_HOME -> Maven embebido en IntelliJ ------
set "MVN="
where mvn >nul 2>&1 && set "MVN=mvn"
if not defined MVN if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN=%M2_HOME%\bin\mvn.cmd"
if not defined MVN (
    for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do (
        if exist "%%~D\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN=%%~D\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
)
if not defined MVN (
    echo [ERROR] No se encontro Maven. Agreguelo al PATH o defina MAVEN_HOME.
    exit /b 1
)
echo Maven: %MVN%
echo Ejecutando: mvn %MVN_ARGS%
echo.

call "%MVN%" %MVN_ARGS%
if errorlevel 1 (
    echo.
    echo [ERROR] La compilacion fallo. Revise la salida anterior ^(reportes de tests en */target/surefire-reports^).
    exit /b 1
)

set "JAR=%~dp0sim-app\target\simulator-payment.jar"
if not exist "%JAR%" (
    echo [ERROR] No se genero el jar ejecutable: %JAR%
    exit /b 1
)
echo.
echo ==========================================================================
echo  BUILD OK
echo  Jar ejecutable: %JAR%
echo  Ejecutar con:   run.bat   ^(o: java -jar sim-app\target\simulator-payment.jar^)
echo ==========================================================================
endlocal
exit /b 0
