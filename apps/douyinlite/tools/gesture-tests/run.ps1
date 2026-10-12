param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
}
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sourceFile = Join-Path $projectDir 'app/src/main/java/com/zhou/douyinlite/EdgeGestureState.java'
$outputDir = Join-Path $projectDir 'app/build/gesture-tests'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $outputDir $sourceFile (Join-Path $PSScriptRoot 'EdgeGestureStateTest.java')
if ($LASTEXITCODE -ne 0) { throw "Gesture test compilation failed: $LASTEXITCODE" }
& (Join-Path $JavaHome 'bin/java.exe') -cp $outputDir com.zhou.douyinlite.EdgeGestureStateTest
if ($LASTEXITCODE -ne 0) { throw "Gesture tests failed: $LASTEXITCODE" }
