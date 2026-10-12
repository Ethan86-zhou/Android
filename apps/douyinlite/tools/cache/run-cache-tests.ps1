param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$appRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$platform = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-\d+$' } |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending | Select-Object -First 1
if (-not $platform) { throw 'Android SDK Platform is required to compile the cache controller.' }
$androidJar = Join-Path $platform.FullName 'android.jar'
$compiler = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java' }
$buildDir = Join-Path $appRoot 'app\build\cache-inventory-tests'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$source = Join-Path $appRoot 'app\src\main\java\com\zhou\douyinlite\CacheController.java'
$test = Join-Path $PSScriptRoot 'CacheInventoryTest.java'
& $compiler -encoding UTF-8 -classpath $androidJar -d $buildDir $source $test
if ($LASTEXITCODE -ne 0) { throw "Cache test compilation failed: $LASTEXITCODE" }
$classPath = $androidJar + [IO.Path]::PathSeparator + $buildDir
& $java -classpath $classPath com.zhou.douyinlite.CacheInventoryTest
if ($LASTEXITCODE -ne 0) { throw "Cache inventory tests failed: $LASTEXITCODE" }
