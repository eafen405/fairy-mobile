param(
    [string]$AvdName = "Pixel_Tablet",
    [string]$Serial = "emulator-5554",
    [ValidateSet("all", "phone", "tablet")]
    [string]$Profile = "all",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Program Files\Android\Android-SDK" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avdHome = Join-Path $sdk ".android\avd"
$package = "com.newoether.agora.screenshots"
$activity = "$package/com.newoether.agora.MainActivity"
$conversationId = "7a294d3f-b710-4c8c-8e52-574825f3012e"
$imagesRoot = Join-Path $repo "fastlane\metadata\android\en-US\images"
$phoneOutput = Join-Path $imagesRoot "phoneScreenshots"
$tabletOutput = Join-Path $imagesRoot "tenInchScreenshots"
$assets = Join-Path $repo "assets"
$apk = Join-Path $repo "app\build\outputs\apk\play\debug\app-play-debug.apk"

function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
}

function Wait-ForBoot {
    $deadline = (Get-Date).AddMinutes(4)
    $state = ""
    $boot = ""
    do {
        Start-Sleep -Seconds 2
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $state = (& $adb -s $Serial get-state 2>$null | Out-String).Trim()
        $boot = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        $ErrorActionPreference = $previousPreference
    } while ((($state -ne "device") -or ($boot -ne "1")) -and (Get-Date) -lt $deadline)
    if ($state -ne "device" -or $boot -ne "1") { throw "Emulator did not boot" }
}

function Set-CaptureProfile([string]$Profile) {
    if ($Profile -eq "phone") {
        Invoke-Adb shell wm size 1216x2400 | Out-Null
        Invoke-Adb shell wm density 420 | Out-Null
        Invoke-Adb shell wm user-rotation lock 0 | Out-Null
    } else {
        Invoke-Adb shell wm size 2560x1600 | Out-Null
        Invoke-Adb shell wm density 320 | Out-Null
        Invoke-Adb shell wm user-rotation lock 0 | Out-Null
    }
    Start-Sleep -Seconds 2
}

function Capture-Screenshot(
    [int]$Index,
    [string]$Destination,
    [string]$Profile,
    [string]$OutputDirectory
) {
    Invoke-Adb shell am force-stop $package
    $startArguments = @(
        "shell", "am", "start", "-W", "-S", "--activity-clear-task", "-n", $activity,
        "--es", "com.newoether.agora.extra.SCREENSHOT_DESTINATION", $Destination,
        "--es", "com.newoether.agora.extra.CONVERSATION_ID", $conversationId
    )
    Invoke-Adb @startArguments | Out-Null
    Start-Sleep -Seconds 5

    if ($Destination -eq "chat") {
        1..6 | ForEach-Object {
            Invoke-Adb shell input swipe 608 520 608 1900 250 | Out-Null
            Start-Sleep -Milliseconds 150
        }
    }

    # ESC hides an IME without dismissing the drawer or a settings destination.
    Invoke-Adb shell input keyevent 111 | Out-Null
    Start-Sleep -Milliseconds 600

    $stem = "agora-$Profile-screenshot-$Index"
    $png = Join-Path $env:TEMP "$stem.png"
    $jpg = Join-Path $OutputDirectory "screenshot_$Index.jpg"
    $remotePng = "/data/local/tmp/$stem.png"
    $captureArguments = @("shell", "screencap", "-p", $remotePng)
    Invoke-Adb @captureArguments | Out-Null
    Invoke-Adb pull $remotePng $png | Out-Null
    Invoke-Adb shell rm -f $remotePng | Out-Null

    if ($Profile -eq "tablet") {
        & ffmpeg -y -loglevel error -i $png -q:v 2 $jpg
    } else {
        $filter = "delogo=x=498:y=30:w=220:h=36:show=0,delogo=x=398:y=2360:w=420:h=38:show=0"
        & ffmpeg -y -loglevel error -i $png -vf $filter -q:v 2 $jpg
    }
    if ($LASTEXITCODE -ne 0) { throw "Unable to encode $Profile screenshot $Index" }
    Remove-Item $png -Force
}

foreach ($directory in @($phoneOutput, $tabletOutput, $assets)) {
    if (-not (Test-Path $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
}

if (-not $SkipBuild) {
    $env:GRADLE_USER_HOME = "C:\Users\newoether\.gradle"
    & (Join-Path $repo "gradlew.bat") :app:assemblePlayDebug --gradle-user-home $env:GRADLE_USER_HOME
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed" }
}
if (-not (Test-Path $apk)) { throw "Debug APK not found: $apk" }

$connected = (& $adb devices | Out-String) -match "(?m)^$([regex]::Escape($Serial))\s+device$"
if (-not $connected) {
    $env:ANDROID_AVD_HOME = $avdHome
    Start-Process -FilePath $emulator -ArgumentList @(
        "-avd", $AvdName,
        "-no-snapshot-save",
        "-no-boot-anim",
        "-no-audio",
        "-no-metrics",
        "-gpu", "host"
    ) | Out-Null
}
Wait-ForBoot

Invoke-Adb shell settings put system accelerometer_rotation 0 | Out-Null
Invoke-Adb shell settings put secure stylus_handwriting_enabled 0 | Out-Null
Invoke-Adb shell settings put secure stylus_pointer_icon_enabled 0 | Out-Null
Invoke-Adb shell settings put global window_animation_scale 0 | Out-Null
Invoke-Adb shell settings put global transition_animation_scale 0 | Out-Null
Invoke-Adb shell settings put global animator_duration_scale 0 | Out-Null
Invoke-Adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural | Out-Null
Invoke-Adb shell cmd overlay enable com.android.internal.systemui.navbar.transparent | Out-Null
Invoke-Adb install -r -t $apk | Out-Null
Invoke-Adb shell pm clear $package | Out-Null
Invoke-Adb shell pm grant $package android.permission.POST_NOTIFICATIONS | Out-Null

$destinations = @(
    "chat",
    "drawer",
    "settings",
    "settings:shell",
    "settings:memory",
    "settings:datacontrol"
)
$profiles = @(
    @{ Name = "phone"; Output = $phoneOutput },
    @{ Name = "tablet"; Output = $tabletOutput }
) | Where-Object { $Profile -eq "all" -or $_.Name -eq $Profile }

foreach ($profile in $profiles) {
    Set-CaptureProfile -Profile $profile.Name
    for ($index = 0; $index -lt $destinations.Count; $index++) {
        Capture-Screenshot `
            -Index ($index + 1) `
            -Destination $destinations[$index] `
            -Profile $profile.Name `
            -OutputDirectory $profile.Output
    }
}

1..3 | ForEach-Object {
    Copy-Item (Join-Path $phoneOutput "screenshot_$_.jpg") (Join-Path $assets "screenshot_$_.jpg") -Force
}

Write-Host "Generated six phone screenshots, six ten-inch tablet screenshots, and synchronized README screenshots 1-3."
