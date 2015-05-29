@echo off

REM  The only argument expected, if any, would be /B which would not show the command window i.e. 'silent mode'.
REM  The arguments are passed through to the SEE services startup scripts.
SET CMD_ARGS=%*

SET SEE_HOME=%~dp0

REM Passed to MIF startup.bat, these parameters set paths to the known locations of the tools delivered within the SEE bundle.
REM No tool-specific settings should be added here, SEE plugins should use '*-see-env-setup.bat' scripts mechanism.

SET MIF_CONNECTORS_ENV_PARAMS=

REM  SEE is delivered with a JRE so use this Java to launch MIF and FIS.
SET JAVA_CMD="%SEE_HOME%\MDL_IDE\jre\bin\java.exe"

REM setting up environment for services
for %%a in (*-see-env-setup.bat) do call "%%a"

SET FIS_DIR=fis

:startupFis
SET FIS_TRACE_FILE=%FIS_DIR%\fis.trace
IF EXIST %FIS_TRACE_FILE% (
	DEL %FIS_TRACE_FILE%
)
CALL start %CMD_ARGS% %FIS_DIR%\see-service-startup.bat 

if NOT ERRORLEVEL 0 (
	echo Failed to execute %FIS_DIR%\see-service-startup.bat 
	pause
	exit 1
)

FOR /L %%i in (1,1,30) do (
	if EXIST %FIS_TRACE_FILE% (
		echo FIS is running
		goto :startupServices
	)
	echo FIS is not running
	REM Wait for *about* one second, we can't use 'TIMEOUT' command since it results in errors when run in batch mode, SLEEP command comes with 2003 Resource Kit
	REM which may not be available.
	REM We ping an address from TEST-NET-1 address block (192.0.2.0/24) which should never be routable.
	ping 192.0.2.1 -n 2 -w 1000 > nul
)

echo Failed to start up FIS
goto :fail

:startupServices
REM Starting up services
for /D %%d in (*) do (
	if NOT [%%d] == [%FIS_DIR%] (
		if exist "%%d\see-service-startup.bat" (
			call start %CMD_ARGS% %%d\see-service-startup.bat 
			
			if NOT ERRORLEVEL 0 (
				echo Failed to execute %%d\see-service-startup.bat 
				pause
				exit 1
			)
		)
	)
)

:complete
exit 0

:fail
exit 1