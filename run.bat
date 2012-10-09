@echo off

if "%OS%"=="Windows_NT" goto nt
echo This script only works with NT-based versions of Windows.
goto :eof

:nt
rem
rem Find the application home.
rem
rem %~dp0 is location of current script under NT
set DIR=%~dp0

set "CPATH=%DIR%\conf"
set "CPATH=%CPATH%;%DIR%\classes"
set "CPATH=%CPATH%;%DIR%\lib\aocode-public.src.jar"
set "CPATH=%CPATH%;%DIR%\lib\aoserv-client.src.jar"

"C:\Program Files (x86)\Java\jdk1.7.0_06\bin\java.exe" -classpath "%CPATH%" NetstatMonitor
