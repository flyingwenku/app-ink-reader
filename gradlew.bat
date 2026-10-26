@echo off
rem Gradle start up script for Windows (simplified wrapper)

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVACMD=java"
)

"%JAVACMD%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" -Dorg.gradle.appname=gradlew org.gradle.wrapper.GradleWrapperMain %*
