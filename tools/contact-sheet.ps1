# Builds a contact sheet from tour-<device>.png files: 4 columns, 2 rows
# (or however many fit), each tile labeled with the device id. Saves to
# .playwright-mcp\contact-sheet.png so we can review the full device
# matrix in one image rather than scrolling 8 screenshots.

$Dir = "D:\Downloads\eucplanet-garmin\.playwright-mcp"
Add-Type -AssemblyName System.Drawing

$files = Get-ChildItem "$Dir\tour-*.png" | Sort-Object Name
if ($files.Count -eq 0) { Write-Output "No tour-*.png yet"; exit 1 }

$cols = 4
$rows = [Math]::Ceiling($files.Count / $cols)
$tileW = 380
$tileH = 540
$labelH = 32
$pad = 12
$sheetW = ($tileW + $pad) * $cols + $pad
$sheetH = ($tileH + $labelH + $pad) * $rows + $pad

$sheet = New-Object System.Drawing.Bitmap $sheetW, $sheetH
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 30, 30, 30))
$g.FillRectangle($bg, 0, 0, $sheetW, $sheetH)

$labelFont = New-Object System.Drawing.Font("Segoe UI", 12, [System.Drawing.FontStyle]::Bold)
$labelBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)

for ($i = 0; $i -lt $files.Count; $i += 1) {
    $col = $i % $cols
    $row = [Math]::Floor($i / $cols)
    $x = $pad + $col * ($tileW + $pad)
    $y = $pad + $row * ($tileH + $labelH + $pad)

    $img = [System.Drawing.Image]::FromFile($files[$i].FullName)

    # Scale-to-fit while preserving aspect ratio
    $srcRatio = $img.Width / $img.Height
    $dstRatio = $tileW / $tileH
    if ($srcRatio -gt $dstRatio) {
        $drawW = $tileW
        $drawH = [int]($tileW / $srcRatio)
    } else {
        $drawH = $tileH
        $drawW = [int]($tileH * $srcRatio)
    }
    $offX = $x + (($tileW - $drawW) / 2)
    $offY = $y + (($tileH - $drawH) / 2)
    $g.DrawImage($img, [int]$offX, [int]$offY, [int]$drawW, [int]$drawH)
    $img.Dispose()

    # Label
    $name = $files[$i].BaseName -replace "^tour-", ""
    $labelRect = New-Object System.Drawing.RectangleF $x, ($y + $tileH), $tileW, $labelH
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($name, $labelFont, $labelBrush, $labelRect, $sf)
}

$g.Dispose()
$out = "$Dir\contact-sheet.png"
$sheet.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Output "Saved $out ($sheetW x $sheetH)"
