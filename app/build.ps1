# OAC 1.4 (TLS-fixed) build script  --  Windows / PowerShell
# Standard legacy Android project (Eclipse/Ant layout): src/ res/ libs/ AndroidManifest.xml
# Requires: Android SDK with build-tools 30.0.3 (or edit $BT below) and a platform android.jar.
# No .so / no native libs. minSdk 9 (Android 2.3). SpongyCastle provides TLS.

$ErrorActionPreference = "Stop"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---- SDK location: prefer local.properties, else default ----
$sdkDir = $null
if (Test-Path "$ROOT\local.properties") {
    foreach ($line in (Get-Content "$ROOT\local.properties")) {
        if ($line -match '^\s*sdk\.dir\s*=(.*)$') { $sdkDir = $Matches[1].Trim() }
    }
}
if (-not $sdkDir) { $sdkDir = "C:\AndroidSDK" }

$BT   = "$sdkDir\build-tools\30.0.3"
$PLAT = "$sdkDir\platforms\android-28\android.jar"
if (-not (Test-Path $PLAT)) {
    # fall back to whatever platform jar exists
    $p = Get-ChildItem -Path "$sdkDir\platforms" -Filter android.jar -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($p) { $PLAT = $p.FullName } else { throw "Cannot find android.jar under $sdkDir\platforms" }
}
$KS = "$ROOT\oac.keystore"

Set-Location $ROOT

Write-Host "==> SDK  : $sdkDir"
Write-Host "==> PLAT : $PLAT"
Write-Host "==> BT   : $BT"

if (-not (Test-Path "$BT\aapt.exe")) { throw "aapt.exe not found in $BT" }

Write-Host "==> clean"
Remove-Item -Recurse -Force gen, obj, bin -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path gen, obj, bin | Out-Null

Write-Host "==> 1) aapt R.java"
& "$BT\aapt.exe" package -f -M AndroidManifest.xml -S res -I $PLAT -J gen -m
if ($LASTEXITCODE -ne 0) { throw "aapt R.java failed" }

Write-Host "==> 2) javac"
$srcs = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rgen = Get-ChildItem -Path gen -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$all  = @($srcs) + @($rgen)
$libs = Get-ChildItem -Path libs -Recurse -Filter *.jar | ForEach-Object { $_.FullName }
$cp = $PLAT
if ($libs) { $cp = $PLAT + ";" + ($libs -join ";") }

$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacOut = & javac -source 1.8 -target 1.8 -encoding UTF-8 -classpath $cp -d obj $all 2>&1 | Out-String
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($javacExit -ne 0) { Write-Host $javacOut; throw "javac failed" }

Write-Host "==> 3) jar + d8 (min-api 9)"
Push-Location obj
& jar cf "$ROOT\bin\classes.jar" -C . .
Pop-Location
$libJars = Get-ChildItem -Path libs -Filter *.jar | ForEach-Object { $_.FullName }
$d8Args = @("--output", "$ROOT\bin\classes-dex.zip", "--lib", $PLAT, "--min-api", "9", "$ROOT\bin\classes.jar")
if ($libJars) { $d8Args += $libJars }
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& java -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 $d8Args 2>&1 | Out-String
$d8Exit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($d8Exit -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory("$ROOT\bin\classes-dex.zip", "$ROOT\bin\dex_extract")
Get-ChildItem "$ROOT\bin\dex_extract\classes*.dex" | ForEach-Object {
    Move-Item $_.FullName "$ROOT\bin\$($_.Name)" -Force
}
Remove-Item -Recurse -Force "$ROOT\bin\classes-dex.zip", "$ROOT\bin\dex_extract"

Write-Host "==> 4) aapt package (resources)"
& "$BT\aapt.exe" package -f -M AndroidManifest.xml -S res -I $PLAT -F "$ROOT\bin\app.unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt package failed" }

Write-Host "==> 5) add classes.dex (multidex aware)"
Push-Location "$ROOT\bin"
Get-ChildItem "$ROOT\bin\classes*.dex" | ForEach-Object {
    & "$BT\aapt.exe" add app.unsigned.apk $_.Name | Out-Null
}
Pop-Location

if (-not (Test-Path $KS)) {
    Write-Host "==> generate keystore (oacbeta / oacbeta123)"
    $ktArgs = @("-genkeypair","-keystore",$KS,"-storetype","JKS","-storepass","oacbeta123",
        "-alias","oacbeta","-keypass","oacbeta123","-keyalg","RSA","-keysize","2048","-validity","20000",
        "-dname","CN=OAC,O=OAC,C=CN")
    $p = Start-Process -FilePath "keytool" -ArgumentList $ktArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput "$ROOT\kt_out.txt" -RedirectStandardError "$ROOT\kt_err.txt"
    if ($p.ExitCode -ne 0 -or -not (Test-Path $KS)) {
        Write-Host (Get-Content "$ROOT\kt_err.txt" -Raw); throw "keystore generation failed"
    }
    Remove-Item "$ROOT\kt_out.txt","$ROOT\kt_err.txt" -ErrorAction SilentlyContinue
}

Write-Host "==> 6) zipalign"
& "$BT\zipalign.exe" -f -p 4 "$ROOT\bin\app.unsigned.apk" "$ROOT\bin\app.aligned.apk" 2>&1 | Select-Object -Last 1

Write-Host "==> 7) apksigner"
& "$BT\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true `
    --ks $KS --ks-pass pass:oacbeta123 --ks-key-alias oacbeta --key-pass pass:oacbeta123 `
    --out "$ROOT\bin\OAC_1.4.apk" "$ROOT\bin\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "BUILD OK: $ROOT\bin\OAC_1.4.apk"
$size = (Get-Item "$ROOT\bin\OAC_1.4.apk").Length
Write-Host ("Size: {0:N0} bytes ({1:N2} MB)" -f $size, ($size/1MB))
