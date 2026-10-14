@echo off
rem The Incari build CLI on Windows. Same as ./incari: builds the tool with Gradle, then runs it.
setlocal
set "ROOT=%~dp0"
set "MODULE=build"
if "%~1"=="survey" set "MODULE=survey"
if "%~1"=="copycheck" set "MODULE=copycheck"

call "%ROOT%gradlew.bat" --quiet --console=plain -p "%ROOT%." ":tools:%MODULE%:installDist" || exit /b 1

set "INCARI_BUILD_OPTS=%INCARI_BUILD_OPTS% -Dincari.cwd=%CD%"
set "INCARI_SURVEY_OPTS=%INCARI_SURVEY_OPTS% -Dincari.cwd=%CD%"
set "INCARI_COPYCHECK_OPTS=%INCARI_COPYCHECK_OPTS% -Dincari.cwd=%CD%"

rem The survey and copycheck take their own options; the command name isn't one of them.
if "%MODULE%"=="survey" (
  call "%ROOT%tools\survey\build\install\incari-survey\bin\incari-survey.bat" %2 %3 %4
) else if "%MODULE%"=="copycheck" (
  call "%ROOT%tools\copycheck\build\install\incari-copycheck\bin\incari-copycheck.bat" %2 %3 %4
) else (
  call "%ROOT%tools\build\build\install\incari-build\bin\incari-build.bat" %*
)
exit /b %ERRORLEVEL%
