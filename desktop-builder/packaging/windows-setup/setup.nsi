; Uses no plug-ins. The zlib stub imports only Windows inbox libraries.
Unicode true
RequestExecutionLevel user
SetCompressor /FINAL zlib
CRCCheck force
SilentInstall silent
AutoCloseWindow true
Name "OpenAutoLink Builder"
OutFile "OpenAutoLink-Bootstrap.exe"
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "OpenAutoLink Builder bootstrap"
VIAddVersionKey "FileDescription" "OpenAutoLink Builder - Microsoft runtime downloaded on demand"
VIAddVersionKey "FileVersion" "1.0.0.0"
VIAddVersionKey "LegalCopyright" "OpenAutoLink contributors; NSIS contributors"

Section
  SetShellVarContext current
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  File "builder.exe"
  File "bootstrap.ps1"
  File "setup-manifest.json"
  File "NSIS-COPYING.txt"
  File "BOOTSTRAP-NOTICE.txt"
  ; An extraction route, not an app/consent bypass. No runtime or builder runs.
  ReadEnvStr $0 "OAL_SETUP_EXTRACT_ONLY"
  StrCmp $0 "" launch
  ClearErrors
  CreateDirectory "$0"
  CopyFiles /SILENT "$PLUGINSDIR\builder.exe" "$0\builder.exe"
  CopyFiles /SILENT "$PLUGINSDIR\bootstrap.ps1" "$0\bootstrap.ps1"
  CopyFiles /SILENT "$PLUGINSDIR\setup-manifest.json" "$0\setup-manifest.json"
  CopyFiles /SILENT "$PLUGINSDIR\NSIS-COPYING.txt" "$0\NSIS-COPYING.txt"
  CopyFiles /SILENT "$PLUGINSDIR\BOOTSTRAP-NOTICE.txt" "$0\BOOTSTRAP-NOTICE.txt"
  IfErrors extraction_failed
  SetErrorLevel 0
  Quit
extraction_failed:
  SetErrorLevel 2
  Quit
launch:
  ; NSIS is x86, Sysnative explicitly selects inbox 64-bit Windows PowerShell.
  IfFileExists "$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" supported
  MessageBox MB_OK|MB_ICONSTOP "OpenAutoLink Builder requires Windows 10 version 1809 or newer, or Windows 11, on an x64 PC, with Windows PowerShell available."
  SetErrorLevel 3
  Quit
supported:
  ClearErrors
  ExecWait '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "$PLUGINSDIR\bootstrap.ps1"' $0
  IfErrors launch_failed
  StrCmp $0 "0" done
  ; PowerShell normally supplies the specific error; this also covers policy blocks.
  MessageBox MB_OK|MB_ICONSTOP "OpenAutoLink Builder did not start or could not complete (code $0). If Windows blocked PowerShell or this unsigned app, ask your administrator. No security policy was changed. If Microsoft requested a restart, restart Windows and open this same file again."
  SetErrorLevel $0
  Quit
launch_failed:
  MessageBox MB_OK|MB_ICONSTOP "Windows could not start its PowerShell bootstrap. Ask your administrator about application-control policy. No builder tools were installed."
  SetErrorLevel 4
  Quit
done:
  SetErrorLevel 0
SectionEnd
