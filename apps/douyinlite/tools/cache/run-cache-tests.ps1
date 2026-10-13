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
$policy = Join-Path $appRoot 'app\src\main\java\com\zhou\douyinlite\CachePolicy.java'
$test = Join-Path $PSScriptRoot 'CacheInventoryTest.java'
$policyTest = Join-Path $PSScriptRoot 'CachePolicyTest.java'
& $compiler -encoding UTF-8 -classpath $androidJar -d $buildDir $source $policy $test $policyTest
if ($LASTEXITCODE -ne 0) { throw "Cache test compilation failed: $LASTEXITCODE" }
$classPath = $androidJar + [IO.Path]::PathSeparator + $buildDir
& $java -classpath $classPath com.zhou.douyinlite.CacheInventoryTest
if ($LASTEXITCODE -ne 0) { throw "Cache inventory tests failed: $LASTEXITCODE" }
& $java -classpath $classPath com.zhou.douyinlite.CachePolicyTest
if ($LASTEXITCODE -ne 0) { throw "Cache policy tests failed: $LASTEXITCODE" }

# 单独的主机 framework 替身只用于调度与 API 调用计数，绝不打入 APK。
$stubDir = Join-Path $buildDir 'framework-stubs'
New-Item -ItemType Directory -Force -Path $stubDir | Out-Null
$stubs = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName }
& $compiler -encoding UTF-8 -d $stubDir $stubs
if ($LASTEXITCODE -ne 0) { throw "Cache framework stub compilation failed: $LASTEXITCODE" }
$controllerClassPath = $stubDir + [IO.Path]::PathSeparator + $buildDir
$controllerTest = Join-Path $PSScriptRoot 'CacheControllerTest.java'
& $compiler -encoding UTF-8 -classpath $controllerClassPath -d $buildDir $controllerTest
if ($LASTEXITCODE -ne 0) { throw "Cache controller test compilation failed: $LASTEXITCODE" }
& $java -classpath $controllerClassPath com.zhou.douyinlite.CacheControllerTest
if ($LASTEXITCODE -ne 0) { throw "Cache controller tests failed: $LASTEXITCODE" }
