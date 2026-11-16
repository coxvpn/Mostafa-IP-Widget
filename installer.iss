; Inno Setup script - builds Mostafa-IP-Widget-Setup.exe from the PyInstaller output in dist\Mostafa-IP-Widget
#define AppName "Mostafa-IP-Widget"
#define AppVersion "0.3.0"
#define AppExe "Mostafa-IP-Widget.exe"

[Setup]
AppId={{D5FE5497-F778-4CD9-90BC-1B35C2F9D6DC}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Mostafa Mandegari
AppCopyright=Copyright (c) Mostafa Mandegari
VersionInfoVersion={#AppVersion}
VersionInfoCompany=Mostafa Mandegari
VersionInfoDescription={#AppName} Setup
DefaultDirName={autopf}\Mostafa-IP-Widget
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=installer_output
OutputBaseFilename=Mostafa-IP-Widget-Setup
SetupIconFile=assets\images\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked
Name: "startup"; Description: "Start {#AppName} when Windows starts"

[Files]
Source: "dist\Mostafa-IP-Widget\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; The value name must match APP_NAME in ipcore.py so the in-app switch sees the same entry.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"""; Flags: uninsdeletevalue; Tasks: startup

[InstallDelete]
Type: files; Name: "{app}\MyIPWidget.exe"
Type: files; Name: "{autoprograms}\MyIP Widget.lnk"
Type: files; Name: "{autodesktop}\MyIP Widget.lnk"

[Run]
Filename: "{app}\{#AppExe}"; Description: "Launch {#AppName}"; Flags: nowait postinstall skipifsilent
