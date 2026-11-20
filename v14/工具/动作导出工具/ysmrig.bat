@echo off
rem Exports a YSM imported 3D model as .ysmrig.json (RigExport in the YSM jar) for the animkit Blockbench plugin.
rem Usage: ysmrig.bat <YSM jar> <.minecraft folder or its libraries folder> <model folder or model file> <out.ysmrig.json>
rem JAVA: the java.exe to use. Default: java on PATH, else Minecraft's bundled Java 17 (runtime\java-runtime-gamma).
setlocal EnableExtensions EnableDelayedExpansion
if "%~4"=="" (
    echo usage: ysmrig.bat ^<YSM jar^> ^<.minecraft or libraries^> ^<model folder or file^> ^<out.ysmrig.json^> 1>&2
    exit /b 2
)
set "JAR=%~f1"
set "MC=%~f2"
set "LIB=%MC%"
if exist "%MC%\libraries\" set "LIB=%MC%\libraries"
set "CP=%JAR%"
for %%A in (com\google\code\gson\gson it\unimi\dsi\fastutil org\joml\joml net\java\dev\jna\jna org\apache\logging\log4j\log4j-api org\apache\logging\log4j\log4j-core org\apache\logging\log4j\log4j-slf4j2-impl org\slf4j\slf4j-api io\netty\netty-common org\apache\commons\commons-lang3) do (
    set "FOUND="
    if exist "%LIB%\%%A\" for /r "%LIB%\%%A" %%J in (*.jar) do set "FOUND=%%J"
    if not defined FOUND (
        echo ysmrig: missing library %%A under %LIB% 1>&2
        exit /b 3
    )
    set "CP=!CP!;!FOUND!"
)
if not defined JAVA (
    where java >nul 2>nul && set "JAVA=java"
)
if not defined JAVA (
    for %%R in ("%MC%\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma\bin\java.exe" "%APPDATA%\.minecraft\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma\bin\java.exe") do (
        if not defined JAVA if exist %%R set "JAVA=%%~R"
    )
)
if not defined JAVA (
    echo ysmrig: no java found; install Java 17 or set JAVA=path\to\java.exe 1>&2
    exit /b 3
)
"%JAVA%" -Xmx2G -cp "!CP!" com.elfmcys.ysm.format.parser.RigExport "%~3" "%~4"
exit /b %ERRORLEVEL%
