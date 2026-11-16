#define AppName "כספת"
#define AppVersion "1.0.0"
#define AppExeName "Vault.Windows.exe"

[Setup]
AppId={{7E8C67E7-76A0-4AC2-8B26-A1441F529EA2}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Vault
DefaultDirName={localappdata}\Programs\כספת
DefaultGroupName=כספת
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\artifacts
OutputBaseFilename=Vault-Windows-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=כספת
UninstallFilesDir={app}\Uninstall
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "he"; MessagesFile: "compiler:Languages\Hebrew.isl"

[Tasks]
Name: "desktopicon"; Description: "יצירת קיצור דרך בשולחן העבודה"; GroupDescription: "אפשרויות נוספות:"; Flags: unchecked

[Files]
Source: "..\Vault.Windows\publish\Vault.Windows.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\כספת"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\כספת"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Code]
function HasDesktopRuntimeAt(const DotNetRoot: String): Boolean;
var
  FindRec: TFindRec;
  RuntimeMask: String;
begin
  Result := False;
  RuntimeMask := AddBackslash(DotNetRoot) + 'shared\Microsoft.WindowsDesktop.App\10.*';
  if FindFirst(RuntimeMask, FindRec) then
  begin
    try
      repeat
        if ((FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0) and
           (FindRec.Name <> '.') and (FindRec.Name <> '..') then
        begin
          Result := True;
          Break;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

function HasDotNet10DesktopRuntime: Boolean;
begin
  Result :=
    HasDesktopRuntimeAt(ExpandConstant('{autopf}\dotnet')) or
    HasDesktopRuntimeAt(ExpandConstant('{localappdata}\Microsoft\dotnet')) or
    HasDesktopRuntimeAt(GetEnv('USERPROFILE') + '\.dotnet');
end;

function InitializeSetup(): Boolean;
var
  ErrorCode: Integer;
begin
  Result := True;
  if not HasDotNet10DesktopRuntime then
  begin
    if MsgBox(
      'כדי להפעיל את כספת למחשב נדרש Microsoft .NET 10 Desktop Runtime (64-bit).' + #13#10 + #13#10 +
      'הרכיב אינו כלול במתקין הקטן. יש להוריד ולהתקין אותו פעם אחת מאתר Microsoft. לאחר ההתקנה ניתן להשתמש בכספת גם ללא חיבור לאינטרנט.' + #13#10 + #13#10 +
      'לפתוח עכשיו את עמוד ההורדה הרשמי?',
      mbConfirmation, MB_YESNO) = IDYES then
    begin
      ShellExec('open',
        'https://dotnet.microsoft.com/en-us/download/dotnet/10.0',
        '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
    end;
    Result := False;
  end;
end;

[Run]
Filename: "{app}\{#AppExeName}"; Description: "הפעלת כספת"; Flags: postinstall nowait skipifsilent