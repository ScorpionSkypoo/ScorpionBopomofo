package com.p3dro.scorpio.ime.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.p3dro.scorpio.ime.R
import com.p3dro.scorpio.ime.engine.ChineseMethod
import com.p3dro.scorpio.ime.engine.ScorpioDictManager
import kotlinx.coroutines.launch

/**
 * 設定 App：第一次使用的引導（啟用 → 切換 → 試打），完成後是分類設定
 * 介面外觀／輸入與按鍵／選字引擎／字典與學習／關於
 *
 * 畫面以程式建構（頁面少、元件重複），頁面切換用簡單的堆疊，返回鍵回上一頁
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Page(val title: String) {
        ONBOARDING("開始使用"),
        HOME("Scorpio 智慧注音"),
        METHODS("輸入法"),
        APPEARANCE("介面外觀"),
        INPUT("輸入與按鍵"),
        ENGINE("選字引擎"),
        DICTIONARY("字典與學習"),
        USER_WORDS("自建詞"),
        DICT_DETAIL("詞庫內容"),
        PLUGINS("工具列與擴充功能"),
        ABOUT("關於")
    }

    private lateinit var prefs: SharedPreferences

    // 照片主題：系統相片挑選器（不需要讀取權限）
    private val importDict = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val name = displayName(uri)?.substringBeforeLast('.')?.ifBlank { null } ?: "匯入的詞庫"
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                android.widget.Toast.makeText(this@SettingsActivity, "讀不到這個檔案", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            android.widget.Toast.makeText(this@SettingsActivity, "匯入中…", android.widget.Toast.LENGTH_SHORT).show()
            val r = dict.importDictionary(name, text)
            val msg = if (r.added == 0) "沒有可以匯入的詞（每行一個詞，可加注音與次數）"
            else "已匯入「$name」${r.added} 個詞" + if (r.skipped > 0) "，${r.skipped} 個推不出讀音已略過" else ""
            android.widget.Toast.makeText(this@SettingsActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            if (stack.lastOrNull() == Page.DICTIONARY) render()
        }
    }

    private val exportDict = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = dict.exportUserDictionary()
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(this@SettingsActivity, if (ok) "已匯出個人詞庫" else "匯出失敗", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private val pickPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { savePhoto(uri) }
            if (ok) prefs.edit().putString("keyboard_theme", "photo").apply()
            else android.widget.Toast.makeText(this@SettingsActivity, "讀取照片失敗", android.widget.Toast.LENGTH_SHORT).show()
            render()
        }
    }

    /** 照片縮到長邊 1600px 存成 keyboard_bg.jpg（輸入法與設定同一個 App，直接讀檔） */
    private fun savePhoto(uri: android.net.Uri): Boolean = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) false else {
            java.io.File(filesDir, "keyboard_bg.jpg").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            true
        }
    } catch (e: Exception) {
        false
    }
    private val dict by lazy { ScorpioDictManager.getInstance(applicationContext) }

    private val stack = ArrayList<Page>()
    private lateinit var titleView: TextView
    private lateinit var backButton: ImageView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("scorpio_ime_prefs", Context.MODE_PRIVATE)
        setContentView(buildFrame())
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        getSharedPreferences("scorpio_plugins", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        com.p3dro.scorpio.ime.engine.PackUpdater.schedule(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) {
                    stack.removeAt(stack.size - 1)
                    render(-1)
                } else {
                    finish()
                }
            }
        })

        val needsSetup = !isImeEnabled() || !isImeSelected()
        stack.add(if (needsSetup && !prefs.getBoolean("onboarding_skipped", false)) Page.ONBOARDING else Page.HOME)
        if (intent?.getStringExtra(EXTRA_PAGE) == "plugins") stack.add(Page.PLUGINS)
        render(1)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        getSharedPreferences("scorpio_plugins", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 從鍵盤的擴充功能面板點過來
        if (intent.getStringExtra(EXTRA_PAGE) == "plugins" && stack.lastOrNull() != Page.PLUGINS) open(Page.PLUGINS)
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }

    override fun onResume() {
        super.onResume()
        // 從系統設定回來：更新啟用／切換狀態
        if (stack.lastOrNull() == Page.ONBOARDING || stack.lastOrNull() == Page.HOME) render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 輸入法選擇視窗關掉時不會觸發 onResume，靠焦點變化更新
        if (hasFocus && stack.lastOrNull() == Page.ONBOARDING) render()
    }

    // ---------- 狀態 ----------

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    // ---------- 外框與頁面切換 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Material 3 色彩（Android 12 以上跟著桌布）
    private fun themeColor(attr: Int) = com.google.android.material.color.MaterialColors.getColor(this, attr, 0)
    private val cSurface by lazy { themeColor(com.google.android.material.R.attr.colorSurface) }
    private val cSurfaceContainer by lazy { themeColor(com.google.android.material.R.attr.colorSurfaceContainer) }
    private val cSurfaceHighest by lazy { themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest) }
    private val cOnSurface by lazy { themeColor(com.google.android.material.R.attr.colorOnSurface) }
    private val cOnSurfaceVariant by lazy { themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant) }
    private val cPrimary by lazy { themeColor(androidx.appcompat.R.attr.colorPrimary) }
    private val cOnPrimary by lazy { themeColor(com.google.android.material.R.attr.colorOnPrimary) }
    private val cError by lazy { themeColor(androidx.appcompat.R.attr.colorError) }

    private fun textAppearance(view: TextView, attr: Int) {
        val tv = android.util.TypedValue()
        if (theme.resolveAttribute(attr, tv, true)) androidx.core.widget.TextViewCompat.setTextAppearance(view, tv.resourceId)
    }

    private lateinit var bar: LinearLayout
    private lateinit var previewBox: LinearLayout
    private lateinit var previewField: EditText
    private val previewPages = setOf(Page.METHODS, Page.APPEARANCE, Page.INPUT, Page.PLUGINS)

    /** 設定改了：重新連上試打框，輸入法會依新設定重建鍵盤 */
    private fun refreshPreview() {
        if (::previewField.isInitialized && previewField.hasFocus()) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(previewField)
        }
    }

    // 鍵盤打字時自己會寫的設定（最近使用的 Emoji、目前的輸入法），不算「改設定」，否則選一個 Emoji 鍵盤就被重建、面板收起
    private val runtimeKeys = setOf(
        com.p3dro.scorpio.ime.ScorpioImeService.PREF_RECENT_LIST,
        ChineseMethod.PREF_CURRENT
    )
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in runtimeKeys) previewField.post { refreshPreview() }
    }
    private var barLifted = false

    private fun buildFrame(): View {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(16), dp(8))
            minimumHeight = dp(64)
            setBackgroundColor(cSurface)
        }
        backButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_set_arrow_back)
            setColorFilter(cOnSurface)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = "返回"
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        bar.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this).apply {
            textAppearance(this, com.google.android.material.R.attr.textAppearanceTitleLarge)
            setTextColor(cOnSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        frame.addView(bar)

        // 即時預覽：輸入法、介面外觀、輸入與按鍵、工具列頁面上方固定一個試打框，鍵盤自動彈出，改設定馬上看得到
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            visibility = View.GONE
        }
        val previewLayout = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "試打看看，改設定鍵盤會馬上更新"
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
        }
        previewField = com.google.android.material.textfield.TextInputEditText(previewLayout.context).apply {
            maxLines = 2
        }
        previewLayout.addView(previewField)
        previewBox.addView(previewLayout)
        frame.addView(previewBox)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            // 捲動後標題列換成稍深的底色（Material 3 的 lift on scroll）
            setOnScrollChangeListener { _, _, y, _, _ -> liftBar(y > 0) }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(content)
        frame.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 內容延伸到導覽列底下，只把最後一段捲動空間加長
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        return frame
    }

    private fun liftBar(lift: Boolean) {
        if (lift == barLifted) return
        barLifted = lift
        android.animation.ValueAnimator.ofArgb(
            if (lift) cSurface else cSurfaceContainer,
            if (lift) cSurfaceContainer else cSurface
        ).apply {
            duration = 180
            addUpdateListener { bar.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun open(page: Page) {
        stack.add(page)
        render(1)
    }

    private fun replaceWith(page: Page) {
        stack.clear()
        stack.add(page)
        render(1)
    }

    /**
     * 重畫目前的頁面。direction：1＝進入下一頁、-1＝返回、0＝原地更新（保留捲動位置、不做轉場）
     */
    private fun render(direction: Int = 0) {
        val page = stack.lastOrNull() ?: return
        titleView.text = page.title
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        titleView.setPadding(if (stack.size > 1) dp(4) else dp(16), 0, 0, 0)
        val keepY = scroll.scrollY
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (page in previewPages && isImeSelected()) {
            if (previewBox.visibility != View.VISIBLE) {
                previewBox.visibility = View.VISIBLE
                previewField.requestFocus()
                previewField.post { imm?.showSoftInput(previewField, InputMethodManager.SHOW_IMPLICIT) }
            }
        } else if (previewBox.visibility == View.VISIBLE) {
            imm?.hideSoftInputFromWindow(previewField.windowToken, 0)
            previewField.clearFocus()
            previewBox.visibility = View.GONE
        }
        content.layoutTransition = null
        content.removeAllViews()
        when (page) {
            Page.ONBOARDING -> buildOnboarding()
            Page.HOME -> buildHome()
            Page.METHODS -> buildMethods()
            Page.APPEARANCE -> buildAppearance()
            Page.INPUT -> buildInput()
            Page.ENGINE -> buildEngine()
            Page.DICTIONARY -> buildDictionary()
            Page.USER_WORDS -> buildUserWords()
            Page.DICT_DETAIL -> buildDictDetail()
            Page.PLUGINS -> buildPlugins()
            Page.ABOUT -> buildAbout()
        }
        if (direction == 0) {
            scroll.post { scroll.scrollTo(0, keepY) }
        } else {
            scroll.scrollTo(0, 0)
            liftBar(false)
            // 新頁面從前進方向滑入、淡入
            val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
            for (v in listOf<View>(content, titleView)) {
                v.animate().cancel()
                v.alpha = 0f
                v.translationX = dp(if (v === content) 40 else 16) * direction.toFloat()
                v.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(interp).start()
            }
        }
        // 頁面畫好後才開版面動畫：之後讀進來的資料讓卡片平滑長高，不會一下子把下面擠開
        content.post { content.layoutTransition = smoothLayout() }
    }

    private fun smoothLayout() = android.animation.LayoutTransition().apply {
        enableTransitionType(android.animation.LayoutTransition.CHANGING)
        setDuration(220)
    }

    // ---------- 元件 ----------

    /** Material 3 卡片；回傳卡片裡面放列的 LinearLayout */
    private fun card(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val c = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(cSurfaceContainer)
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        return inner
    }

    private fun sectionTitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge)
            setTextColor(cPrimary)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        })
    }

    private fun texts(parent: LinearLayout, title: String, summary: String?) {
        parent.addView(TextView(this).apply {
            text = title
            textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyLarge)
            setTextColor(cOnSurface)
        })
        if (!summary.isNullOrEmpty()) {
            parent.addView(TextView(this).apply {
                text = summary
                textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium)
                setTextColor(cOnSurfaceVariant)
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(0f, 1.1f)
            })
        }
    }

    private fun row(parent: LinearLayout, icon: Int?, clickable: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(64)
            if (clickable) {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
        }
        if (icon != null) {
            row.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(cOnSurfaceVariant)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
        }
        parent.addView(row)
        return row
    }

    private fun textColumn(row: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun navRow(parent: LinearLayout, icon: Int?, title: String, summary: String?, onClick: () -> Unit) {
        val row = row(parent, icon, clickable = true)
        texts(textColumn(row), title, summary)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_set_chevron_right)
            setColorFilter(cOnSurfaceVariant)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        row.setOnClickListener { onClick() }
    }

    private fun infoRow(parent: LinearLayout, title: String, summary: String?, icon: Int? = null) {
        texts(textColumn(row(parent, icon, clickable = false)), title, summary)
    }

    /** onRowClick 有給時，點列本身做那件事（例如看詞庫內容），只有開關切換開關 */
    private fun switchRow(
        parent: LinearLayout, title: String, summary: String, checked: Boolean,
        onRowClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit
    ): Pair<LinearLayout, com.google.android.material.materialswitch.MaterialSwitch> {
        val row = row(parent, null, clickable = true)
        texts(textColumn(row), title, summary)
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply { isChecked = checked }
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16)
        })
        row.setOnClickListener { if (onRowClick != null) onRowClick() else sw.toggle() }
        return row to sw
    }

    private fun boolSwitch(parent: LinearLayout, title: String, summary: String, key: String, default: Boolean) =
        switchRow(parent, title, summary, prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }.let { }

    private fun slider(
        parent: LinearLayout, title: String, summary: String,
        from: Float, to: Float, step: Float, value: Float, format: (Float) -> String, onChange: (Float) -> Unit
    ) {
        val row = row(parent, null, clickable = false)
        val col = textColumn(row)
        texts(col, title, summary)
        val valueText = TextView(this).apply {
            textAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium)
            setTextColor(cPrimary)
            text = format(value)
        }
        row.addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        val s = com.google.android.material.slider.Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = (Math.round(value / step) * step).coerceIn(from, to)
            setLabelFormatter { format(it) }
            addOnChangeListener { _, v, fromUser ->
                valueText.text = format(v)
                if (fromUser) onChange(v)
            }
        }
        parent.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })
    }

    private fun button(parent: LinearLayout, text: String, filled: Boolean = true, danger: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(this) else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.isAllCaps = false
        if (danger) {
            b.setTextColor(cError)
            b.strokeColor = android.content.res.ColorStateList.valueOf(cError)
        }
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(dp(16), dp(4), dp(16), dp(12))
        })
        return b
    }

    private fun testField(parent: LinearLayout, hint: String) {
        val layout = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = hint
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
        }
        layout.addView(com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })
        parent.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), dp(16))
        })
    }

    private fun confirm(title: String, message: String, action: String, onOk: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 引導 ----------

    private fun buildOnboarding() {
        val enabled = isImeEnabled()
        val selected = isImeSelected()

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(24))
        }
        hero.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(80), dp(80)))
        hero.addView(TextView(this).apply {
            text = "歡迎使用 Scorpio 智慧注音"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(cOnSurface)
            setPadding(0, dp(16), 0, dp(6))
        })
        hero.addView(TextView(this).apply {
            text = "整句選字、免聲調、打錯也猜得到。\n只要兩個步驟就能開始打字。"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(cOnSurfaceVariant)
            setLineSpacing(0f, 1.2f)
        })
        content.addView(hero)

        fun step(n: Int, done: Boolean, active: Boolean, title: String, summary: String, action: String, onClick: () -> Unit) {
            val c = card()
            val row = row(c, null, clickable = false)
            row.addView(FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (done || active) cPrimary else cSurfaceHighest)
                }
                if (done) {
                    addView(ImageView(this@SettingsActivity).apply {
                        setImageResource(R.drawable.ic_set_check)
                        setColorFilter(cOnPrimary)
                    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                } else {
                    addView(TextView(this@SettingsActivity).apply {
                        text = n.toString()
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (active) cOnPrimary else cOnSurfaceVariant)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) })
            texts(textColumn(row), title, if (done) "已完成" else summary)
            if (!done && active) button(c, action, onClick = onClick)
        }

        step(1, enabled, !enabled, "啟用輸入法",
            "在系統的「螢幕鍵盤」清單中，把【Scorpio 智慧注音】打開。系統會提醒輸入法可以讀取你打的字，這是所有輸入法都會出現的標準提示。",
            "前往系統設定") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        step(2, selected, enabled && !selected, "切換為目前的輸入法",
            "從輸入法清單選擇【Scorpio 智慧注音】。", "選擇輸入法") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }

        val c = card()
        texts(textColumn(row(c, null, clickable = false)), "試打看看", "例如打「ㄏㄣㄏㄠ」（不用聲調），候選列會出現「很好」。")
        testField(c, "在這裡試打…")

        if (enabled && selected) {
            button(content, "開始使用") {
                prefs.edit().putBoolean("onboarding_skipped", false).apply()
                replaceWith(Page.HOME)
            }
        } else {
            button(content, "稍後再設定", filled = false) {
                prefs.edit().putBoolean("onboarding_skipped", true).apply()
                replaceWith(Page.HOME)
            }
        }
    }

    // ---------- 首頁 ----------

    private fun buildHome() {
        val enabled = isImeEnabled()
        val ready = enabled && isImeSelected()
        val status = card()
        val row = row(status, if (ready) R.drawable.ic_set_check else R.drawable.ic_set_warning, clickable = false)
        texts(
            textColumn(row),
            if (ready) "輸入法運作中" else "輸入法尚未設定完成",
            if (ready) "版本 ${versionName()}" else if (!enabled) "還沒在系統中啟用" else "目前不是使用中的輸入法"
        )
        if (!ready) button(status, "完成設定") { open(Page.ONBOARDING) }
        testField(status, "在這裡試打…")

        // 有新版 App：首頁最上面提示（一天最多在背景檢查一次）
        com.p3dro.scorpio.ime.engine.AppUpdater.cachedNewer(this)?.let { tag ->
            val u = card()
            navRow(u, R.drawable.ic_set_info, "有新版本 $tag", "點這裡下載更新") { open(Page.ABOUT) }
        }
        if (System.currentTimeMillis() - com.p3dro.scorpio.ime.engine.AppUpdater.lastCheck(this) > 24 * 3600_000L) {
            lifecycleScope.launch {
                if (com.p3dro.scorpio.ime.engine.AppUpdater.check(this@SettingsActivity) is com.p3dro.scorpio.ime.engine.AppUpdater.Check.Available &&
                    stack.lastOrNull() == Page.HOME) render()
            }
        }

        sectionTitle("鍵盤")
        val nav = card()
        val methods = ChineseMethod.enabled(prefs).joinToString("、") { it.label }
        navRow(nav, R.drawable.ic_set_keyboard, "輸入法", "$methods、英文") { open(Page.METHODS) }
        navRow(nav, R.drawable.ic_set_palette, "介面外觀", "主題、照片背景、按鍵樣式、數字列") { open(Page.APPEARANCE) }
        navRow(nav, R.drawable.ic_set_engine, "輸入與按鍵", "震動、空白鍵、長按功能") { open(Page.INPUT) }
        navRow(nav, R.drawable.ic_set_extension, "工具列與擴充功能", "工具列要放哪些工具、最近使用的 Emoji、線上安裝擴充功能") { open(Page.PLUGINS) }

        sectionTitle("選字與詞庫")
        val more = card()
        navRow(more, R.drawable.ic_set_book, "選字引擎", "整句選字、動態感應區、按鍵校正") { open(Page.ENGINE) }
        navRow(more, R.drawable.ic_set_book, "字典與學習", "詞庫開關、匯入匯出、學習統計、自建詞") { open(Page.DICTIONARY) }
        navRow(more, R.drawable.ic_set_info, "關於", "版本、資料來源、GitHub") { open(Page.ABOUT) }
    }

    // ---------- 輸入法 ----------

    private fun buildMethods() {
        sectionTitle("中文輸入法")
        val c = card()
        val enabled = ChineseMethod.enabled(prefs).toMutableSet()
        val descriptions = mapOf(
            ChineseMethod.DACHEN to "標準大千 41 鍵注音鍵盤，支援免聲調、簡拼與打錯修正。",
            ChineseMethod.HSU to "許氏 26 鍵：一個鍵代表一到兩個注音，自動判斷。",
            ChineseMethod.ETEN26 to "倚天 26 鍵：一個鍵代表一到兩個注音，自動判斷。",
            ChineseMethod.DACHEN26 to "大千 26 鍵：大千的位置壓縮到 26 個字母鍵。",
            ChineseMethod.PINYIN to "用英文字母打漢語拼音（zhong → 中），可只打聲母（kbd → 看不到）。",
            ChineseMethod.CANGJIE to "倉頡碼（Unicode 倉頡第三代），最多 5 碼，打一半就會列出常用字。",
            ChineseMethod.QUICK to "速成：只打倉頡碼的第一碼和最後一碼，依常用程度排序。"
        )
        for (m in ChineseMethod.entries) {
            switchRow(c, m.label, descriptions[m].orEmpty(), m in enabled) { on ->
                if (on) enabled.add(m) else enabled.remove(m)
                if (enabled.isEmpty()) {
                    // 至少保留一個
                    enabled.add(ChineseMethod.DACHEN)
                    render()
                }
                prefs.edit().putString(ChineseMethod.PREF_ENABLED, ChineseMethod.entries.filter { it in enabled }.joinToString(",") { it.id }).apply()
            }
        }
        infoRow(card(), "切換方式", "按鍵盤上的地球鍵，依上面的順序切換啟用的輸入法，最後是英文。拼音與 26 鍵注音共用同一套詞庫、整句選字與學習紀錄；倉頡、速成選字後一樣會出現接續詞。")
    }

    // ---------- 介面外觀 ----------

    private fun buildAppearance() {
        sectionTitle("主題")
        val t = card()
        val current = prefs.getString("keyboard_theme", "dynamic") ?: "dynamic"
        val hasPhoto = java.io.File(filesDir, "keyboard_bg.jpg").exists()
        val themes = listOf(
            Triple("dynamic", "動態取色（預設）", if (android.os.Build.VERSION.SDK_INT >= 31) "跟著桌布配色，換桌布鍵盤顏色也會跟著變。" else "需要 Android 12 以上，這支手機會使用經典配色。"),
            Triple("system", "經典", "灰白配色，跟著系統切換淺色和深色。"),
            Triple("light", "淺色", "永遠使用淺色。"),
            Triple("dark", "深色", "永遠使用深色。"),
            Triple("photo", "照片", if (hasPhoto) "用你選的照片當鍵盤背景，按鍵半透明。" else "選一張照片當鍵盤背景。")
        )
        for ((id, title, summary) in themes) {
            val row = row(t, null, clickable = true)
            texts(textColumn(row), title, summary)
            val radio = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                isChecked = id == current
                isClickable = false
            }
            row.addView(radio)
            row.setOnClickListener {
                if (id == "photo" && !hasPhoto) {
                    pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    prefs.edit().putString("keyboard_theme", id).apply()
                    render()
                }
            }
        }
        if (current == "photo") {
            button(t, "更換照片", filled = false) {
                pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            slider(t, "照片調暗", "照片太亮時調高，按鍵上的字比較清楚。", 0f, 85f, 5f, prefs.getInt("photo_dim", 35).toFloat(), { "${it.toInt()}%" }) {
                prefs.edit().putInt("photo_dim", it.toInt()).apply()
            }
            slider(t, "照片透明度", "調高時照片變淡、透出底下的黑色。", 0f, 90f, 5f,
                (100 - prefs.getInt(com.p3dro.scorpio.ime.ScorpioImeService.PREF_PHOTO_ALPHA, 100)).toFloat(), { "${it.toInt()}%" }) {
                prefs.edit().putInt(com.p3dro.scorpio.ime.ScorpioImeService.PREF_PHOTO_ALPHA, 100 - it.toInt()).apply()
            }
        }

        sectionTitle("按鍵")
        val ks = card()
        boolSwitch(ks, "按鍵底色", "關掉後按鍵只剩文字，配照片背景最好看。", com.p3dro.scorpio.ime.ScorpioImeService.PREF_KEY_BG, true)
        boolSwitch(ks, "按鍵邊框", "每個按鍵加一圈細框，按鍵底色關掉時也看得出按鍵的範圍。", com.p3dro.scorpio.ime.ScorpioImeService.PREF_KEY_BORDER, false)
        boolSwitch(ks, "按鍵漣漪效果", "按下時從手指處擴散的動畫。關掉是按下瞬間變色，每按一鍵少畫三四十幀，比較省電、打字更順。", com.p3dro.scorpio.ime.ScorpioImeService.PREF_KEY_RIPPLE, false)

        sectionTitle("按鍵大小")
        val sz = card()
        slider(sz, "按鍵高度", "調整後觸控定位會依實際按鍵大小換算，不影響準確度。", 80f, 130f, 5f,
            prefs.getFloat("key_height_scale", 1.0f) * 100f, { "${it.toInt()}%" }) {
            prefs.edit().putFloat("key_height_scale", it / 100f).apply()
        }

        sectionTitle("鍵盤")
        val k = card()
        boolSwitch(k, "上方圓角", "鍵盤左上、右上的圓弧角；關掉是直角。", com.p3dro.scorpio.ime.ScorpioImeService.PREF_ROUNDED, true)
        boolSwitch(k, "常駐數字列", "注音鍵盤上方多一排 1～0，打數字不用切到符號鍵盤。", com.p3dro.scorpio.ime.ScorpioImeService.PREF_NUMBER_ROW, false)
        boolSwitch(k, "按鍵預覽泡泡", "按住按鍵時在上方放大顯示按到的字，長按時泡泡會變色。", "key_preview", true)

        sectionTitle("候選字")
        val c = card()
        boolSwitch(c, "候選列穿插 Emoji 與顏文字", "關閉後候選列只顯示字詞，Emoji 與顏文字改到展開面板的分頁。", "inline_emoji_kaomoji", true)

        texts(textColumn(row(c, null, clickable = false)), "展開面板每排格數", "按下候選列右邊的箭頭展開時，單字佔 1 格、詞佔 2 格。")
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = HashMap<Int, Int>()
        for (n in listOf(5, 6, 7)) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = "$n 格"
                isAllCaps = false
            }
            ids[b.id] = n
            group.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (prefs.getInt("candidate_grid_cells", 6) == n) group.check(b.id)
        }
        group.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) ids[id]?.let { prefs.edit().putInt("candidate_grid_cells", it).apply() }
        }
        c.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(12))
        })
    }

    // ---------- 輸入與按鍵 ----------

    private fun buildInput() {
        sectionTitle("回饋")
        val f = card()
        boolSwitch(f, "按鍵震動", "按下按鍵時輕微震動。", "vibrate_on_keypress", false)

        sectionTitle("空白鍵")
        val s = card()
        infoRow(s, "按一下", "還沒打聲調時是一聲（大千標準）；已經打完聲調時送出第一個候選字。")
        switchRow(
            s, "連按兩下送出注音符號",
            "關閉：連按兩下送出第一個候選字。\n開啟：直接送出打的注音，例如「ㄏㄠˇ」。",
            prefs.getString("double_space_action", "candidate") == "raw"
        ) { prefs.edit().putString("double_space_action", if (it) "raw" else "candidate").apply() }

        sectionTitle("長按功能")
        val l = card()
        infoRow(l, "注音鍵", "長按後放開，直接輸入注音符號本身（例如「ㄅ」）。長按時泡泡變色，滑開就取消。")
        infoRow(l, "「，」鍵", "長按輸入句號「。」。")
        infoRow(l, "地球鍵", "長按切換到其他輸入法。")
        infoRow(l, "候選字", "帶小鉛筆的是你自己打出來的詞，長按可以刪除。")
    }

    // ---------- 選字引擎 ----------

    private fun buildEngine() {
        sectionTitle("運作方式")
        val e = card()
        infoRow(e, "整句選字", "依詞庫與前後文，一次挑出整句最通順的組合；打錯一兩個鍵也會一起考慮。")
        infoRow(e, "免聲調與簡拼", "可以不打聲調，也可以只打聲母，例如「ㄎㄅㄉ」→「看不到」。")
        infoRow(e, "動態感應區", "依你前面打的字，在背景微調每個按鍵的判定範圍（注音與英文）。點在按鍵正中央一律照按的鍵，不會被改掉。")
        infoRow(e, "打錯自動修正", "常打錯的注音（例如 ㄉㄨㄠ→ㄉㄧㄠ）選過一次正確的字後就會記住。")

        sectionTitle("個人化按鍵校正")
        val t = card()
        val n = dict.spatial.sampleCount()
        if (n < 300) {
            infoRow(t, "學習中：已收集 $n / 300 次按鍵", "輸入法會記住你按每個鍵時習慣偏哪個方向，自動校正判定位置。收集滿 300 次就建立好你的基準。")
        } else {
            infoRow(t, "已完成校正，持續更新中", "基準已建立。之後依最近約 300 次按鍵持續微調：換手機殼、換握法也會慢慢跟上，舊習慣自然淡出。")
        }
        infoRow(t, "刪掉重打也會學", "只刪一個鍵就馬上改按別的鍵，會把按歪的那一下記成「想按的是新的鍵」（注音與 QWERTY 鍵盤都會）。")
        button(t, "重設按鍵校正", filled = false) {
            confirm("重設按鍵校正？", "清除已學到的按鍵習慣偏移（注音與 QWERTY 鍵盤），從頭開始學習。選字學習不受影響。", "重設") {
                dict.resetTouchCalibration()
                render()
            }
        }

        sectionTitle("我的按鍵落點")
        val h = card()
        val zhuyin = dict.spatial.keySpreads()
        val qwerty = dict.qwerty.keySpreads()
        if (zhuyin.isEmpty() && qwerty.isEmpty()) {
            infoRow(h, "還沒有資料", "打字、選字之後，這裡會畫出你按每個鍵的習慣（注音鍵盤與 QWERTY 鍵盤各一張）。")
            return
        }
        fun pct(v: Long, total: Long) = "%.1f%%".format(java.util.Locale.ROOT, v * 100.0 / total)
        fun heatmap(keys: Map<Char, Pair<Double, Double>>, cols: Int, rows: Int, spreads: Map<Char, com.p3dro.scorpio.ime.engine.KeyHabits.KeySpread>) {
            h.addView(
                TapHeatmapView(this, keys, cols, rows, spreads, keyColor = cSurfaceHighest, labelColor = cOnSurface, heatColor = cPrimary),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(12), dp(4), dp(12), dp(4))
                }
            )
        }
        if (zhuyin.isNotEmpty()) {
            val grid = com.p3dro.scorpio.ime.engine.SpatialModel.GRID
            val keys = buildMap { for (r in grid.indices) for (c in grid[r].indices) put(grid[r][c], c.toDouble() to r.toDouble()) }
            val acc = dict.tapAccuracy()
            infoRow(h, "注音鍵盤", if (acc[0] > 0) "判對率 ${pct(acc[1], acc[0])}（共 ${acc[0]} 次）；不看散佈、只看偏移是 ${pct(acc[2], acc[0])}。" else null)
            heatmap(keys, grid[0].size, grid.size, zhuyin)
        }
        if (qwerty.isNotEmpty()) {
            val acc = dict.qwertyAccuracy()
            infoRow(
                h, "QWERTY 鍵盤（英文、26 鍵注音、拼音、倉頡、速成）",
                if (acc[0] > 0) "只看落點的判對率 ${pct(acc[1], acc[0])}（共 ${acc[0]} 次）；沒有個人化是 ${pct(acc[2], acc[0])}。" else null
            )
            heatmap(com.p3dro.scorpio.ime.engine.QwertyModel.POS, 10, 3, qwerty)
        }
        infoRow(h, "怎麼看", "圓點是你瞄準這個鍵時平均按到的位置，細線從鍵中心指向它；暈開的範圍是落點的散佈，顏色越深代表這個鍵的樣本越多。")
    }

    // ---------- 字典與學習 ----------

    /** 資料讀進來之前的佔位；卡片開版面動畫，資料到了平滑展開、淡入 */
    private fun loadingText(parent: LinearLayout) = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
        isIndeterminate = true
        trackCornerRadius = dp(4)
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), dp(28), dp(20), dp(28))
        })
        parent.layoutTransition = smoothLayout().apply {
            disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
        }
    }

    private fun buildDictionary() {
        sectionTitle("學習統計")
        val s = card()
        val loading = loadingText(s)
        lifecycleScope.launch {
            val st = dict.learningStats()
            if (stack.lastOrNull() != Page.DICTIONARY) return@launch
            s.removeView(loading)
            infoRow(s, "學過的中文詞：${st.words}", "選過的字詞會往前排，加到一定程度就不再增加。")
            infoRow(s, "自建詞：${st.userCreated}", "系統詞典沒有、由你打出來的詞（候選列上帶小鉛筆）。")
            infoRow(s, "接續搭配：${st.bigrams}", "打完一個詞後預測下一個詞用的個人搭配（中英文）。")
            infoRow(s, "英文單字：${st.englishWords}", null)
            infoRow(s, "打錯修正：${st.typos}", null)
        }

        sectionTitle("管理")
        val m = card()
        navRow(m, R.drawable.ic_user_word, "自建詞", "查看並刪除你打出來的詞") { open(Page.USER_WORDS) }
        button(m, "清除所有學習紀錄", filled = false, danger = true) {
            confirm(
                "清除所有學習紀錄？",
                "學過的字詞、自建詞、接續搭配、英文單字與打錯修正都會刪除，無法復原。按鍵校正不受影響。",
                "清除"
            ) {
                lifecycleScope.launch {
                    dict.clearLearning()
                    render()
                }
            }
        }

        sectionTitle("線上更新")
        val up = card()
        val updatePrefs = getSharedPreferences(com.p3dro.scorpio.ime.engine.PackUpdater.PREFS, Context.MODE_PRIVATE)
        switchRow(
            up, "自動更新詞庫", "每天在 Wi-Fi 下檢查一次，有新版的流行用語、地名、接續詞語料就自動下載。",
            updatePrefs.getBoolean(com.p3dro.scorpio.ime.engine.PackUpdater.PREF_AUTO, true)
        ) { on ->
            updatePrefs.edit().putBoolean(com.p3dro.scorpio.ime.engine.PackUpdater.PREF_AUTO, on).apply()
            com.p3dro.scorpio.ime.engine.PackUpdater.schedule(this)
        }
        fun lastCheckText(): String {
            val t = updatePrefs.getLong(com.p3dro.scorpio.ime.engine.PackUpdater.PREF_LAST_CHECK, 0)
            return if (t == 0L) "還沒檢查過" else "上次檢查：" + java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(t))
        }
        val checkButton = button(up, "立即檢查更新", filled = false) {}
        val status = TextView(this).apply {
            text = lastCheckText()
            textAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall)
            setTextColor(cOnSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        up.addView(status)
        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkButton.text = "檢查中…"
            lifecycleScope.launch {
                val r = com.p3dro.scorpio.ime.engine.PackUpdater.checkAndUpdate(this@SettingsActivity)
                val msg = when {
                    r.error != null -> r.error
                    r.updated.isEmpty() && r.failed.isEmpty() -> "目前已經是最新版"
                    else -> (if (r.updated.isNotEmpty()) "已更新：" + r.updated.joinToString("、") { "${it.first} ${it.second}" } else "") +
                        (if (r.failed.isNotEmpty()) "（${r.failed.joinToString("、")} 更新失敗）" else "")
                }
                android.widget.Toast.makeText(this@SettingsActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                if (stack.lastOrNull() != Page.DICTIONARY) return@launch
                if (r.updated.isNotEmpty()) render() else {
                    checkButton.isEnabled = true
                    checkButton.text = "立即檢查更新"
                    status.text = lastCheckText()
                }
            }
        }

        // 詞庫分組列出；匯入的詞庫會一直變多，放在最下面
        // 開關就是安裝：打開時抓線上最新版（還沒下載的就下載安裝），之後自動更新；關掉就停用。點一下可以看詞庫內容
        val groups = linkedMapOf(
            "basic" to ("基本" to null),
            "places" to ("地名與地標" to null),
            "terms" to ("專業術語" to null),
            "symbols" to ("表情符號" to null),
            "bigram" to ("接續詞語料" to "影響整句選字與選完字後聯想的下一個詞，不影響打得出哪些詞。開了多個來源時取搭配最強的那個。"),
            "import" to ("匯入的詞庫" to null)
        )
        val cards = HashMap<String, LinearLayout>()
        val loadings = HashMap<String, View>()
        for ((key, v) in groups) {
            sectionTitle(v.first)
            val c = card()
            if (v.second != null) infoRow(c, "詞與詞怎麼接", v.second)
            if (key == "import") {
                navRow(c, null, "匯入詞庫", "每行一個詞的文字檔，也能直接匯入小麥注音、新酷音、RIME、Gboard 的詞庫檔；沒寫注音會自動推") {
                    importDict.launch(arrayOf("text/*", "application/octet-stream"))
                }
                navRow(c, null, "匯出個人詞庫", "把學過的詞與自建詞存成文字檔，換手機或備份時可以再匯入") {
                    exportDict.launch("scorpio_個人詞庫.txt")
                }
            }
            cards[key] = c
            loadings[key] = loadingText(c)
        }
        lifecycleScope.launch {
            val available = com.p3dro.scorpio.ime.engine.PackUpdater.refreshManifest(this@SettingsActivity)
            val manifest = available.associateBy { it.id }
            val onlineOnly = available.filter { !it.bundled }.map { it.id }.toSet()
            val dicts = dict.dictionaries(available)
            if (stack.lastOrNull() != Page.DICTIONARY) return@launch
            for ((key, c) in cards) c.removeView(loadings[key])
            fun groupOf(d: ScorpioDictManager.DictionaryInfo) = manifest[d.id]?.category?.takeIf { it in groups } ?: when {
                d.group == "bigram" -> "bigram"
                d.removable -> "import"
                d.id in setOf("emoji", "kaomoji") -> "symbols"
                d.id.contains("places") || d.id.contains("landmarks") -> "places"
                else -> "basic"
            }
            // 專業術語目前只有可下載的，沒有就不顯示整組
            if (dicts.none { groupOf(it) == "terms" }) {
                val c = cards.getValue("terms")
                (c.parent as? View)?.visibility = View.GONE
                content.getChildAt(content.indexOfChild(c.parent as View) - 1)?.visibility = View.GONE
            }
            for (d in dicts) {
                val list = cards.getValue(groupOf(d))
                val bigram = d.group == "bigram"
                fun summary(installed: Boolean, count: Int, version: String?) =
                    "${d.description}・${"%,d".format(count)} ${if (bigram) "組" else "個"}" +
                        (if (!installed) "・尚未下載（約 ${"%.1f".format(d.downloadSize / 1048576.0)} MB）" else "") +
                        (d.source?.let { "\n來源：$it" } ?: "") + (version?.let { "\n版本 $it" } ?: "")
                var installed = d.installed
                val openDetail = {
                    if (installed) {
                        detailDict = d
                        open(Page.DICT_DETAIL)
                    } else {
                        android.widget.Toast.makeText(this@SettingsActivity, "打開開關下載後就能看內容", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                if (!d.toggleable) {
                    navRow(list, null, d.name, summary(true, d.count, d.version)) { openDetail() }
                    continue
                }
                var ignore = false
                lateinit var sw: com.google.android.material.materialswitch.MaterialSwitch
                lateinit var r: LinearLayout
                val pair = switchRow(list, d.name, summary(installed, d.count, d.version), d.enabled, onRowClick = openDetail) { on ->
                    if (ignore) return@switchRow
                    val sub = (r.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
                    lifecycleScope.launch {
                        if (!on && d.id in onlineOnly) {
                            // 只能線上安裝的：關掉就整個移除，回到「尚未下載」
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dict.uninstallPack(d.id) }
                            installed = false
                            sub.text = summary(false, manifest[d.id]?.rows ?: d.count, manifest[d.id]?.version)
                            return@launch
                        }
                        dict.setDictionaryEnabled(d.id, on)
                        if (!on || d.removable || d.id == "emoji") return@launch
                        // 有東西要下載時才在這列底下出現進度條：下載顯示百分比，套用時轉成不確定進度
                        var bar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
                        val before = sub.text
                        sw.isEnabled = false
                        val ok = com.p3dro.scorpio.ime.engine.PackUpdater.ensureLatest(this@SettingsActivity, d.id) { f ->
                            runOnUiThread {
                                val b = bar ?: com.google.android.material.progressindicator.LinearProgressIndicator(this@SettingsActivity).apply {
                                    max = 100
                                    trackCornerRadius = dp(4)
                                    list.addView(this, list.indexOfChild(r) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                        setMargins(dp(20), 0, dp(20), dp(12))
                                    })
                                    bar = this
                                }
                                if (f < 0) {
                                    if (!b.isIndeterminate) {
                                        b.visibility = View.INVISIBLE
                                        b.isIndeterminate = true
                                        b.visibility = View.VISIBLE
                                    }
                                    sub.text = "安裝中…"
                                } else {
                                    b.setProgressCompat((f * 100).toInt(), true)
                                    sub.text = "下載中 ${(f * 100).toInt()}%"
                                }
                            }
                        }
                        bar?.let { list.removeView(it) }
                        sw.isEnabled = true
                        val m = manifest[d.id]
                        when {
                            ok -> {
                                installed = true
                                sub.text = summary(true, m?.rows ?: d.count, m?.version ?: d.version)
                                android.widget.Toast.makeText(this@SettingsActivity,
                                    if (d.installed) "已更新「${d.name}」到最新版" else "已安裝「${d.name}」", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            !installed -> {
                                sub.text = before
                                android.widget.Toast.makeText(this@SettingsActivity, "下載失敗，確認網路後再試一次", android.widget.Toast.LENGTH_SHORT).show()
                                dict.setDictionaryEnabled(d.id, false)
                                ignore = true
                                sw.isChecked = false
                                ignore = false
                            }
                            else -> sub.text = before
                        }
                    }
                }
                r = pair.first
                sw = pair.second
                if (d.removable) {
                    val rr = row(list, null, clickable = false)
                    rr.setPadding(dp(16), 0, dp(4), dp(4))
                    rr.addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    rr.addView(TextView(this@SettingsActivity).apply {
                        text = "刪除這個詞庫"
                        textSize = 14f
                        setTextColor(cError)
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        setOnClickListener {
                            confirm("刪除「${d.name}」？", "這個匯入的詞庫會從輸入法移除，原本的檔案不受影響。", "刪除") {
                                lifecycleScope.launch {
                                    dict.removeImportedDictionary(d.id)
                                    render()
                                }
                            }
                        }
                    })
                }
            }
        }
    }

    // ---------- 詞庫內容 ----------

    private var detailDict: ScorpioDictManager.DictionaryInfo? = null

    private fun buildDictDetail() {
        val d = detailDict ?: return
        titleView.text = d.name
        val head = card()
        infoRow(head, d.name, "${d.description}・${"%,d".format(d.count)} ${if (d.group == "bigram") "組" else "個"}" +
            (d.source?.let { "\n來源：$it" } ?: "") + (d.version?.let { "\n版本 $it" } ?: ""))
        val searchCard = card()
        val layout = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "搜尋詞或注音"
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
            setStartIconDrawable(android.R.drawable.ic_menu_search)
        }
        val input = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        layout.addView(input)
        searchCard.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(12), dp(16), dp(12))
        })
        val results = card()
        val pageSize = 100
        var offset = 0
        var query = ""
        var job: kotlinx.coroutines.Job? = null
        var more: View? = null

        fun load(reset: Boolean) {
            job?.cancel()
            if (reset) {
                offset = 0
                results.removeAllViews()
            }
            more?.let { results.removeView(it) }
            val loading = loadingText(results)
            job = lifecycleScope.launch {
                val rows = dict.browseDictionary(d.id, query, offset, pageSize)
                results.removeView(loading)
                if (rows.isEmpty() && offset == 0) {
                    infoRow(results, if (query.isBlank()) "這個詞庫是空的" else "找不到「$query」", null)
                    return@launch
                }
                for ((main, sub) in rows) {
                    val r = row(results, null, clickable = false)
                    r.minimumHeight = dp(48)
                    r.setPadding(dp(20), dp(8), dp(20), dp(8))
                    texts(textColumn(r), main, sub.ifEmpty { null })
                }
                offset += rows.size
                if (rows.size == pageSize) {
                    more = button(results, "載入更多", filled = false) { load(false) }
                }
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                content.removeCallbacks(searchRunnable)
                searchRunnable = Runnable {
                    query = q
                    load(true)
                }
                content.postDelayed(searchRunnable, 300)
            }
        })
        load(true)
    }

    private var searchRunnable: Runnable? = null

    /** 工具列的工具清單：開關＋拖拉排序（放開時存順序、鍵盤即時更新） */
    private fun toolList(parent: LinearLayout) {
        val items = com.p3dro.scorpio.ime.engine.Toolbar.all(this).toMutableList()
        lateinit var touchHelper: androidx.recyclerview.widget.ItemTouchHelper

        class Holder(val view: LinearLayout, val icon: ImageView, val name: TextView,
                     val sw: com.google.android.material.materialswitch.MaterialSwitch, val handle: ImageView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(p: ViewGroup, viewType: Int): Holder {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(20), dp(4), dp(8), dp(4))
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val icon = ImageView(this@SettingsActivity).apply { setColorFilter(cOnSurfaceVariant) }
                row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
                val name = TextView(this@SettingsActivity).apply {
                    textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyLarge)
                    setTextColor(cOnSurface)
                }
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sw = com.google.android.material.materialswitch.MaterialSwitch(this@SettingsActivity)
                row.addView(sw)
                val handle = ImageView(this@SettingsActivity).apply {
                    setImageResource(R.drawable.ic_set_drag)
                    setColorFilter(cOnSurfaceVariant)
                    contentDescription = "拖拉調整順序"
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
                return Holder(row, icon, name, sw, handle)
            }

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(h: Holder, position: Int) {
                val t = items[position]
                h.icon.setImageResource(t.icon)
                h.name.text = t.name
                h.sw.setOnCheckedChangeListener(null)
                h.sw.isChecked = t.visible
                h.sw.setOnCheckedChangeListener { _, on ->
                    val i = h.bindingAdapterPosition
                    if (i < 0) return@setOnCheckedChangeListener
                    items[i] = items[i].copy(visible = on)
                    com.p3dro.scorpio.ime.engine.Toolbar.setVisible(this@SettingsActivity, items[i].id, on)
                }
                h.view.setOnClickListener { h.sw.toggle() }
                h.handle.setOnTouchListener { _, e ->
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                    false
                }
            }
        }
        touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                                to: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                items.add(b, items.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // 拖起來的那列浮起、換成稍深的底色
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        setBackgroundColor(cSurfaceHighest)
                        elevation = dp(4).toFloat()
                    }
                }
            }

            override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.background = null
                viewHolder.itemView.elevation = 0f
                com.p3dro.scorpio.ime.engine.Toolbar.setOrder(this@SettingsActivity, items.map { it.id })
            }

            override fun isLongPressDragEnabled() = true
        })
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        touchHelper.attachToRecyclerView(rv)
        parent.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 關於頁的「檢查 App 更新」：有新版就顯示更新內容，按「下載並安裝」交給系統安裝 */
    private fun appUpdateRow(parent: LinearLayout) {
        val U = com.p3dro.scorpio.ime.engine.AppUpdater
        val r = row(parent, null, clickable = true)
        val col = textColumn(r)
        texts(col, "檢查 App 更新", U.cachedNewer(this)?.let { "有新版本 $it" } ?: "從 GitHub Releases 下載新版")
        val sub = col.getChildAt(1) as TextView
        var busy = false
        r.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            sub.text = "檢查中…"
            lifecycleScope.launch {
                when (val c = U.check(this@SettingsActivity)) {
                    is com.p3dro.scorpio.ime.engine.AppUpdater.Check.Available -> {
                        sub.text = "有新版本 ${c.release.tag}"
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("新版本 ${c.release.tag}")
                            .setMessage(c.release.notes.ifBlank { "（沒有更新說明）" }.take(1500))
                            .setPositiveButton(if (c.release.apkUrl != null) "下載並安裝" else "開啟下載頁") { _, _ ->
                                if (c.release.apkUrl == null) {
                                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.release.page)))
                                    return@setPositiveButton
                                }
                                sub.text = "下載中…"
                                lifecycleScope.launch {
                                    val apk = U.download(this@SettingsActivity, c.release)
                                    if (apk == null) {
                                        sub.text = "下載失敗，請稍後再試"
                                    } else {
                                        sub.text = "下載完成"
                                        if (!U.install(this@SettingsActivity, apk)) {
                                            android.widget.Toast.makeText(this@SettingsActivity, "請允許安裝更新，回來後再按一次", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("稍後", null)
                            .show()
                    }
                    com.p3dro.scorpio.ime.engine.AppUpdater.Check.UpToDate -> sub.text = "已經是最新版"
                    com.p3dro.scorpio.ime.engine.AppUpdater.Check.NoRelease -> sub.text = "還沒有公開的版本（原始碼 repo 公開後才查得到）"
                    is com.p3dro.scorpio.ime.engine.AppUpdater.Check.Failed -> sub.text = c.reason
                }
                busy = false
            }
        }
    }

    // ---------- 擴充功能 ----------

    private fun buildPlugins() {
        sectionTitle("工具列")
        val tb = card()
        boolSwitch(tb, "最近使用的 Emoji 與顏文字", "工具列中間常駐最近用過的 Emoji 和顏文字，可以左右滑。",
            com.p3dro.scorpio.ime.ScorpioImeService.PREF_RECENT_BAR, false)
        infoRow(tb, "左邊的工具", "用開關決定要不要顯示，按住右邊的把手拖拉調整順序；全部關掉也可以。")
        toolList(tb)

        sectionTitle("擴充功能")
        val intro = card()
        infoRow(intro, "線上安裝的工具",
            "裝好後出現在上面的工具清單裡。擴充功能只能插入文字、轉換你選取的文字、用選取的文字開網址；" +
                "不含程式碼，也讀不到你打的字，只有你點了它的項目時才會用到目前選取的文字。")
        val installed = com.p3dro.scorpio.ime.engine.Plugins.installed(this)
        if (installed.isNotEmpty()) {
            sectionTitle("已安裝")
            val c = card()
            for (p in installed) {
                val r = row(c, com.p3dro.scorpio.ime.engine.Plugins.iconRes(p.icon), clickable = false)
                texts(textColumn(r), p.name, "${p.description}\n版本 ${p.version}".trim())
                r.addView(TextView(this).apply {
                    text = "移除"
                    textSize = 14f
                    setTextColor(cError)
                    setPadding(dp(16), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        com.p3dro.scorpio.ime.engine.Plugins.uninstall(this@SettingsActivity, p.id)
                        render()
                    }
                })
            }
        }
        sectionTitle("可以安裝")
        val avail = card()
        val loading = loadingText(avail)
        lifecycleScope.launch {
            val list = com.p3dro.scorpio.ime.engine.PackUpdater.availablePlugins(this@SettingsActivity)
            if (stack.lastOrNull() != Page.PLUGINS) return@launch
            avail.removeView(loading)
            val ids = installed.map { it.id }.toSet()
            val todo = list?.filter { it.id !in ids }
            when {
                list == null -> infoRow(avail, "連不上擴充功能清單", "確認網路後再試一次。")
                todo.isNullOrEmpty() -> infoRow(avail, "全部都裝好了", null)
                else -> for (p in todo) {
                    val r = row(avail, com.p3dro.scorpio.ime.engine.Plugins.iconRes(p.icon), clickable = false)
                    texts(textColumn(r), p.name, p.description.ifEmpty { null })
                    val b = MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "安裝"
                        isAllCaps = false
                    }
                    b.setOnClickListener {
                        b.isEnabled = false
                        b.text = "安裝中…"
                        lifecycleScope.launch {
                            val ok = com.p3dro.scorpio.ime.engine.PackUpdater.installPlugin(this@SettingsActivity, p)
                            android.widget.Toast.makeText(this@SettingsActivity, if (ok) "已安裝「${p.name}」" else "安裝失敗", android.widget.Toast.LENGTH_SHORT).show()
                            if (stack.lastOrNull() == Page.PLUGINS) render()
                        }
                    }
                    r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
                }
            }
        }
    }

    private fun buildUserWords() {
        val c = card()
        val loading = loadingText(c)
        lifecycleScope.launch {
            val words = dict.userCreatedWords()
            if (stack.lastOrNull() != Page.USER_WORDS) return@launch
            c.removeView(loading)
            if (words.isEmpty()) {
                infoRow(c, "目前沒有自建詞", "系統詞典沒有、但你選過的詞會出現在這裡。")
                return@launch
            }
            titleView.text = "自建詞（${words.size}）"
            for ((word, count) in words) {
                val row = row(c, null, clickable = false)
                row.minimumHeight = dp(52)
                row.setPadding(dp(16), dp(6), dp(4), dp(6))
                texts(textColumn(row), word, "用過 $count 次")
                row.addView(TextView(this@SettingsActivity).apply {
                    text = "刪除"
                    textSize = 14f
                    setTextColor(cError)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener {
                        lifecycleScope.launch {
                            if (dict.deleteUserWord(word)) c.removeView(row)
                        }
                    }
                })
            }
        }
    }

    // ---------- 關於 ----------

    private fun buildAbout() {
        val a = card()
        infoRow(a, "Scorpio 智慧注音", "版本 ${versionName()}")
        fun link(title: String, url: String) = navRow(a, null, title, url.removePrefix("https://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "沒有可以開啟網址的 App", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        appUpdateRow(a)
        link("原始碼", "https://github.com/ScorpionSkypoo/ScorpionBopomofo")
        link("詞庫與擴充功能", "https://github.com/ScorpionSkypoo/scorpio-ime-data")
        sectionTitle("資料來源")
        val d = card()
        infoRow(d, "英文單字頻率", "FrequencyWords（hermitdave，CC BY-SA 4.0）")
        infoRow(d, "英文接續詞", "OpenSubtitles 對白語料（OPUS 專案）統計")
        infoRow(d, "倉頡、速成碼表", "Unicode Unihan（Unicode License v3）")
        lifecycleScope.launch {
            val dicts = dict.dictionaries()
            if (stack.lastOrNull() != Page.ABOUT) return@launch
            for (x in dicts) {
                val src = x.source ?: continue
                infoRow(d, if (x.group == "bigram") "接續詞：${x.name}" else x.name, src)
            }
        }
    }
}
