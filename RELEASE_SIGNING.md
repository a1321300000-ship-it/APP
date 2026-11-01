# כספת – חתימה והפצת גרסאות

המאגר אינו כולל קובץ מפתח חתימה. המאגר ציבורי, ולכן אין להעלות אליו keystore או סיסמאות.

יש להגדיר ב-GitHub תחת Settings → Secrets and variables → Actions את ארבעת ה-Secrets הבאים:
- VAULT_KEYSTORE_BASE64 – תוכן Base64 של הקובץ vault-release-key.jks
- VAULT_KEYSTORE_PASSWORD – סיסמת ה-keystore
- VAULT_KEY_ALIAS – vault-release
- VAULT_KEY_PASSWORD – סיסמת המפתח

לאחר הגדרת ה-Secrets, הפעל את workflow בשם Build and publish Vault APK דרך Actions → Run workflow.
כאשר כל ארבעת ה-Secrets זמינים, ה-workflow חותם גם Debug וגם Release באותו מפתח, מאמת את חתימת ה-APK, ומחליף את vault.apk תחת ה-Release הקבוע vault-latest.

קישור קבוע לנכס ה-Release:
https://github.com/a1321300000-ship-it/APP/releases/download/vault-latest/vault.apk

גרסת הקוד הנוכחית: versionCode 2, versionName 1.1.
applicationId נשאר he.vault.app.
