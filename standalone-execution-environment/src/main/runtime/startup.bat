@echo off

REM  The only argument expected, if any, would be /B which would not show the command window i.e. 'silent mode'.
REM  The arguments are passed through to the MIF and FIS startup scripts.
SET CMD_ARGS=%*

SET SEE_HOME=%~dp0

REM Passed to MIF startup.bat, these parameters set paths to the known locations of the tools delivered within the SEE bundle.
REM No tool-specific settings should be added here, SEE plugins should use '*-see-env-setup.bat' scripts mechanism.

SET MIF_CONNECTORS_ENV_PARAMS=

REM  SEE is delivered with a JRE so use this Java to launch MIF and FIS.
SET JAVA_CMD="%SEE_HOME%\MDL_IDE\jre\bin\java.exe"

REM setting up environment for services
for %%a in (*-see-env-setup.bat) do call "%%a"

REM Starting up services
for /D %%d in (*) do (
    if exist "%%d\see-service-startup.bat" (
        call start %CMD_ARGS% %%d\see-service-startup.bat 
        
        if %ERRORLEVEL% NEQ 0 (
            echo Failed to execute
            pause
            exit 1
        )
    )
)