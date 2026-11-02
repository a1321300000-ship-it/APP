package he.vault.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var store: VaultStore
    private lateinit var root: LinearLayout
    private var masterKey: ByteArray?
        get() = VaultSession.masterKey
        set(value) { VaultSession.setMasterKey(value) }
    private var items: MutableList<VaultItem>
        get() = VaultSession.items
        set(value) { VaultSession.items = value }
    private var needsRelock = false
    private var internalPickerActive = false
    private var unlockEyeView: WatchingEyeView? = null
    private var pendingAction: PendingAction? = null
    private var scareDialog: Dialog? = null
    private var previewDialog: AlertDialog? = null
    private var pendingScareAttemptId: String? = null
    private var activeScareDemo = false
    private var cameraPermissionRequestInProgress = false
    private var activeRadarView: RadarView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                if (!internalPickerActive && !cameraPermissionRequestInProgress) lockForSecurityBoundary()
            }
            Lifecycle.Event.ON_START -> {
                if (needsRelock && !internalPickerActive) {
                    handler.post {
                        if (!isFinishing && needsRelock && !internalPickerActive && store.hasCredentials()) {
                            needsRelock = false
                            showUnlock()
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lockForSecurityBoundary()
        }
    }
    private var searchText = ""
    private var sortMode = "date"

    private data class PreviewResult(val temp: File, val bitmap: Bitmap? = null, val text: String? = null)

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
        private const val REQ_CAMERA = 105
        private const val BG = 0xFF050505.toInt()
        private const val PANEL = 0xFF111315.toInt()
        private const val PANEL_2 = 0xFF1A1D1D.toInt()
        private const val ACCENT = 0xFFFF1744.toInt()
        private const val WHITE = 0xFFE8ECE7.toInt()
        private const val MUTED = 0xFFA7B0A5.toInt()
        private const val RED = 0xFFFF1744.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        store = VaultStore(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        val retainedKey = masterKey
        if (retainedKey != null) {
            try {
                items = store.loadIndex(retainedKey)
                showHome()
            } catch (_: Exception) {
                lockForSecurityBoundary()
                showUnlock()
            }
        } else if (store.hasCredentials()) showUnlock() else showSetup()
    }

    private fun lockForSecurityBoundary() {
        if (::store.isInitialized && store.hasCredentials()) needsRelock = true
        masterKey = null
        items = mutableListOf()
        previewDialog?.dismiss()
        previewDialog = null
        unlockEyeView = null
        File(filesDir, "vault/.tmp").apply { mkdirs() }.listFiles()
            ?.filter { it.name.startsWith("preview-") }
            ?.forEach { it.delete() }
    }

    override fun onPause() {
        super.onPause()
        // Activity pause is not a security boundary: dialogs, pickers and internal screens
        // can temporarily pause this Activity without locking the process-wide session.
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) { }
        if (isFinishing) VaultSession.clear()
        activeRadarView?.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated by the platform; retained for Android 7 compatibility")
    override fun onBackPressed() {
        if (scareDialog?.isShowing == true && !activeScareDemo) return
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            cameraPermissionRequestInProgress = false
            val attemptId = pendingScareAttemptId
            pendingScareAttemptId = null
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            showScare(false, attemptId, granted)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun newRoot(scrollable: Boolean = true): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val screen: View = if (scrollable) {
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(root, ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
                ))
            }
        } else root
        screen.setOnApplyWindowInsetsListener { _, insets ->
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let { cutout ->
                    left = maxOf(left, cutout.safeInsetLeft)
                    top = maxOf(top, cutout.safeInsetTop)
                    right = maxOf(right, cutout.safeInsetRight)
                    bottom = maxOf(bottom, cutout.safeInsetBottom)
                }
            }
            root.setPadding(dp(16) + left, dp(12) + top, dp(16) + right, dp(12) + bottom)
            insets
        }
        setContentView(screen)
        screen.requestApplyInsets()
        return root
    }

    private fun isLowRamDevice(): Boolean =
        (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    private fun launchInternalPicker(intent: Intent, requestCode: Int) {
        internalPickerActive = true
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            internalPickerActive = false
            toast("לא ניתן לפתוח בורר קבצים: ${e.message ?: "שגיאה"}")
        }
    }

    private fun showHomeOrUnlock() {
        if (masterKey != null) showHome() else showUnlock()
    }

    private fun <T> runInBackground(
        message: String,
        work: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Exception) -> Unit = { error -> toast("$message: ${error.message ?: "שגיאה"}") }
    ) {
        if (isFinishing || isDestroyed) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true })
            addView(body(message, 14f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("המערכת עובדת")
            .setView(panel)
            .setCancelable(false)
            .create()
        dialog.show()
        VaultSession.ioExecutor.execute {
            try {
                val result = work()
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (!isFinishing && !isDestroyed) onSuccess(result)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (!isFinishing && !isDestroyed) onError(error)
                }
            }
        }
    }

    private fun decodeSampledBitmap(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "קובץ התמונה אינו תקין" }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("לא ניתן לפענח את התמונה")
    }

    private fun cacheThumbnail(item: VaultItem, decryptedFile: File) {
        val directory = File(filesDir, "vault/thumbnails")
        if (!directory.exists() && !directory.mkdirs()) return
        val target = File(directory, "${item.id}.jpg")
        if (target.isFile && target.length() > 0L) return
        val bitmap = decodeSampledBitmap(decryptedFile, 320)
        try {
            FileOutputStream(target).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
                output.flush()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun readTextPreview(file: File, maxChars: Int = 400_000): String {
        val result = StringBuilder()
        file.inputStream().bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            while (result.length < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - result.length))
                if (count <= 0) break
                result.append(buffer, 0, count)
            }
        }
        return result.toString()
    }

    private fun title(text: String, size: Float = 25f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(WHITE)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun body(text: String, size: Float = 15f, color: Int = MUTED): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
        typeface = Typeface.MONOSPACE
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
                        runInBackground("יוצר כספת מוצפנת ושומר את הקודים...", {
                            val master = VaultCrypto.randomBytes(32)
                            try {
                                store.saveCredentials(Credentials.create(master, p, confirmedPattern!!))
                                store.saveIndex(emptyList(), master)
                                store.resetConsecutiveFailures()
                                master
                            } catch (error: Exception) {
                                master.fill(0)
                                throw error
                            }
                        }, { master ->
                            masterKey = master
                            items = mutableListOf()
                            showHome()
                        })
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
        page.addView(body("המערכת מנטרת אותך... הזן קוד כדי להמשיך", 15f))
        addGap(page, 8)
        val eye = WatchingEyeView(this, !isLowRamDevice()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        unlockEyeView = eye
        page.addView(eye)
        addGap(page, 10)
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        runInBackground("מאמת את הקוד ומפענח את רשימת הקבצים...", {
            val credentials = store.loadCredentials()
            val master = credentials?.let { Credentials.unlock(it, code, type) }
            if (master == null) Pair<ByteArray?, MutableList<VaultItem>?>(null, null)
            else try {
                Pair<ByteArray?, MutableList<VaultItem>?>(master, store.loadIndex(master))
            } catch (error: Exception) {
                master.fill(0)
                throw error
            }
        }, { result ->
            val master = result.first
            if (master == null) {
                val failure = store.recordFailure()
                val consecutive = failure.consecutive
                val threshold = store.preferenceInt("scare_threshold", 2).coerceIn(1, 5)
                if (store.preferenceBoolean("scare_enabled", true) && consecutive % threshold == 0) {
                    requestScare(failure.attemptId)
                }
                if (consecutive >= 5) {
                    val level = store.preferenceInt("lockout_level", 0).coerceIn(0, 6)
                    val duration = 30_000L * (1L shl level)
                    store.setPreferenceLong("lockout_until", System.currentTimeMillis() + duration)
                    store.setPreferenceInt("lockout_level", (level + 1).coerceAtMost(6))
                    store.resetConsecutiveFailures()
                    handler.postDelayed({ if (!isFinishing && scareDialog?.isShowing != true) showUnlock() }, 500)
                    toast("יותר מדי ניסיונות. הכספת ננעלה לזמן קצר.")
                } else {
                    toast("קוד שגוי")
                }
                return@runInBackground
            }
            store.resetConsecutiveFailures()
            store.setPreferenceLong("lockout_until", 0L)
            masterKey = master
            items = result.second ?: mutableListOf()
            val eye = unlockEyeView
            unlockEyeView = null
            if (eye != null) eye.closeEye {
                if (!isFinishing && masterKey != null) performPendingOrShowHome()
            } else performPendingOrShowHome()
        }, { error ->
            toast("לא ניתן לפתוח את הכספת: ${error.message ?: "נתונים פגומים"}")
            showUnlock()
        })
    }

    private fun requestScare(attemptId: String) {
        pendingScareAttemptId = attemptId
        val hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        if (!hasFrontCamera) {
            showScare(false, attemptId, false)
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScare(false, attemptId, true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("הרשאת מצלמה קדמית")
            .setMessage("כדי להציג תצוגה חיה ולשמור תמונת ניסיון מוצפנת ביומן המקומי, נדרשת הרשאת מצלמה. המצלמה והתמונה יוצגו באופן גלוי, והתמונה לא תישלח לשום מקום.")
            .setPositiveButton("אישור והמשך") { _, _ ->
                cameraPermissionRequestInProgress = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
            .setNegativeButton("המשך ללא מצלמה") { _, _ ->
                pendingScareAttemptId = null
                showScare(false, attemptId, false)
            }
            .setOnCancelListener {
                pendingScareAttemptId = null
                showScare(false, attemptId, false)
            }
            .show()
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
        val page = newRoot(false)
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

        var visibleItemCount = 20
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
            sorted.take(visibleItemCount).forEach { item ->
                val itemCard = card()
                val cachedThumb = File(File(filesDir, "vault/thumbnails"), "${item.id}.jpg")
                if (item.mime.startsWith("image/") && cachedThumb.isFile) {
                    val thumb = BitmapFactory.decodeFile(cachedThumb.absolutePath)
                    if (thumb != null) {
                        itemCard.addView(ImageView(this).apply {
                            setImageBitmap(thumb)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                        })
                        addGap(itemCard, 5)
                    }
                }
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
            if (visibleItemCount < sorted.size) {
                list.addView(makeButton("הצג עוד קבצים (${sorted.size - visibleItemCount} נותרו)", {
                    visibleItemCount = (visibleItemCount + 20).coerceAtMost(sorted.size)
                    renderList()
                }))
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchText = s?.toString() ?: ""; visibleItemCount = 20; renderList() }
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
        launchInternalPicker(intent, REQ_IMPORT)
    }

    private fun chooseExport(item: VaultItem) {
        pendingAction = PendingAction.WaitExport(item)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, item.name)
        }
        launchInternalPicker(intent, REQ_EXPORT)
    }

    private fun chooseBackup() {
        pendingAction = PendingAction.WaitBackup
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "vault-backup.vault")
        }
        launchInternalPicker(intent, REQ_BACKUP)
    }

    private fun chooseRestore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchInternalPicker(intent, REQ_RESTORE)
    }

    @Deprecated("Deprecated by the platform; retained for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        internalPickerActive = false
        if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK && data?.data != null) showRestoreCodeDialog(data.data!!)
            else showHomeOrUnlock()
            return
        }
        if (requestCode == REQ_IMPORT) {
            val uris = mutableListOf<Uri>()
            if (resultCode == RESULT_OK && data != null) {
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
            }
            pendingAction = if (uris.isNotEmpty()) PendingAction.Import(uris) else null
            if (masterKey != null) performPendingOrShowHome() else showUnlock()
            return
        }
        if (requestCode == REQ_EXPORT) {
            val previous = pendingAction
            if (resultCode == RESULT_OK && data?.data != null && previous is PendingAction.WaitExport) {
                pendingAction = PendingAction.Export(previous.item, data.data!!)
            } else pendingAction = null
            if (masterKey != null) performPendingOrShowHome() else showUnlock()
            return
        }
        if (requestCode == REQ_BACKUP) {
            if (resultCode == RESULT_OK && data?.data != null) pendingAction = PendingAction.Backup(data.data!!)
            else pendingAction = null
            if (masterKey != null) performPendingOrShowHome() else showUnlock()
        }
    }

    private fun importUris(uris: List<Uri>, index: Int) {
        val key = masterKey ?: run { showUnlock(); return }
        if (index >= uris.size) {
            toast("הייבוא הסתיים")
            showHome()
            return
        }
        val uri = uris[index]
        val name = queryDisplayName(uri)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val keyCopy = key.copyOf()
        val currentItems = items.toList()
        runInBackground("מצפין ומעתיק את הקובץ: $name", {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ")
                val item = store.importFile(input, name, mime, keyCopy)
                try {
                    store.saveIndex(currentItems + item, keyCopy)
                    item
                } catch (error: Exception) {
                    store.delete(item)
                    throw error
                }
            } finally {
                keyCopy.fill(0)
            }
        }, { item ->
            items.add(item)
            confirmAction(
                "מחיקת הקובץ המקורי",
                "הקובץ '$name' הועתק לכספת. האם לנסות למחוק את המקור מהמכשיר? ייתכן שמערכת הקבצים לא תאפשר זאת.",
                {
                    runInBackground("מנסה למחוק את המקור...", {
                        store.deleteOriginal(contentResolver, uri)
                    }, { deleted ->
                        toast(if (deleted) "הקובץ המקורי נמחק" else "המערכת לא אפשרה למחוק את המקור")
                        importUris(uris, index + 1)
                    })
                },
                { importUris(uris, index + 1) }
            )
        }, { error ->
            toast("ייבוא נכשל: ${error.message ?: "שגיאה"}")
            importUris(uris, index + 1)
        })
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
        val keyCopy = masterKey?.copyOf() ?: run { showUnlock(); return }
        runInBackground("מפענח וכותב את הקובץ ליעד שבחרת...", {
            try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("לא ניתן לפתוח את יעד השמירה")
                store.exportFile(item, output, keyCopy)
                true
            } finally {
                keyCopy.fill(0)
            }
        }, {
            toast("הקובץ יוצא בהצלחה")
            showHome()
        }, { error ->
            toast("ההוצאה נכשלה: ${error.message ?: "שגיאה"}")
            showHome()
        })
    }

    private fun performBackup(uri: Uri) {
        val keyCopy = masterKey?.copyOf() ?: run { showUnlock(); return }
        runInBackground("יוצר גיבוי מוצפן...", {
            try {
                store.writeBackup(uri, contentResolver, keyCopy)
                true
            } finally {
                keyCopy.fill(0)
            }
        }, {
            toast("הגיבוי המוצפן נוצר בהצלחה")
            showHome()
        }, { error ->
            toast("יצירת הגיבוי נכשלה: ${error.message ?: "שגיאה"}")
            showHome()
        })
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
                val restoreCode = code.text.toString()
                runInBackground("מפענח ובודק את הגיבוי...", {
                    val restored = store.restoreBackup(uri, contentResolver, restoreCode)
                    try {
                        Pair(restored, store.loadIndex(restored))
                    } catch (error: Exception) {
                        restored.fill(0)
                        throw error
                    }
                }, { restored ->
                    masterKey = restored.first
                    items = restored.second
                    store.resetConsecutiveFailures()
                    toast("הגיבוי שוחזר בהצלחה")
                    showHome()
                }, { error ->
                    toast("השחזור נכשל: ${error.message ?: "קוד שגוי או קובץ פגום"}")
                    showHomeOrUnlock()
                })
            }
            .setNegativeButton("ביטול") { _, _ -> showUnlock() }
            .setOnCancelListener { showUnlock() }
            .show()
    }

    private fun saveIndexAndRefresh() {
        masterKey?.let { store.saveIndex(items, it) }
    }

    private fun deleteItem(item: VaultItem) {
        val keyCopy = masterKey?.copyOf() ?: run { showUnlock(); return }
        val updatedItems = items.filterNot { it.id == item.id }
        runInBackground("מוחק את הקובץ ומעדכן את הרשימה המוצפנת...", {
            try {
                store.delete(item)
                store.saveIndex(updatedItems, keyCopy)
                File(File(filesDir, "vault/thumbnails"), "${item.id}.jpg").delete()
                true
            } finally {
                keyCopy.fill(0)
            }
        }, {
            items = updatedItems.toMutableList()
            toast("הקובץ נמחק מהכספת")
            showHome()
        })
    }

    private fun previewItem(item: VaultItem) {
        val keyCopy = masterKey?.copyOf() ?: run { showUnlock(); return }
        val temp = File(File(filesDir, "vault/.tmp").apply { mkdirs() }, "preview-${item.id}")
        runInBackground("מפענח את הקובץ לתצוגה זמנית ומאובטחת...", {
            try {
                store.decryptToFile(item, temp, keyCopy)
                when {
                    item.mime.startsWith("image/") -> {
                        cacheThumbnail(item, temp)
                        PreviewResult(temp, bitmap = decodeSampledBitmap(temp, 1400))
                    }
                    item.mime.startsWith("text/") || item.mime.contains("json") || item.mime.contains("xml") -> {
                        PreviewResult(temp, text = readTextPreview(temp))
                    }
                    else -> PreviewResult(temp)
                }
            } catch (error: Exception) {
                temp.delete()
                throw error
            } finally {
                keyCopy.fill(0)
            }
        }, { result ->
            when {
                result.bitmap != null -> {
                    val image = ImageView(this).apply {
                        setImageBitmap(result.bitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    val dialog = AlertDialog.Builder(this).setTitle(item.name).setView(image).setPositiveButton("סגירה", null).create()
                    previewDialog = dialog
                    dialog.setOnDismissListener {
                        result.temp.delete()
                        result.bitmap.recycle()
                        if (previewDialog === dialog) previewDialog = null
                    }
                    dialog.show()
                }
                result.text != null -> {
                    val scroll = ScrollView(this)
                    val tv = body(result.text, 14f, WHITE).apply {
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    }
                    scroll.addView(tv)
                    val dialog = AlertDialog.Builder(this).setTitle(item.name).setView(scroll).setPositiveButton("סגירה", null).create()
                    previewDialog = dialog
                    dialog.setOnDismissListener { result.temp.delete(); if (previewDialog === dialog) previewDialog = null }
                    dialog.show()
                }
                else -> {
                    result.temp.delete()
                    toast("סוג הקובץ הזה ניתן להוצאה בלבד")
                }
            }
        }, { error ->
            temp.delete()
            toast("לא ניתן לפתוח את הקובץ: ${error.message ?: "שגיאה"}")
        })
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
        page.addView(makeButton("הדגמת מסך ההתרעה", { showScare(true, null, false) }, true))
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
        val patternView = PatternView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
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
        addTitleAndSubtitle(page, "יומן ניסיונות", "היומן והתמונות נשמרים מקומית בלבד. תמונות מוצגות לאחר פתיחה תקינה של הכספת.")
        val history = store.getAttemptHistory()
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        if (history.isEmpty()) {
            content.addView(body("אין ניסיונות כושלים שמורים."))
        } else {
            history.forEach { event ->
                val date = SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", Locale("he", "IL")).format(Date(event.time))
                val row = card()
                row.addView(title("ניסיון כושל", 16f))
                addGap(row, 4)
                row.addView(body(date))
                row.addView(body("רצף ניסיונות: ${event.count}", 13f))
                if (store.hasAttemptPhoto(event.id)) {
                    addGap(row, 7)
                    row.addView(body("תמונת ניסיון מוצפנת שמורה במכשיר", 13f, ACCENT))
                    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    buttons.addView(makeButton("צפייה בתמונה", {
                        try {
                            val bytes = store.readAttemptPhoto(event.id) ?: throw IllegalStateException("התמונה לא נמצאה")
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IllegalStateException("לא ניתן לפענח את התמונה")
                            val image = ImageView(this@MainActivity).apply {
                                setImageBitmap(bitmap)
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setPadding(dp(8), dp(8), dp(8), dp(8))
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("תמונת ניסיון")
                                .setView(image)
                                .setPositiveButton("סגירה", null)
                                .show()
                        } catch (e: Exception) {
                            toast("לא ניתן להציג את התמונה: ${e.message ?: "שגיאה"}")
                        }
                    }, true), LinearLayout.LayoutParams(0, dp(42), 1f))
                    buttons.addView(View(this), LinearLayout.LayoutParams(dp(7), 1))
                    buttons.addView(makeButton("מחיקת תמונה", {
                        confirmAction("מחיקת תמונת ניסיון", "התמונה המוצפנת תימחק לצמיתות מהאחסון הפרטי.") {
                            store.deleteAttemptPhoto(event.id)
                            showHistory()
                        }
                    }), LinearLayout.LayoutParams(0, dp(42), 1f))
                    row.addView(buttons)
                } else {
                    row.addView(body("לא נשמרה תמונה לניסיון זה", 12f))
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                content.addView(row, lp)
            }
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addGap(page, 10)
        page.addView(makeButton("מחיקת יומן הניסיונות והתמונות", {
            confirmAction("מחיקת יומן", "כל רשומות הניסיונות וכל תמונות הניסיון המוצפנות יימחקו לצמיתות.") {
                store.clearAttemptHistory()
                toast("היומן והתמונות נמחקו")
                showHistory()
            }
        }))
        addGap(page, 8)
        page.addView(makeButton("חזרה להגדרות", { showSettings() }))
    }

    private fun showScare(demo: Boolean, attemptId: String?, useCamera: Boolean) {
        if (isFinishing) return
        val dialog = Dialog(this)
        scareDialog = dialog
        activeScareDemo = demo

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(16), dp(20), dp(22))
            setBackgroundColor(0xFF05070C.toInt())
        }
        scroll.addView(layout)

        val emblem = TextView(this).apply {
            text = "◈"
            textSize = 43f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        }
        layout.addView(emblem)
        layout.addView(title("מרכז אבטחה מקומי", 23f).apply { gravity = Gravity.CENTER; setTextColor(WHITE) })
        addGap(layout, 2)
        layout.addView(body("הדמיה בלבד · לא נשלח דיווח ולא מתבצעת תקשורת", 12f, ACCENT).apply { gravity = Gravity.CENTER })

        var cameraStatus: TextView? = null
        var cameraPreview: FrontCameraPreview? = null
        if (useCamera && attemptId != null) {
            addGap(layout, 9)
            cameraStatus = body("המצלמה פעילה. התמונה תישמר מוצפנת במכשיר בלבד.", 13f, ACCENT).apply { gravity = Gravity.CENTER }
            layout.addView(cameraStatus)
            lateinit var preview: FrontCameraPreview
            preview = FrontCameraPreview(
                this,
                onPhoto = { bytes ->
                    try {
                        store.saveAttemptPhoto(attemptId, bytes)
                        cameraStatus?.text = "התמונה שלך נשמרה מוצפנת במכשיר בלבד"
                        cameraStatus?.setTextColor(ACCENT)
                    } catch (e: Exception) {
                        cameraStatus?.text = "שמירת התמונה נכשלה: ${e.message ?: "שגיאה"}"
                        cameraStatus?.setTextColor(RED)
                    }
                },
                onUnavailable = { message ->
                    cameraStatus?.text = message + " · ממשיך בהדמיה ללא מצלמה"
                    cameraStatus?.setTextColor(RED)
                },
                onReady = {
                    handler.postDelayed({
                        if (dialog.isShowing) preview.captureOnce()
                    }, 1400)
                }
            )
            cameraPreview = preview
            layout.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(185)))
        } else {
            addGap(layout, 8)
            layout.addView(body(if (demo) "הדגמה — ללא צילום וללא שמירת תמונה" else "מצב ללא מצלמה · תהליך הדמיה מקומי", 13f, MUTED).apply { gravity = Gravity.CENTER })
        }

        addGap(layout, 10)
        val radar = RadarView(this)
        activeRadarView = radar
        layout.addView(radar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(145)))
        val stageTitle = title("מאתר את המכשיר...", 19f).apply { gravity = Gravity.CENTER; setTextColor(ACCENT) }
        layout.addView(stageTitle)
        addGap(layout, 5)
        val stageDetail = body("מכ״ם מקומי סורק · הדמיה בלבד", 13f, WHITE).apply { gravity = Gravity.CENTER }
        layout.addView(stageDetail)
        addGap(layout, 8)

        val fingerprintPad = TextView(this).apply {
            text = "☝"
            textSize = 43f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0xFF111C2B.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), ACCENT)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        layout.addView(fingerprintPad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
        val fingerprintStatus = body("הנח את האצבע על המסך לאימות", 13f, MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        layout.addView(fingerprintStatus)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
            visibility = View.GONE
        }
        layout.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(7)))

        val deviceInfo = body("", 12f, WHITE).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(0xFF111C2B.toInt()); cornerRadius = dp(12).toFloat() }
        }
        layout.addView(deviceInfo)
        val caseInfo = body("", 13f, WHITE).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(0xFF111C2B.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF34445A.toInt()) }
        }
        layout.addView(caseInfo)
        val countdown = body("", 14f, RED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        layout.addView(countdown)
        val stamp = TextView(this).apply {
            text = "התקבל"
            textSize = 40f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(RED)
            gravity = Gravity.CENTER
            rotation = -7f
            alpha = 0f
            scaleX = 2.3f
            scaleY = 2.3f
            background = GradientDrawable().apply {
                setColor(0x22000000)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(4), RED)
            }
            setPadding(dp(22), dp(8), dp(22), dp(8))
            visibility = View.GONE
        }
        layout.addView(stamp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(88)))
        addGap(layout, 7)
        val footer = body("הדמיה מקומית בלבד · אין דיווח חיצוני", 11f, ACCENT).apply { gravity = Gravity.CENTER }
        layout.addView(footer)
        if (demo) {
            addGap(layout, 7)
            layout.addView(makeButton("סיום הדגמה", { dialog.dismiss() }))
        }

        dialog.setContentView(scroll)
        dialog.setCancelable(demo)
        dialog.setCanceledOnTouchOutside(demo)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        var touchedFingerprint = false
        var fingerprintAnimating = false
        fingerprintPad.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN && !touchedFingerprint && dialog.isShowing) {
                touchedFingerprint = true
                fingerprintAnimating = true
                fingerprintStatus.text = "סורק נקודת מגע..."
                progress.visibility = View.VISIBLE
                var step = 0
                val scan = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return
                        step = (step + 10).coerceAtMost(100)
                        progress.progress = step
                        fingerprintStatus.text = "סריקת מגע: $step%"
                        if (step < 100) handler.postDelayed(this, 65)
                        else {
                            fingerprintAnimating = false
                            fingerprintStatus.text = "דפוס המגע נקלט בהדמיה בלבד"
                        }
                    }
                }
                handler.post(scan)
                true
            } else false
        }

        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra("level", -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra("scale", 100) ?: 100
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) (batteryLevel * 100 / batteryScale) else -1
        val timeText = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("he", "IL")).format(Date())
        val deviceText = "דגם: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}\nסוללה: ${if (batteryPercent >= 0) "$batteryPercent%" else "לא זמין"}\nתאריך ושעה: $timeText"
        val caseNumber = Random.nextInt(1000, 10000)
        val caseText = "נפתח אירוע אבטחה מספר $caseNumber\nסוג: ניסיון גישה לא מורשה\nסטטוס: נשמר במכשיר בלבד\nהדמיה בלבד — לא נשלח דיווח"
        var finalEffectsPlayed = false
        val tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 70) } catch (_: Exception) { null }
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        val start = SystemClock.uptimeMillis()
        val runner = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val elapsed = SystemClock.uptimeMillis() - start
                when {
                    elapsed < 2500L -> {
                        radar.visibility = View.VISIBLE
                        fingerprintPad.visibility = View.GONE
                        fingerprintStatus.visibility = View.GONE
                        progress.visibility = View.GONE
                        deviceInfo.visibility = View.GONE
                        caseInfo.visibility = View.GONE
                        countdown.visibility = View.GONE
                        stamp.visibility = View.GONE
                        stageTitle.text = "מאתר את המכשיר..."
                        stageDetail.text = if (elapsed < 1300L) "מכ״ם מקומי סורק · הדמיה בלבד" else "המיקום אותר (מיקום מדומה)"
                    }
                    elapsed < 5200L -> {
                        radar.visibility = View.GONE
                        fingerprintPad.visibility = View.VISIBLE
                        fingerprintStatus.visibility = View.VISIBLE
                        stageTitle.text = "סריקת מגע"
                        stageDetail.text = "הנח את האצבע על המסך לאימות"
                        if (!touchedFingerprint) fingerprintStatus.text = "לא נאסף מידע ביומטרי · נגיעה היא חלק מההדמיה"
                        if (!fingerprintAnimating && !touchedFingerprint) progress.visibility = View.GONE
                    }
                    elapsed < 8200L -> {
                        radar.visibility = View.GONE
                        fingerprintPad.visibility = View.GONE
                        fingerprintStatus.visibility = View.GONE
                        progress.visibility = View.GONE
                        deviceInfo.visibility = View.VISIBLE
                        stageTitle.text = "נתוני המכשיר נאספו"
                        stageDetail.text = "מידע מקומי בלבד"
                        deviceInfo.text = deviceText
                    }
                    elapsed < 11200L -> {
                        deviceInfo.visibility = View.GONE
                        caseInfo.visibility = View.VISIBLE
                        stageTitle.text = "פתיחת אירוע אבטחה (הדמיה)"
                        stageDetail.text = "מעבד את פרטי הניסיון המקומיים..."
                        val chars = ((elapsed - 8200L) / 24L).toInt().coerceIn(0, caseText.length)
                        caseInfo.text = caseText.take(chars)
                        countdown.visibility = View.VISIBLE
                        val remaining = ((11200L - elapsed) / 1000L).coerceAtLeast(0L)
                        countdown.text = "סיום התהליך בעוד 00:0$remaining"
                    }
                    elapsed < 14000L -> {
                        caseInfo.visibility = View.GONE
                        countdown.visibility = View.GONE
                        stageTitle.text = "הסתיים תהליך ההדמיה"
                        stageDetail.text = "הגישה לכספת נשארת נעולה"
                        stamp.visibility = View.VISIBLE
                        stamp.alpha = 1f
                        stamp.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
                        if (!finalEffectsPlayed) {
                            finalEffectsPlayed = true
                            try {
                                tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                                handler.postDelayed({ try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) } catch (_: Exception) { } }, 320)
                                if (Build.VERSION.SDK_INT >= 26) {
                                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180, 100, 350), -1))
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator?.vibrate(650)
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    else -> dialog.dismiss()
                }
                if (elapsed < 14000L) handler.postDelayed(this, 90)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(runner)
            activeRadarView?.stop()
            activeRadarView = null
            try { tone?.stopTone(); tone?.release() } catch (_: Exception) { }
            try { vibrator?.cancel() } catch (_: Exception) { }
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
