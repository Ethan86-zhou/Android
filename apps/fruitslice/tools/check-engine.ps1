$ErrorActionPreference = 'Stop'
$appRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $PSScriptRoot 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $javac -encoding UTF-8 -d $classes (Join-Path $appRoot 'app/src/main/java/com/ethan/fruitslice/GameEngine.java') (Join-Path $appRoot 'app/src/main/java/com/ethan/fruitslice/BladeLimiter.java') (Join-Path $PSScriptRoot 'GameEngineCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Engine check compilation failed' }
& $java -cp $classes com.ethan.fruitslice.GameEngineCheck
if ($LASTEXITCODE -ne 0) { throw 'Engine checks failed' }
