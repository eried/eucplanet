# Bundle the Connect IQ SDK (Linux build) + the 7 device profiles used by CI
# into a single zip that the GitHub Actions workflows download via the
# GARMIN_SDK_BUNDLE_URL secret. Run this once on the machine that has the
# CIQ SDK installed; re-run after upgrading the SDK or adding devices to the
# bundle.
#
# Output layout (inside the zip):
#   ciq-bundle/
#     sdk/          contents of the connectiq-sdk-lin-*.zip the user downloaded
#       bin/monkeyc
#       bin/monkeydo
#       ...
#     devices/      one directory per device profile (compiler.json + assets)
#       venu2/
#       venu3/
#       fenix843mm/
#       fenix847mm/
#       fenix6xpro/
#       fenix8solar47mm/
#       epix2pro47mm/
#
# Why a separate Linux SDK download (instead of zipping the Windows SDK the
# user already has installed): the CI runner is Ubuntu, and monkeyc ships
# platform-specific JRE + native helpers per OS. The Linux SDK zip is what
# Garmin's SDK Manager offers under "Other SDKs"; download it once, point
# this script at it.

param(
    [Parameter(Mandatory = $true)]
    [string] $LinuxSdkZip,

    [string] $OutputZip = "$PSScriptRoot\..\ciq-bundle.zip",

    [string[]] $Devices = @(
        'venu2',
        'venu3',
        'fenix843mm',
        'fenix847mm',
        'fenix6xpro',
        'fenix8solar47mm',
        'epix2pro47mm'
    )
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $LinuxSdkZip)) {
    throw "Linux SDK zip not found at $LinuxSdkZip. Download it from the Connect IQ SDK Manager (Other SDKs -> Linux) and pass its path via -LinuxSdkZip."
}

$devRoot = Join-Path $env:APPDATA 'Garmin\ConnectIQ\Devices'
if (-not (Test-Path $devRoot)) {
    throw "Device profile root not found at $devRoot. Open the SDK Manager and install the device profiles first."
}

$staging = Join-Path $env:TEMP "ciq-bundle-stage-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$bundleRoot = Join-Path $staging 'ciq-bundle'
$sdkOut = Join-Path $bundleRoot 'sdk'
$devOut = Join-Path $bundleRoot 'devices'
New-Item -ItemType Directory -Path $sdkOut, $devOut -Force | Out-Null

Write-Host "Extracting $LinuxSdkZip -> $sdkOut"
Expand-Archive -Path $LinuxSdkZip -DestinationPath $sdkOut -Force

# Garmin's Linux SDK zip contains a single top-level directory; flatten it so
# the bundle always has sdk/bin/monkeyc regardless of the zip's wrapper name.
$inner = Get-ChildItem $sdkOut -Directory
if ($inner.Count -eq 1 -and (Test-Path (Join-Path $inner[0].FullName 'bin'))) {
    $tmp = Join-Path $staging '_flatten'
    Move-Item -Path $inner[0].FullName -Destination $tmp
    Remove-Item $sdkOut -Recurse -Force
    Move-Item -Path $tmp -Destination $sdkOut
}

if (-not (Test-Path (Join-Path $sdkOut 'bin\monkeyc'))) {
    throw "After extraction, sdk/bin/monkeyc is missing. Is $LinuxSdkZip really the Linux build of the SDK?"
}

foreach ($d in $Devices) {
    $src = Join-Path $devRoot $d
    if (-not (Test-Path $src)) {
        throw "Device profile '$d' not installed at $src. Open SDK Manager -> Devices and install it."
    }
    Write-Host "Copying device profile: $d"
    Copy-Item -Path $src -Destination (Join-Path $devOut $d) -Recurse
}

if (Test-Path $OutputZip) { Remove-Item $OutputZip }
Write-Host "Compressing -> $OutputZip"
Compress-Archive -Path (Join-Path $staging 'ciq-bundle') -DestinationPath $OutputZip

Remove-Item $staging -Recurse -Force

$size = (Get-Item $OutputZip).Length
Write-Host ''
Write-Host "Bundle ready: $OutputZip ($('{0:N1} MB' -f ($size / 1MB)))"
Write-Host ''
Write-Host 'Next steps:'
Write-Host '  1. Upload as a GitHub release asset, e.g. on a stable "ci-tools" release:'
Write-Host "       gh release create ci-tools --prerelease --notes 'CI build dependencies' $OutputZip"
Write-Host '     (or, on an existing release: gh release upload ci-tools $OutputZip --clobber)'
Write-Host '  2. Copy the asset download URL from the release page.'
Write-Host '  3. Set the GARMIN_SDK_BUNDLE_URL repo secret to that URL:'
Write-Host '       Settings -> Secrets and variables -> Actions -> New repository secret'
Write-Host '  4. The next push to a branch will produce the .iq + per-device .prg files.'
