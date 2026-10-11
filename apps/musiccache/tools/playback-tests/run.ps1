param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testOutput = Join-Path $projectRoot 'build/playback-tests'
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
$javaSources = @(
    (Join-Path $projectRoot 'app/src/main/java/com/zhou/musiccache/cache/RangeCache.java'),
    (Join-Path $projectRoot 'app/src/main/java/com/zhou/musiccache/cache/CacheProxy.java'),
    (Join-Path $projectRoot 'app/src/main/java/com/zhou/musiccache/cache/WebViewRangeInputStream.java'),
    (Join-Path $projectRoot 'app/src/main/java/com/zhou/musiccache/playback/PlayerRuntime.java'),
    (Join-Path $projectRoot 'app/src/main/java/com/zhou/musiccache/playback/CacheDataSource.java'),
    (Join-Path $PSScriptRoot 'PlaybackDataSourceTest.java')
)
$javaSources += Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tools/cache-tests/stubs') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$javaSources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 --release 17 --add-modules jdk.httpserver -d $testOutput @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Playback contract compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') --add-modules jdk.httpserver -cp $testOutput com.zhou.musiccache.playback.PlaybackDataSourceTest
if ($LASTEXITCODE -ne 0) { throw 'Playback contract execution failed' }
