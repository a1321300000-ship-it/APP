# כספת ל-Windows

יישום שולחני מקומי ל-Windows, המותאם לאפליקציית Android כספת.

## תאימות נתונים

מימוש Windows משתמש באותו פורמט credentials, באותו PBKDF2-HMAC-SHA1 (150,000 איטרציות), באותו AES-256-GCM ובאותו מבנה של קובצי הנתונים וגיבויי VLTBACK1. לכן ניתן ליצור גיבוי ‎.vault באפליקציית Android ולשחזר אותו בגרסת Windows, ולהפך.

הנתונים המקומיים של Windows נשמרים תחת:
%LOCALAPPDATA%\כספת\vault

## בנייה

נדרש .NET 8 SDK ו-Inno Setup. קובץ ההתקנה נבנה מתוך windows/installer/Vault.iss.