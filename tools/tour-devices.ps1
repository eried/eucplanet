# Multi-device screenshot tour for the Garmin Connect IQ watch app.
# For each device: builds the .prg, sideloads via monkeydo, waits for the
# dial to render with demo data, captures the simulator window via
# PrintWindow (which works even when the window is occluded), and saves
# the PNG into .playwright-mcp/.
#
# Usage:
#   pwsh -NoProfile -File tools/tour-devices.ps1 [-Devices fenix843mm,fr970]
#
# Stops the simulator between devices is not necessary — monkeydo reuses
# the running simulator.exe instance and just swaps the active device.

param(
    [string[]]$Devices = @(
        "fenix843mm",
        "fenix847mm",
        "fenix8solar47mm",
        "fenix8solar51mm",
        "fr970",
        "venu445mm",
        "vivoactive6",
        "edge1040"
    )
)

$ErrorActionPreference = "Continue"
$Sdk = "C:\Users\erwin\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b"
$Project = "D:\Downloads\eucplanet-garmin\garmin-watch-app"
$OutDir = "D:\Downloads\eucplanet-garmin\.playwright-mcp"
$Key = "$Project\developer_key.der"

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Tour {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@
Add-Type -AssemblyName System.Drawing

foreach ($d in $Devices) {
    Write-Output "=== $d ==="

    # Build .prg for this device
    $prg = "$Project\build\EucPlanet-$d.prg"
    $build = & "$Sdk\bin\monkeyc.bat" -f "$Project\monkey.jungle" -o $prg -y $Key -d $d 2>&1
    $buildLast = $build | Select-Object -Last 1
    if ($buildLast -notmatch "BUILD SUCCESSFUL") {
        Write-Output "BUILD FAILED for $d : $buildLast"
        continue
    }
    Write-Output "  built $d"

    # Sideload via monkeydo. Run in background so we can move on.
    Start-Process -FilePath "$Sdk\bin\monkeydo.bat" -ArgumentList "$prg $d" -NoNewWindow -RedirectStandardOutput "$OutDir\md-$d.log" -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 7  # demo data preload fires after 4s; buffer for safety

    # Snapshot via PrintWindow
    $sim = Get-Process simulator -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $sim) {
        Write-Output "  simulator not running, skipping snapshot"
        continue
    }
    $r = New-Object Tour+RECT
    [Tour]::GetWindowRect($sim.MainWindowHandle, [ref]$r) | Out-Null
    $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
    if ($w -le 0 -or $h -le 0) {
        Write-Output "  bad sim rect, skipping"
        continue
    }
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    [Tour]::PrintWindow($sim.MainWindowHandle, $hdc, 2) | Out-Null
    $g.ReleaseHdc($hdc); $g.Dispose()
    $out = "$OutDir\tour-$d.png"
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "  saved $out"
}

Write-Output "`nDone. Screenshots in $OutDir\tour-*.png"
