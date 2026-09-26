@echo off
rem Runs the mintwf CLI. Builds mintwf-cli\target\mintwf.jar first when it is missing.
rem Unlike bin/mintwf this does not rebuild after source changes; run "mvnw.cmd package" to refresh it.
setlocal
set "ROOT=%~dp0.."
set "JAR=%ROOT%\mintwf-cli\target\mintwf.jar"
if not exist "%JAR%" (
    echo mintwf: building the CLI... 1>&2
    pushd "%ROOT%"
    call mvnw.cmd -q -pl mintwf-cli -am package -DskipTests 1>&2
    popd
    if not exist "%JAR%" exit /b 1
)
java -jar "%JAR%" %*
