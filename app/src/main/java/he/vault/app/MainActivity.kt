package he.vault.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: VaultStore
    private lateinit var root: LinearLayout
    private var masterKey: ByteArray? = null
    private var items: MutableList<VaultItem> = mutableListOf()
    private var needsRelock = false
    private var pendingAction: PendingAction? = null
    private var scareDialog: Dialog? = null
    private var previewDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var searchText = ""
    private var sortMode = "date"

    private sealed class PendingAction {
        data class Import(val uris: List<Uri>) : PendingAction()
        data class Export(val item: VaultItem, val uri: Uri) : PendingAction()
        data class Backup(val uri: Uri) : PendingAction()
        data class WaitExport(val item: VaultItem) : PendingAction()
        object WaitBackup : PendingAction()
    }

    companion object {
        private const val REQ_IMPORT = 101
        private const val REQ_EXPORT = 102
        private const val REQ_BACKUP = 103
        private const val REQ_RESTORE = 104
        private const val BG = 0xFF090D16.toInt()
        private const val PANEL = 0xFF141D2B.toInt()
        private const val PANEL_2 = 0xFF1C293A.toInt()
        private const val ACCENT = 0xFF55D6BE.toInt()
        private const val WHITE = 0xFFF4F7FB.toInt()
        private const val MUTED = 0xFF9AA8B9.toInt()
        private const val RED = 0xFFFF647C.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        store = VaultStore(this)
        if (store.hasCredentials()) showUnlock() else showSetup()
    }

    override fun onPause() {
        super.onPause()
        masterKey?.fill(0)
        masterKey = null
        previewDialog?.dismiss()
        previewDialog = null
        items = mutableListOf()
        File(filesDir, "vault/.tmp").apply { mkdirs() }.listFiles()
            ?.filter { it.name.startsWith("preview-") }
            ?.forEach { it.delete() }
        if (store.hasCredentials()) needsRelock = true
    }

    override fun onResume() {
        super.onResume()
        if (needsRelock && store.hasCredentials()) {
            needsRelock = false
            showUnlock()
        }
    }

    override fun onDestroy() {
        masterKey?.fill(0)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun newRoot(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        setContentView(root)
        return root
    }

    private fun title(text: String, size: Float = 25f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(WHITE)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun body(text: String, size: Float = 15f, color: Int = MUTED): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun makeButton(text: String, onClick: () -> Unit, filled: Boolean = false): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(if (filled) BG else WHITE)
        background = GradientDrawable().apply {
            setColor(if (filled) ACCENT else PANEL_2)
            cornerRadius = dp(13).toFloat()
            if (!filled) setStroke(dp(1), 0xFF2A394D.toInt())
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, numeric: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 17f
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        setSingleLine(true)
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(PANEL_2); cornerRadius = dp(11).toFloat() }
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFF202D3F.toInt()) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun addGap(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun addTitleAndSubtitle(parent: LinearLayout, heading: String, subtitle: String) {
        parent.addView(title(heading))
        addGap(parent, 5)
        parent.addView(body(subtitle))
        addGap(parent, 18)
    }

    private fun showSetup() {
        val page = newRoot()
        page.addView(title("כספת", 32f))
        addGap(page, 8)
        page.addView(body("הגדרה ראשונית · יוצרים שני קודים לפתיחת אותה כספת"))
        addGap(page, 20)
        page.addView(card().apply {
            addView(title("שלב 1 · קוד מספרי", 18f))
            addGap(this, 10)
            val pin = input("קוד בן 4 עד 8 ספרות", true)
            val confirmPin = input("אימות הקוד", true)
            addView(pin)
            addGap(this, 8)
            addView(confirmPin)
            addGap(this, 14)
            addView(title("שלב 2 · קוד קווים", 18f))
            addGap(this, 5)
            addView(body("צייר לפחות 4 נקודות ברשת. לאחר מכן צייר שוב לאימות."))
            val patternView = PatternView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250))
            }
            addView(patternView)
            val patternStatus = body("טרם הוגדר קוד קווים", 14f, ACCENT)
            addView(patternStatus)
            var firstPattern: String? = null
            var confirmedPattern: String? = null
            patternView.onPatternComplete = { pattern ->
                if (firstPattern == null) {
                    firstPattern = pattern
                    patternStatus.text = "כעת צייר שוב את אותו הקוד לאימות"
                } else if (confirmedPattern == null) {
                    if (firstPattern == pattern) {
                        confirmedPattern = pattern
                        patternStatus.text = "קוד הקווים אומת בהצלחה"
                        patternStatus.setTextColor(ACCENT)
                    } else {
                        firstPattern = pattern
                        patternStatus.text = "הקווים לא תואמים. הקוד הראשון עודכן; צייר שוב לאימות"
                        patternStatus.setTextColor(RED)
                    }
                }
                patternView.clearPattern()
            }
            addGap(this, 15)
            addView(body("חשוב: אם תשכח את שני הקודים, אין שום דרך לשחזר את הקבצים.", 14f, RED))
            addGap(this, 12)
            addView(makeButton("יצירת הכספת", {
                val p = pin.text.toString()
                val cp = confirmPin.text.toString()
                when {
                    p.length !in 4..8 || !p.all { it.isDigit() } -> toast("הקוד המספרי חייב להכיל 4 עד 8 ספרות")
                    p != cp -> toast("הקודים המספריים אינם תואמים")
                    confirmedPattern == null -> toast("יש להגדיר ולאמת קוד קווים")
                    else -> {
                        val master = VaultCrypto.randomBytes(32)
                        store.saveCredentials(Credentials.create(master, p, confirmedPattern!!))
                        masterKey = master
                        items = mutableListOf()
                        store.saveIndex(items, master)
                        store.resetConsecutiveFailures()
                        showHome()
                    }
                }
            }, true))
        })
    }

    private fun showUnlock() {
        if (!store.hasCredentials()) {
            showSetup()
            return
        }
        val page = newRoot()
        page.gravity = Gravity.CENTER_HORIZONTAL
        page.addView(title("כספת", 32f))
        addGap(page, 8)
        page.addView(body("הזן אחד משני הקודים כדי לפתוח", 15f))
        addGap(page, 22)
        val lockout = store.preferenceLong("lockout_until", 0L)
        if (System.currentTimeMillis() < lockout) {
            val seconds = ((lockout - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            page.addView(body("הכספת נעולה זמנית. נסה שוב בעוד $seconds שניות.", 16f, RED))
            addGap(page, 15)
        }
        val pinCard = card()
        pinCard.addView(title("קוד מספרי", 19f))
        addGap(pinCard, 8)
        val pinInput = input("4 עד 8 ספרות", true)
        pinCard.addView(pinInput)
        addGap(pinCard, 10)
        pinCard.addView(makeButton("פתיחה עם קוד מספרי", {
            attemptUnlock(pinInput.text.toString(), "pin")
            pinInput.setText("")
        }, true))
        page.addView(pinCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addGap(page, 14)
        val patternCard = card()
        patternCard.addView(title("קוד קווים", 19f))
        addGap(patternCard, 6)
        patternCard.addView(body("צייר את הקוד האישי שלך ברשת"))
        val patternView = PatternView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
        }
        patternCard.addView(patternView)
        patternView.onPatternComplete = { pattern ->
            attemptUnlock(pattern, "pattern")
            patternView.clearPattern()
        }
        page.addView(patternCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addGap(page, 12)
        page.addView(body("כל הנתונים נשמרים במכשיר בלבד ומוצפנים.", 13f))
    }

    private fun attemptUnlock(code: String, type: String) {
        val until = store.preferenceLong("lockout_until", 0L)
        if (System.currentTimeMillis() < until) {
            toast("הכספת נעולה זמנית")
            return
        }
        if (code.isBlank()) {
            toast("יש להזין קוד")
            return
        }
        val credentials = store.loadCredentials()
        val master = credentials?.let { Credentials.unlock(it, code, type) }
        if (master == null) {
            val (_, consecutive) = store.recordFailure()
            val threshold = store.preferenceInt("scare_threshold", 2).coerceIn(1, 5)
            if (store.preferenceBoolean("scare_enabled", true) && consecutive % threshold == 0) showScare(false)
            if (consecutive >= 5) {
                val level = store.preferenceInt("lockout_level", 0).coerceIn(0, 6)
                val duration = 30_000L * (1L shl level)
                store.setPreferenceLong("lockout_until", System.currentTimeMillis() + duration)
                store.setPreferenceInt("lockout_level", (level + 1).coerceAtMost(6))
                store.resetConsecutiveFailures()
                handler.postDelayed({ if (!isFinishing) showUnlock() }, 500)
                toast("יותר מדי ניסיונות. הכספת ננעלה לזמן קצר.")
            } else {
                toast("קוד שגוי")
            }
            return
        }
        store.resetConsecutiveFailures()
        store.setPreferenceLong("lockout_until", 0L)
        masterKey?.fill(0)
        masterKey = master
        try {
            items = store.loadIndex(master)
            performPendingOrShowHome()
        } catch (e: Exception) {
            master.fill(0)
            masterKey = null
            toast("לא ניתן לפתוח את הכספת: ${e.message ?: "נתונים פגומים"}")
            showUnlock()
        }
    }

    private fun performPendingOrShowHome() {
        when (val pending = pendingAction) {
            is PendingAction.Import -> {
                pendingAction = null
                importUris(pending.uris, 0)
            }
            is PendingAction.Export -> {
                pendingAction = null
                performExport(pending.item, pending.uri)
            }
            is PendingAction.Backup -> {
                pendingAction = null
                performBackup(pending.uri)
            }
            else -> showHome()
        }
    }

    private fun showHome() {
        masterKey ?: run { showUnlock(); return }
        val page = newRoot()
        page.addView(title("הכספת שלך", 28f))
        addGap(page, 4)
        page.addView(body("${items.size} קבצים שמורים ומוצפנים במכשיר", 14f))
        addGap(page, 14)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        actions.addView(makeButton("＋ הכנסת קבצים", { confirmAction("הכנסת קבצים", "הקבצים יועתקו לכספת ויישמרו בהצפנה. לאחר ההעתקה תוכל לבחור אם למחוק את המקור.") { chooseImport() } }, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(makeButton("הגדרות", { showSettings() }), LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(actions)
        addGap(page, 8)
        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        actions2.addView(makeButton("גיבוי", { confirmAction("יצירת גיבוי", "ייווצר קובץ ‎.vault מוצפן במקום שתבחר. לא יישלח מידע לשום מקום.") { chooseBackup() } }, false), LinearLayout.LayoutParams(0, dp(44), 1f))
        actions2.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions2.addView(makeButton("שחזור", { confirmAction("שחזור מגיבוי", "השחזור יחליף את כל תוכן הכספת הנוכחית בתוכן הגיבוי. לאחר מכן תידרש להשתמש בקוד של הגיבוי.") { chooseRestore() } }, false), LinearLayout.LayoutParams(0, dp(44), 1f))
        page.addView(actions2)
        addGap(page, 15)
        page.addView(title("הקבצים שלי", 20f))
        addGap(page, 8)
        val search = input("חיפוש לפי שם קובץ")
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.setText(searchText)
        page.addView(search)
        addGap(page, 7)
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        sortRow.addView(makeButton("שם", { sortMode = "name"; showHome() }), LinearLayout.LayoutParams(0, dp(40), 1f))
        sortRow.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
        sortRow.addView(makeButton("תאריך", { sortMode = "date"; showHome() }), LinearLayout.LayoutParams(0, dp(40), 1f))
        sortRow.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
        sortRow.addView(makeButton("גודל", { sortMode = "size"; showHome() }), LinearLayout.LayoutParams(0, dp(40), 1f))
        page.addView(sortRow)
        val scroller = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroller.addView(list)
        page.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun renderList() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            val filtered = items.filter { it.name.contains(query, ignoreCase = true) }
            val sorted = when (sortMode) {
                "name" -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
                "size" -> filtered.sortedByDescending { it.size }
                else -> filtered.sortedByDescending { it.created }
            }
            if (sorted.isEmpty()) {
                list.addView(body(if (items.isEmpty()) "הכספת עדיין ריקה. לחץ על 'הכנסת קבצים' כדי להתחיל." else "לא נמצאו קבצים מתאימים.", 15f))
                return
            }
            sorted.forEach { item ->
                val itemCard = card()
                itemCard.addView(title(item.name, 16f))
                addGap(itemCard, 4)
                itemCard.addView(body("${item.mime}  ·  ${formatSize(item.size)}", 12f))
                addGap(itemCard, 10)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                row.addView(makeButton("פתיחה", { previewItem(item) }, true), LinearLayout.LayoutParams(0, dp(40), 1f))
                row.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
                row.addView(makeButton("הוצאה", { confirmAction("הוצאת קובץ", "הקובץ יפוענח ויישמר רק ביעד שתבחר.") { chooseExport(item) } }), LinearLayout.LayoutParams(0, dp(40), 1f))
                row.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
                row.addView(makeButton("מחיקה", { confirmAction("מחיקת קובץ", "הקובץ יימחק מהכספת ולא יהיה אפשר לשחזר אותו.") { deleteItem(item) } }), LinearLayout.LayoutParams(0, dp(40), 1f))
                itemCard.addView(row)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(9)
                list.addView(itemCard, lp)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchText = s?.toString() ?: ""; renderList() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderList()
    }

    private fun chooseImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun chooseExport(item: VaultItem) {
        pendingAction = PendingAction.WaitExport(item)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, item.name)
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun chooseBackup() {
        pendingAction = PendingAction.WaitBackup
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "vault-backup.vault")
        }
        startActivityForResult(intent, REQ_BACKUP)
    }

    private fun chooseRestore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_RESTORE)
    }

    @Deprecated("Deprecated by the platform; retained for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK && data?.data != null) showRestoreCodeDialog(data.data!!)
            else showUnlock()
            return
        }
        if (requestCode == REQ_IMPORT) {
            val uris = mutableListOf<Uri>()
            if (resultCode == RESULT_OK && data != null) {
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
            }
            pendingAction = if (uris.isNotEmpty()) PendingAction.Import(uris) else null
            showUnlock()
            return
        }
        if (requestCode == REQ_EXPORT) {
            val previous = pendingAction
            if (resultCode == RESULT_OK && data?.data != null && previous is PendingAction.WaitExport) {
                pendingAction = PendingAction.Export(previous.item, data.data!!)
            } else pendingAction = null
            showUnlock()
            return
        }
        if (requestCode == REQ_BACKUP) {
            if (resultCode == RESULT_OK && data?.data != null) pendingAction = PendingAction.Backup(data.data!!)
            else pendingAction = null
            showUnlock()
        }
    }

    private fun importUris(uris: List<Uri>, index: Int) {
        val key = masterKey ?: run { showUnlock(); return }
        if (index >= uris.size) {
            saveIndexAndRefresh()
            toast("הייבוא הסתיים")
            showHome()
            return
        }
        val uri = uris[index]
        try {
            val name = queryDisplayName(uri)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ")
            val item = store.importFile(input, name, mime, key)
            items.add(item)
            store.saveIndex(items, key)
            confirmAction(
                "מחיקת הקובץ המקורי",
                "הקובץ '$name' הועתק לכספת. האם לנסות למחוק את המקור מהמכשיר? ייתכן שמערכת הקבצים לא תאפשר זאת.",
                {
                    val deleted = store.deleteOriginal(contentResolver, uri)
                    toast(if (deleted) "הקובץ המקורי נמחק" else "המערכת לא אפשרה למחוק את המקור")
                    importUris(uris, index + 1)
                },
                { importUris(uris, index + 1) }
            )
        } catch (e: Exception) {
            toast("ייבוא נכשל: ${e.message ?: "שגיאה"}")
            importUris(uris, index + 1)
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "קובץ"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "קובץ"
    }

    private fun performExport(item: VaultItem, uri: Uri) {
        val key = masterKey ?: run { showUnlock(); return }
        try {
            val output = contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("לא ניתן לפתוח את יעד השמירה")
            store.exportFile(item, output, key)
            toast("הקובץ יוצא בהצלחה")
        } catch (e: Exception) {
            toast("ההוצאה נכשלה: ${e.message ?: "שגיאה"}")
        }
        showHome()
    }

    private fun performBackup(uri: Uri) {
        val key = masterKey ?: run { showUnlock(); return }
        try {
            store.writeBackup(uri, contentResolver, key)
            toast("הגיבוי המוצפן נוצר בהצלחה")
        } catch (e: Exception) {
            toast("יצירת הגיבוי נכשלה: ${e.message ?: "שגיאה"}")
        }
        showHome()
    }

    private fun showRestoreCodeDialog(uri: Uri) {
        val code = input("הזן את הקוד של הגיבוי")
        val panel = card().apply {
            addView(body("הזן את הקוד המספרי או את רצף נקודות קוד הקווים ששימש ביצירת הגיבוי."))
            addGap(this, 8)
            addView(code)
        }
        AlertDialog.Builder(this)
            .setTitle("פתיחת הגיבוי")
            .setView(panel)
            .setPositiveButton("אישור") { _, _ ->
                try {
                    val restored = store.restoreBackup(uri, contentResolver, code.text.toString())
                    masterKey = restored
                    items = store.loadIndex(restored)
                    store.resetConsecutiveFailures()
                    toast("הגיבוי שוחזר בהצלחה")
                    showHome()
                } catch (e: Exception) {
                    toast("השחזור נכשל: ${e.message ?: "קוד שגוי או קובץ פגום"}")
                    showUnlock()
                }
            }
            .setNegativeButton("ביטול") { _, _ -> showUnlock() }
            .setOnCancelListener { showUnlock() }
            .show()
    }

    private fun saveIndexAndRefresh() {
        masterKey?.let { store.saveIndex(items, it) }
    }

    private fun deleteItem(item: VaultItem) {
        store.delete(item)
        items.removeAll { it.id == item.id }
        saveIndexAndRefresh()
        toast("הקובץ נמחק מהכספת")
        showHome()
    }

    private fun previewItem(item: VaultItem) {
        val key = masterKey ?: run { showUnlock(); return }
        val temp = File(File(filesDir, "vault/.tmp").apply { mkdirs() }, "preview-${item.id}")
        try {
            store.decryptToFile(item, temp, key)
            if (item.mime.startsWith("image/")) {
                val bitmap = BitmapFactory.decodeFile(temp.absolutePath)
                if (bitmap == null) throw IllegalStateException("לא ניתן להציג את התמונה")
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val dialog = AlertDialog.Builder(this).setTitle(item.name).setView(image).setPositiveButton("סגירה", null).create()
                previewDialog = dialog
                dialog.setOnDismissListener { temp.delete(); if (previewDialog === dialog) previewDialog = null }
                dialog.show()
            } else if (item.mime.startsWith("text/") || item.mime.contains("json") || item.mime.contains("xml")) {
                val text = temp.inputStream().bufferedReader().use { it.readText().take(400_000) }
                val scroll = ScrollView(this)
                val tv = body(text, 14f, WHITE).apply { setPadding(dp(14), dp(14), dp(14), dp(14)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
                scroll.addView(tv)
                val dialog = AlertDialog.Builder(this).setTitle(item.name).setView(scroll).setPositiveButton("סגירה", null).create()
                previewDialog = dialog
                dialog.setOnDismissListener { temp.delete(); if (previewDialog === dialog) previewDialog = null }
                dialog.show()
            } else {
                temp.delete()
                toast("סוג הקובץ הזה ניתן להוצאה בלבד")
            }
        } catch (e: Exception) {
            temp.delete()
            toast("לא ניתן לפתוח את הקובץ: ${e.message ?: "שגיאה"}")
        }
    }

    private fun showSettings() {
        val page = newRoot()
        addTitleAndSubtitle(page, "הגדרות", "ניהול אבטחה והתנהגות הכספת")
        val warning = card()
        warning.addView(body("אזהרה: אם תשכח את שני הקודים, אין שום דרך לשחזר את הקבצים.", 15f, RED))
        page.addView(warning)
        addGap(page)
        val scary = Switch(this).apply {
            text = "הפעלת מסך התרעה לאחר קוד שגוי"
            setTextColor(WHITE)
            isChecked = store.preferenceBoolean("scare_enabled", true)
            setOnCheckedChangeListener { _, checked -> store.setPreferenceBoolean("scare_enabled", checked) }
        }
        page.addView(scary)
        addGap(page, 6)
        page.addView(makeButton("שינוי סף הפעלת ההתרעה (1–5)", {
            val values = arrayOf("1 ניסיון", "2 ניסיונות", "3 ניסיונות", "4 ניסיונות", "5 ניסיונות")
            val selected = store.preferenceInt("scare_threshold", 2).coerceIn(1, 5) - 1
            AlertDialog.Builder(this).setTitle("מתי להציג התרעה?").setSingleChoiceItems(values, selected) { dialog, which ->
                store.setPreferenceInt("scare_threshold", which + 1)
                dialog.dismiss()
                showSettings()
            }.setNegativeButton("ביטול", null).show()
        }))
        addGap(page, 8)
        page.addView(makeButton("הדגמת מסך ההתרעה", { showScare(true) }, true))
        addGap(page, 8)
        page.addView(makeButton("שינוי קוד מספרי", { changePinDialog() }))
        addGap(page, 8)
        page.addView(makeButton("שינוי קוד קווים", { changePatternDialog() }))
        addGap(page, 8)
        page.addView(makeButton("יומן ניסיונות כושלים", { showHistory() }))
        addGap(page, 8)
        page.addView(makeButton("חזרה לקבצים", { showHome() }))
    }

    private fun changePinDialog() {
        val newPin = input("קוד חדש בן 4 עד 8 ספרות", true)
        val confirm = input("הקלד שוב את הקוד החדש", true)
        val box = card().apply { addView(newPin); addGap(this, 8); addView(confirm) }
        AlertDialog.Builder(this).setTitle("שינוי קוד מספרי").setView(box)
            .setPositiveButton("המשך") { _, _ ->
                val p = newPin.text.toString()
                if (p.length !in 4..8 || !p.all { it.isDigit() } || p != confirm.text.toString()) {
                    toast("הקוד אינו תקין או שהאימות אינו תואם")
                } else confirmAction("אישור שינוי קוד", "העטיפה של הקוד המספרי תעודכן. קוד הקווים יישאר ללא שינוי.") {
                    val key = masterKey ?: return@confirmAction
                    val credentials = store.loadCredentials() ?: return@confirmAction
                    store.saveCredentials(Credentials.replace(credentials, key, p, "pin"))
                    toast("הקוד המספרי עודכן")
                }
            }.setNegativeButton("ביטול", null).show()
    }

    private fun changePatternDialog() {
        val box = card()
        box.addView(body("צייר קוד קווים חדש ולאחר מכן צייר אותו שוב לאימות."))
        val patternView = PatternView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)) }
        box.addView(patternView)
        val status = body("צייר לפחות 4 נקודות", 14f, ACCENT)
        box.addView(status)
        var first: String? = null
        var accepted: String? = null
        patternView.onPatternComplete = { pattern ->
            if (first == null) {
                first = pattern
                status.text = "צייר שוב לאימות"
            } else if (first == pattern) {
                accepted = pattern
                status.text = "הקוד אומת"
            } else {
                first = pattern
                status.text = "לא תואם. הרצף החדש נקלט; צייר שוב"
            }
            patternView.clearPattern()
        }
        AlertDialog.Builder(this).setTitle("שינוי קוד קווים").setView(box)
            .setPositiveButton("המשך") { _, _ ->
                val pattern = accepted
                if (pattern == null) toast("יש להשלים את אימות הקווים")
                else confirmAction("אישור שינוי קוד", "העטיפה של קוד הקווים תעודכן. הקוד המספרי יישאר ללא שינוי.") {
                    val key = masterKey ?: return@confirmAction
                    val credentials = store.loadCredentials() ?: return@confirmAction
                    store.saveCredentials(Credentials.replace(credentials, key, pattern, "pattern"))
                    toast("קוד הקווים עודכן")
                }
            }.setNegativeButton("ביטול", null).show()
    }

    private fun showHistory() {
        val page = newRoot()
        addTitleAndSubtitle(page, "יומן ניסיונות", "היומן נשמר מקומית ומציג ניסיונות כושלים בלבד")
        val history = store.getAttemptHistory()
        if (history.isEmpty()) page.addView(body("אין ניסיונות כושלים שמורים."))
        else history.forEach { event ->
            val date = SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", Locale("he", "IL")).format(Date(event.time))
            val row = card()
            row.addView(title("ניסיון כושל", 16f))
            addGap(row, 4)
            row.addView(body(date))
            row.addView(body("רצף ניסיונות: ${event.count}", 13f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(7)
            page.addView(row, lp)
        }
        if (history.size > 8) {
            val scroll = ScrollView(this)
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            history.forEach { event ->
                val date = SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", Locale("he", "IL")).format(Date(event.time))
                content.addView(body("$date  ·  רצף: ${event.count}", 14f, WHITE))
            }
            scroll.addView(content)
            page.removeViews(1, page.childCount - 1)
            page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addGap(page, 12)
        page.addView(makeButton("מחיקת יומן ניסיונות", { confirmAction("מחיקת יומן", "כל רשומות הניסיונות הכושלים יימחקו לצמיתות.") { store.clearAttemptHistory(); toast("היומן נמחק"); showHistory() } }))
        addGap(page, 8)
        page.addView(makeButton("חזרה להגדרות", { showSettings() }))
    }

    private fun showScare(demo: Boolean) {
        if (isFinishing) return
        val dialog = Dialog(this)
        scareDialog = dialog
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(26), dp(30), dp(26), dp(30))
            setBackgroundColor(0xFF05070C.toInt())
        }
        val emblem = TextView(this).apply {
            text = "◉"
            textSize = 58f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        }
        layout.addView(emblem)
        val heading = title("זוהה ניסיון גישה לא מורשה", 22f).apply { gravity = Gravity.CENTER; setTextColor(RED) }
        addGap(layout, 15)
        layout.addView(heading)
        addGap(layout, 12)
        val scanText = body("סורק נתוני גישה...", 17f, WHITE).apply { gravity = Gravity.CENTER }
        layout.addView(scanText)
        addGap(layout, 18)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
        }
        layout.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        addGap(layout, 16)
        val detail = body("מזהה פעילות חריגה במכשיר", 14f).apply { gravity = Gravity.CENTER }
        layout.addView(detail)
        addGap(layout, 30)
        layout.addView(body("${if (demo) "מצב הדגמה" else "הגישה לכספת חסומה"} · המידע נשאר במכשיר", 13f, ACCENT).apply { gravity = Gravity.CENTER })
        addGap(layout, 8)
        layout.addView(body("הצגה בלבד · לא מתבצע צילום ולא נשלח מידע", 12f).apply { gravity = Gravity.CENTER })
        dialog.setContentView(layout)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        val tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 65).also { it.startTone(ToneGenerator.TONE_PROP_BEEP, 600) } } catch (_: Exception) { null }
        val messages = arrayOf("סורק פנים...", "מזהה את המשתמש...", "מתעד את המכשיר...", "בודק הרשאות גישה...", "הגישה לכספת נחסמה")
        val start = System.currentTimeMillis()
        val runner = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val elapsed = System.currentTimeMillis() - start
                val pct = ((elapsed / 5000f) * 100).toInt().coerceIn(0, 100)
                progress.progress = pct
                scanText.text = messages[((elapsed / 950L).toInt()).coerceIn(0, messages.lastIndex)]
                if (pct >= 100) dialog.dismiss() else handler.postDelayed(this, 100)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(runner)
            try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
            if (scareDialog === dialog) scareDialog = null
        }
        handler.post(runner)
    }

    private fun confirmAction(title: String, message: String, onConfirm: () -> Unit): AlertDialog {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("אישור") { _, _ -> onConfirm() }
            .setNegativeButton("ביטול", null)
            .create()
        dialog.show()
        return dialog
    }

    private fun confirmAction(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit): AlertDialog {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("אישור") { _, _ -> onConfirm() }
            .setNegativeButton("ביטול") { _, _ -> onCancel() }
            .create()
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        return dialog
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes בייט"
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
