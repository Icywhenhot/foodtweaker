@echo off
REM ===========================================================================
REM  FoodTweaker dev launcher.
REM
REM  This machine needs a few JVM flags to build/run Minecraft from Gradle:
REM    - JDK 21 (not the default JDK 25) via its short 8.3 path (no spaces).
REM    - jdk.net.unixdomain.tmpdir=C:\fttmp  -> works around the AF_UNIX/loopback
REM      failure caused by the "&" in this Windows username's temp path.
REM    - preferIPv4Stack=true -> reliable DNS (IPv6/::1 is broken here).
REM
REM  Usage:
REM    dev.bat                -> runs the Fabric client (default)
REM    dev.bat :neoforge:runClient
REM    dev.bat :fabric:runServer
REM    dev.bat build          -> just builds both jars
REM ===========================================================================

REM Convert the JDK 21 path to its space-free short form so gradlew.bat is happy.
for %%I in ("C:\Program Files\Java\jdk-21.0.10") do set "JAVA_HOME=%%~sI"

if not exist C:\fttmp mkdir C:\fttmp
set "_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\fttmp -Djava.io.tmpdir=C:\fttmp -Djava.net.preferIPv4Stack=true"

set "TASK=%*"
if "%TASK%"=="" set "TASK=:fabric:runClient"

echo Running: gradlew %TASK%
call "%~dp0gradlew.bat" %TASK% --console=plain
