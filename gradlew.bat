@rem Gradle wrapper for Windows
@rem Set local scope
@if "%DEBUG%"=="" @echo off
@rem Set JAVA_HOME if not set
if "%JAVA_HOME%"=="" (
    echo JAVA_HOME is not set. Please set it to your JDK installation path.
    exit /b 1
)
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
