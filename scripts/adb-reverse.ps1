# ทำให้แอปบน MuMu / emulator / มือถือ (USB) เรียก http://localhost:3000 แล้วไปถึง backend บน Windows
# ต้องรันใหม่ทุกครั้งที่เปิด MuMu (กฎ reverse หายตอนอุปกรณ์รีสตาร์ท)
#
# ใช้: powershell -File scripts\adb-reverse.ps1
#      powershell -File scripts\adb-reverse.ps1 -Port 3000 -MuMu 127.0.0.1:16384

param(
    [int]$Port = 3000,
    # พอร์ต adb ของ MuMu 12 (ดูได้จาก MuMuManager.exe info -v all)
    [string]$MuMu = "127.0.0.1:16384"
)

$ErrorActionPreference = "Stop"

# ใช้ adb ของ Android SDK ตัวเดียว (ห้ามปนกับ adb ที่มากับ MuMu เพราะจะแย่งพอร์ต 5037 กัน)
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "ไม่พบ adb ที่ $adb — ติดตั้ง Android SDK Platform-Tools ผ่าน Android Studio ก่อน" }

# ต่อ MuMu (ถ้าไม่ได้เปิด MuMu อยู่ คำสั่งนี้จะล้มเหลวแต่ไม่เป็นไร ถ้ามีมือถือเสียบ USB อยู่)
& $adb connect $MuMu | Out-Null

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } |
    ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devices) { throw "ไม่พบอุปกรณ์ Android — เปิด MuMu หรือเสียบมือถือ (เปิด USB debugging) ก่อน" }

# MuMu โผล่สองชื่อ (127.0.0.1:16384 และ emulator-5554) เป็นเครื่องเดียวกัน ตั้งกฎกับทุกชื่อไม่เสียหาย
foreach ($d in $devices) {
    & $adb -s $d reverse "tcp:$Port" "tcp:$Port" | Out-Null
    Write-Host "reverse tcp:$Port ตั้งแล้วที่ $d"
}
