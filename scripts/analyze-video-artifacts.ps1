$logs = Get-ChildItem D:\personal\logs\oal_*.log | Sort-Object Name
foreach ($f in $logs) {
  $c = Get-Content $f.FullName
  $matches = $c | Select-String "Keyframe|IDR|Codec|video:|MicChannel|VoiceSession|call|CallState|HFP|onPause|onResume|onStop|onTopResumed|surfaceChanged|surfaceCreated|surfaceDestroyed|Surface attached|Surface stabilized|reconfigure|reset|flush"
  if ($matches.Count -lt 5) { continue }
  Write-Host "=== $($f.Name) ==="
  $matches | ForEach-Object { $_.Line }
}
