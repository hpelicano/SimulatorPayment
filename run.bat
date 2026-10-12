@echo off
rem ==========================================================================
rem  SimulatorPayment - ejecucion
rem  Uso:  run.bat                                   interfaz grafica (JavaFX)
rem        run.bat --headless regression --suite SMOKE [--env QA] [--junit out.xml]
rem        run.bat --headless issuer [--port 7001]
rem        run.bat --headless agent --token <secreto> [--port 7700]
rem  Variables opcionales:
rem        JAVA_OPTS            opciones extra de la JVM (por defecto -Xms256m -Xmx1g)
rem        SIM_MASTER_PASSWORD  contrasena maestra (evita el dialogo al usar secretos ENC(...))
rem ==========================================================================
setlocal
cd /d "%~dp0"

set "JAR=%~dp0sim-app\target\simulator-payment.jar"
if not exist "%JAR%" (
    echo [ERROR] No existe %JAR%
    echo Compile primero el proyecto con: build.bat
    exit /b 1
)

set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No se encontro Java 17+. Configure JAVA_HOME o agregue java al PATH.
    exit /b 1
)

if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1g"
"%JAVA_EXE%" %JAVA_OPTS% -Dfile.encoding=UTF-8 -jar "%JAR%" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
