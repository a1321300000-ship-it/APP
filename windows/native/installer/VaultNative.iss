#define AppName "כספת"
#define AppVersion "1.1.0"
#define AppExeName "VaultNative.exe"

[Setup]
AppId={{7E8C67E7-76A0-4AC2-8B26-A1441F529EA2}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Vault
DefaultDirName={localappdata}\Programs\כספת
DefaultGroupName=כספת
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\artifacts
OutputBaseFilename=Vault-Windows-Native-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=כספת
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "he"; MessagesFile: "compiler:Languages\Hebrew.isl"

[Tasks]
Name: "desktopicon"; Description: "יצירת קיצור דרך בשולחן העבודה"; GroupDescription: "אפשרויות נוספות:"; Flags: unchecked

[Files]
Source: "..\..\..\build\native\Release\VaultNative.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\כספת"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\כספת"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "הפעלת כספת"; Flags: postinstall nowait skipifsilent
