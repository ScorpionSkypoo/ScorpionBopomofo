package com.p3dro.scorpio.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.p3dro.scorpio.ime.engine.BopomofoComposer
import com.p3dro.scorpio.ime.engine.ScorpioDictManager
import com.p3dro.scorpio.ime.ui.CandidateAdapter
import com.p3dro.scorpio.ime.ui.RepeatTouchListener
import com.p3dro.scorpio.ime.ui.SpatialKeyLayout
import com.p3dro.scorpio.ime.ui.ZhuyinKeyView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScorpioImeService : InputMethodService() {

    enum class KeyboardMode {
        ZHUYIN, ENGLISH, SYMBOLS, EXPANDED, HANDWRITING, NUMPAD
    }

    /** 輸入框要什麼（依 EditorInfo.inputType）：決定一打開是哪個鍵盤 */
    private enum class FieldKind { TEXT, NUMBER, PHONE, DATETIME, PASSWORD, EMAIL, URI }

    enum class ShiftState {
        OFF, ONCE, LOCKED
    }

    private lateinit var composer: BopomofoComposer
    private lateinit var dictManager: ScorpioDictManager
    private lateinit var prefs: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var candidateAdapter: CandidateAdapter? = null
    private var rvCandidates: RecyclerView? = null
    private var candidateJob: Job? = null
    private var englishCandidateJob: Job? = null
    private var predictionJob: Job? = null

    private var layoutIdleBar: View? = null

    // 自動填入建議（簡訊驗證碼、密碼、地址）：由系統的自動填入服務提供，鍵盤只負責顯示；點了由系統填進輸入框
    private var scrollInlineSuggestions: View? = null
    private var layoutInlineSuggestions: LinearLayout? = null
    private var scrollRecent: View? = null
    private var inlineSuggestionViews: List<View> = emptyList()
    private var layoutCandidateActive: View? = null

    private var panelZhuyin: View? = null
    private var panelEnglish: View? = null
    private var panelSymbols: View? = null
    private var panelHandwriting: LinearLayout? = null
    private var panelNumpad: View? = null
    private var fieldKind = FieldKind.TEXT
    // 數字鍵盤是使用者從符號頁的「1234」打開的（不是輸入框要的）：多一排運算符號，可以返回、切到符號頁
    private var numpadFromUser = false
    private var handwritingView: com.p3dro.scorpio.ime.ui.HandwritingView? = null
    private val handwriting by lazy { com.p3dro.scorpio.ime.engine.HandwritingRecognizer() }
    private var handwritingJob: Job? = null
    private var handwritingReady = false
    // 連續手寫：目前這個字的辨識結果；自動送出後保留為「替換用候選」，直到開始寫下一個字並辨識出新結果
    private var hwResults: List<String> = emptyList()
    private var hwLastCommit: String? = null
    // 辨識結果是否對應目前板上的筆跡（寫了新的一筆就失效）、上一筆的停筆時間
    private var hwResultsCurrent = false
    private var hwLastStrokeEnd = 0L
    // 手寫送出依寫的順序排隊（前一個字還在辨識時，後一個字不能先送出）
    private val hwCommitLock = kotlinx.coroutines.sync.Mutex()
    private var panelExpanded: View? = null
    private var isCandidateExpanded = false
    private var ivExpandChevron: ImageView? = null
    private var layoutExpandedHeader: View? = null

    private enum class ExpandedType {
        NONE, CANDIDATES, EMOJI, KAOMOJI, PLUGINS
    }
    private var currentExpandedType = ExpandedType.NONE
    private val zhuyinKeyViews = mutableMapOf<Char, View>()
    // 候選列是否穿插 emoji／顏文字；關閉時只在展開面板以分頁出現
    private var inlineSymbols = true

    private var currentMode = KeyboardMode.ZHUYIN
    private var previousMode = KeyboardMode.ZHUYIN
    private var shiftState = ShiftState.OFF
    private var lastShiftClickTime = 0L

    private val englishKeyViews = mutableListOf<TextView>()
    private var ivEnShift: ImageView? = null

    private val englishWordBuffer = StringBuilder()
    // 上一個打完的英文字（同一句話內），用來學英文搭配與顯示接續詞
    private var lastEnglishWord: String? = null

    // 目前的中文輸入法（大千用注音鍵盤，其餘用 QWERTY 鍵盤＋字母解碼）
    private var chineseMethod = com.p3dro.scorpio.ime.engine.ChineseMethod.DACHEN
    // 依版面（id＋鍵位表）各留一個解碼器，切換時不必重建音節表
    private val letterDecoders = HashMap<String, com.p3dro.scorpio.ime.engine.LetterDecoder>()
    private val isChineseQwerty: Boolean get() = currentMode == KeyboardMode.ZHUYIN && chineseMethod.usesQwerty
    // 倉頡／速成打到一半的碼
    private val codeBuffer = StringBuilder()
    private var codeCandidateJob: Job? = null
    // 與 englishWordBuffer 逐字對應的觸控落點（供英文補全鄰鍵容錯）
    private val englishTouches = mutableListOf<BopomofoComposer.TouchPoint>()
    // 倉頡／速成每一碼的落點（QWERTY 落點學習用）
    private val codeTouches = mutableListOf<BopomofoComposer.TouchPoint>()
    private var isVibrationEnabled = false
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var keyboardRootView: View? = null

    /** 最近一根手指按下的位置（鍵盤最外層的座標）：ㄦ 這類自己處理觸控的鍵用來算落點 */
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var keyPreview: com.p3dro.scorpio.ime.ui.KeyPreview? = null
    private var lastCommittedWord: String? = null

    /**
     * 畫面上游標前已經有的字的最後一個詞：還沒有「上一個送出的詞」時（剛點進輸入框、移動過游標、刪過字）當選字的上文。
     * 每次開始打新的詞時讀一次（見 captureScreenContext）；只拿來排序，不拿來學習
     */
    private var screenContextWord: String? = null
    private var lastCommittedReading: String? = null
    private var lastCommitTime: Long = 0L
    /** 連續打字時 lastCommittedWord 的前一個詞（5 秒內接著打的），學「前兩個詞」的搭配用 */
    private var prevCommittedWord: String? = null

    /** 上一次整個送出的中文詞（落點、注音、上文）：整個刪掉改打別的詞時拿來學 */
    private class FullCommit(val word: String, val syllables: List<String>, val touches: List<BopomofoComposer.TouchPoint>, val prev: String?, val at: Long)
    private var retypeCommit: FullCommit? = null
    /** 送出 retypeCommit 之後連續刪掉的字數 */
    private var retypeDeleted = 0

    // 累加本次輸入會話中連續部分選字的音節與文字（如先選「佩」再選「卓」，智慧串聯為「佩卓」自動學習）
    private val sessionComposingReadings = mutableListOf<String>()
    private val sessionCommittedWords = mutableListOf<String>()

    companion object {
        // 外觀選項（設定「介面外觀」）
        const val PREF_KEY_BORDER = "key_border"
        const val PREF_KEY_BG = "key_background"
        const val PREF_ROUNDED = "keyboard_rounded"
        const val PREF_KEY_RIPPLE = "key_ripple"
        const val PREF_NUMBER_ROW = "number_row"
        const val PREF_RECENT_BAR = "recent_bar"
        const val PREF_PHOTO_ALPHA = "photo_alpha"
        const val PREF_RECENT_LIST = "recent_symbols"
        // 握法（設定「選字引擎」）：auto／two／one；自動判斷看不出來時當成 two／one／none
        const val PREF_POSTURE_MODE = "posture_mode"
        const val PREF_POSTURE_FALLBACK = "posture_fallback"

        fun postureOf(v: String?) = when (v) {
            "two" -> com.p3dro.scorpio.ime.engine.PostureTracker.TWO_THUMBS
            "one" -> com.p3dro.scorpio.ime.engine.PostureTracker.ONE_HAND
            else -> com.p3dro.scorpio.ime.engine.PostureTracker.UNKNOWN
        }
        private const val RECENT_SEP = "\u001F"
        // 最近用過的 Emoji、顏文字最多記幾個
        private const val MAX_RECENT_SYMBOLS = 10
        // 開著最近使用列時，工具列先放幾個工具（多的收進「更多」）
        private const val MAX_TOOLS_WITH_RECENT = 2

        /** 展開候選格的字級（sp），不因詞長縮小 */
        const val EXPANDED_TEXT_SP = 20f

        /** 打很長時展開面板一組最多幾個字 */
        const val MAX_SEGMENT_CHARS = 4

        /** 刪掉後多久內重打才算更正 */
        const val RETYPE_WINDOW_MS = 4000L
        /** 鍵盤上方的透明延伸高度：最上排按鍵的預覽泡泡畫得下 */

        // 手寫：停筆多久開始辨識、辨識完再停多久自動送出（同一個字的筆畫間隔通常遠短於此）
        private const val HW_RECOGNIZE_DELAY_MS = 300L
        private const val HW_AUTO_COMMIT_DELAY_MS = 450L
        // 落筆前停了這麼久、且上一個字已辨識完：視為下一個字
        private const val HW_NEW_CHAR_PAUSE_MS = 380L
    }

    override fun onCreate() {
        // 保險：服務本身也套經典主題，萬一有畫面沒用主題 context 建立，顏色仍解得出來（不會變洋紅色）
        setTheme(R.style.ImeTheme_Classic)
        super.onCreate()
        composer = BopomofoComposer()
        dictManager = ScorpioDictManager.getInstance(this)
        prefs = getSharedPreferences("scorpio_ime_prefs", Context.MODE_PRIVATE)
        // 詞庫線上更新：每天在 Wi-Fi 下檢查一次（WorkManager 會自己去重）
        com.p3dro.scorpio.ime.engine.PackUpdater.schedule(this)
        // App 更新：每天在背景問一次 GitHub 有沒有新版（設定可關）
        com.p3dro.scorpio.ime.engine.AppUpdater.schedule(this)
        isVibrationEnabled = prefs.getBoolean("vibrate_on_keypress", false)
        ui = buildThemedContext()
    }

    // ---------- 主題 ----------

    /** 鍵盤用的主題 context：所有鍵盤畫面都用它建立，顏色經 ?attr 取自所選主題 */
    private lateinit var ui: Context
    private var builtThemeSignature = ""

    private fun themeId() = prefs.getString("keyboard_theme", "dynamic") ?: "dynamic"
    private fun photoFile() = java.io.File(filesDir, "keyboard_bg.jpg")
    private fun keyHeightScale() = prefs.getFloat("key_height_scale", 1.0f).coerceIn(0.8f, 1.3f)

    /** 會影響鍵盤畫面的設定；變了就重建鍵盤 */
    private fun themeSignature() = listOf(
        themeId(), prefs.getInt("photo_dim", 35), keyHeightScale(), photoFile().lastModified(),
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        prefs.getBoolean(PREF_KEY_BORDER, false), prefs.getBoolean(PREF_KEY_BG, true), prefs.getBoolean(PREF_ROUNDED, true),
        prefs.getBoolean(PREF_NUMBER_ROW, false), prefs.getInt(PREF_PHOTO_ALPHA, 100), prefs.getBoolean(PREF_KEY_RIPPLE, false)
    ).joinToString("|")

    private fun buildThemedContext(): Context {
        val theme = themeId()
        // 淺色／深色固定：用覆寫 uiMode 的 context，經典配色取 values 或 values-night
        val base: Context = when (theme) {
            "light", "dark" -> {
                val cfg = Configuration(resources.configuration)
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (theme == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                createConfigurationContext(cfg)
            }
            else -> this
        }
        val style = when (theme) {
            "dynamic" -> R.style.ImeTheme_Dynamic
            "photo" -> R.style.ImeTheme_Photo
            else -> R.style.ImeTheme_Classic
        }
        // 外觀選項疊在主題上：按鍵邊框、按鍵底色、鍵盤上方圓角、按鍵漣漪
        return android.view.ContextThemeWrapper(base, style).apply {
            if (prefs.getBoolean(PREF_KEY_BORDER, false)) getTheme().applyStyle(R.style.ImeOverlay_KeyBorder, true)
            if (!prefs.getBoolean(PREF_KEY_BG, true)) getTheme().applyStyle(R.style.ImeOverlay_NoKeyBg, true)
            if (!prefs.getBoolean(PREF_ROUNDED, true)) getTheme().applyStyle(R.style.ImeOverlay_SquareTop, true)
            if (prefs.getBoolean(PREF_KEY_RIPPLE, false)) getTheme().applyStyle(R.style.ImeOverlay_Ripple, true)
        }
    }

    /** 按鍵底圖（主題屬性 imeKey*Drawable）：預設按下瞬間變色，開了「按鍵漣漪效果」是漣漪版 */
    private fun keyDrawable(attr: Int) = ui.getDrawable(android.util.TypedValue().also { ui.theme.resolveAttribute(attr, it, true) }.resourceId)

    /**
     * 按鍵高度：把所有 44dp 的按鍵排依比例縮放（觸控定位以實際按鍵大小換算，不受影響）。
     * 版面檔的 44dp 換成像素是四捨五入（450dpi 是 124），dpToPx 是無條件捨去（123），差一像素內都算
     */
    private fun scaleKeyRows(v: View, scale: Float) {
        val lp = v.layoutParams
        if (lp != null && lp.height > 0 && kotlin.math.abs(lp.height - 44f * resources.displayMetrics.density) <= 1f) {
            lp.height = keyRowHeightPx()
            v.layoutParams = lp
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scaleKeyRows(v.getChildAt(i), scale)
    }

    /**
     * 一排按鍵的高度（像素）：44dp × 按鍵高度設定，跟版面檔一樣四捨五入。
     * 程式建立的面板（符號、數字、手寫）都用這個，才會跟注音／英文鍵盤一樣高、排在一樣的位置
     */
    private fun keyRowHeightPx() = (44f * keyHeightScale() * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreateInputView(): View {
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ui = buildThemedContext()
        builtThemeSignature = themeSignature()
        updateWindowAppearance()

        val root = LayoutInflater.from(ui).inflate(R.layout.keyboard_view, null)
        keyboardRootView = root
        deleteConfirmBar = null
        // 候選列在鍵盤最上面：依鍵盤的圓角裁切，候選字左右滑、按下的底色才不會畫到圓角外。
        // 只裁這一條（幾個字），不必整個鍵盤每一幀都經過圓角裁切
        val radius = android.util.TypedValue().also { ui.theme.resolveAttribute(R.attr.imeKeyboardRadius, it, true) }
            .getDimension(resources.displayMetrics)
        if (radius > 0f) root.findViewById<View>(R.id.candidate_bar_container)?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                // 往下多延伸一個半徑：只有上面兩個角是圓的
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
            clipToOutline = true
        }
        val scale = keyHeightScale()
        if (scale != 1.0f) {
            scaleKeyRows(root, scale)
            root.findViewById<View>(R.id.panel_expanded)?.let { it.layoutParams = it.layoutParams.apply { height = dpToPx(246f * scale) } }
        }

        // 照片主題：照片鋪在鍵盤後面（置中裁切＋調暗），按鍵半透明
        // 左上、右上圓角：只有開了漣漪效果才把整個鍵盤依圓角裁切（漣漪會畫到圓角外）；
        // 每一幀整個鍵盤都要經過圓角裁切很花 GPU 準備時間，沒必要就不裁（其他東西都不會畫到圓角上，照片另外自己裁）
        val keyboardFrame = FrameLayout(ui).apply {
            background = ui.getDrawable(R.drawable.bg_keyboard_root)
            clipToOutline = prefs.getBoolean(PREF_KEY_RIPPLE, false)
            // 鍵盤上的東西一律不拿焦點：終端機這類輸入框會把送出的字轉成實體按鍵事件，系統因此離開觸控模式，
            // 原本能拿焦點的按鍵、工具列按鈕、候選列就會畫出焦點底色（看起來像整條反白）
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val photo = photoFile()
        if (themeId() == "photo" && photo.exists()) {
            val bmp = try {
                android.graphics.BitmapFactory.decodeFile(photo.absolutePath)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                // 照片自己一層、自己依圓角裁切（只裁照片這兩個東西，不必整個鍵盤每一幀都裁）
                val photoLayer = FrameLayout(ui).apply {
                    background = ui.getDrawable(R.drawable.bg_keyboard_root)
                    clipToOutline = true
                }
                keyboardFrame.addView(photoLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // 照片底下墊一層黑：照片調透明時透出來的是黑底，不是後面的 App
                photoLayer.addView(View(ui).apply { setBackgroundColor(android.graphics.Color.BLACK) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                photoLayer.addView(ImageView(ui).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val dim = prefs.getInt("photo_dim", 35).coerceIn(0, 85)
                    setColorFilter(android.graphics.Color.argb(dim * 255 / 100, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
                    alpha = prefs.getInt(PREF_PHOTO_ALPHA, 100).coerceIn(10, 100) / 100f
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        keyboardFrame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 按鍵預覽泡泡是獨立浮動視窗，可直接超出鍵盤頂端，鍵盤視窗不必再留透明延伸區
        val container = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyboardFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        keyPreview = com.p3dro.scorpio.ime.ui.KeyPreview(container).apply {
            enabled = prefs.getBoolean("key_preview", true)
        }

        rvCandidates = root.findViewById(R.id.rv_candidates)
        layoutIdleBar = root.findViewById(R.id.layout_idle_bar)
        scrollInlineSuggestions = root.findViewById(R.id.scroll_inline_suggestions)
        layoutInlineSuggestions = root.findViewById(R.id.layout_inline_suggestions)
        scrollRecent = root.findViewById(R.id.scroll_recent)
        showInlineSuggestions(inlineSuggestionViews)
        layoutCandidateActive = root.findViewById(R.id.layout_candidate_active)

        panelZhuyin = root.findViewById(R.id.panel_zhuyin)
        panelEnglish = root.findViewById(R.id.panel_english)
        panelSymbols = root.findViewById(R.id.panel_symbols)
        panelHandwriting = root.findViewById(R.id.panel_handwriting)
        panelNumpad = root.findViewById(R.id.panel_numpad)
        panelExpanded = root.findViewById(R.id.panel_expanded)
        setupNumberRow(root)
        refreshRecentBar()

        candidateAdapter = CandidateAdapter { candidate ->
            onCandidateSelected(candidate)
        }.apply {
            onLongSelect = { candidate ->
                showCandidateDeleteConfirmation(candidate)
                true
            }
        }
        rvCandidates?.apply {
            layoutManager = LinearLayoutManager(this@ScorpioImeService, LinearLayoutManager.HORIZONTAL, false)
            adapter = candidateAdapter
            // 每按一鍵候選列就整個換一次，淡入、移動動畫只會多畫很多幀
            itemAnimator = null
            // 候選列的大小跟內容無關：換候選時只重排列表本身，不讓整個鍵盤重新量版面
            setHasFixedSize(true)
            // 一排看得到十幾格：回收池預設只留 5 格，多的每次都要重新 inflate
            recycledViewPool.setMaxRecycledViews(0, 24)
        }
        root.findViewById<RecyclerView>(R.id.rv_expanded_grid)?.itemAnimator = null

        ivExpandChevron = root.findViewById(R.id.iv_expand_chevron)
        layoutExpandedHeader = root.findViewById(R.id.layout_expanded_header)

        refreshToolbar(root)

        // 下拉展開大候選區 ⌵ 點擊：展開或折疊
        root.findViewById<View>(R.id.btn_expand_candidates).setOnClickListener {
            triggerHaptic(it)
            if (isCandidateExpanded) {
                collapseCandidates()
            } else {
                expandCandidates(root)
            }
        }

        // 注音鍵盤的四排照版面宣告排（setKeyboardMode 時依目前的輸入法換：內建 10×4，或宣告的版面，例如三星的鍵位）
        renderedZhuyinLayout = null

        val gridZhuyin = root.findViewById<com.p3dro.scorpio.ime.ui.ZhuyinGridLayout>(R.id.grid_zhuyin)
        gridZhuyin.rebuildKeyGrid()
        // 按鍵大小可調：空間模型改用實際量到的鍵距換算落點
        gridZhuyin.onPitchMeasured = { rx, ry -> dictManager.spatial.setPitch(rx, ry) }
        gridZhuyin.keyPreview = keyPreview
        gridZhuyin.onKeyHapticListener = { view ->
            triggerHaptic(view)
        }
        gridZhuyin.onKeyTapListener = { char, offsetX, offsetY ->
            onZhuyinKeyPressed(char, offsetX, offsetY)
        }
        // 握法判斷看鍵盤上的每一下（注音、聲調、ㄦ、空白、刪除、英文、符號鍵…），候選列與展開面板不算
        val keyPanels = listOf(R.id.panel_zhuyin, R.id.panel_english, R.id.panel_symbols, R.id.panel_numpad).mapNotNull { root.findViewById<View>(it) }
        (root as com.p3dro.scorpio.ime.ui.TapObserverLayout).onTapDown = { x, y, time ->
            lastDownX = x
            lastDownY = y
            val panel = keyPanels.firstOrNull { it.visibility == View.VISIBLE && y >= it.top && y < it.bottom }
            val w = panel?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
            if (panel != null && w > 0) dictManager.noteKeyboardTap((x - panel.left - panel.paddingLeft).toDouble() / w * 10 - 0.5, time)
        }
        dictManager.postureFromKeyboard = true
        gridZhuyin.onKeyLongPressListener = { char ->
            if (composer.isComposing) {
                clearComposer(discardComposingText = true)
            }
            currentInputConnection?.commitText(char.toString(), 1)
            updateBarVisibility()
        }

        setupZhuyinFunctionKeys(root)

        // 建置英文鍵盤
        setupEnglishKeyboard(root)

        // 建置符號鍵盤
        // 注音與英文鍵盤的最下排照同一組鍵位排（見 KeyRowLayout），切換時共有的鍵不移位
        root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(R.id.row_5).segments = com.p3dro.scorpio.ime.ui.KeyRowLayout.ZHUYIN
        root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(R.id.en_row_4).segments = com.p3dro.scorpio.ime.ui.KeyRowLayout.QWERTY
        setupSymbolsKeyboard(root)

        setKeyboardMode(KeyboardMode.ZHUYIN)
        updateBarVisibility()
        return container
    }

    private val insetsLoc = IntArray(2)

    /** 透明延伸區不推擠 App 版面、觸控穿透給 App；只有鍵盤本體算輸入法區域 */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val kb = keyboardRootView ?: return
        if (isFullscreenMode || !kb.isShown) return
        kb.getLocationInWindow(insetsLoc)
        val top = insetsLoc[1]
        val decor = window?.window?.decorView ?: return
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, decor.width, decor.height)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 主題、照片、按鍵大小在設定裡改過：重建鍵盤
        if (keyboardRootView != null && themeSignature() != builtThemeSignature) setInputView(onCreateInputView())
        updateWindowAppearance()
        isVibrationEnabled = prefs.getBoolean("vibrate_on_keypress", false)
        keyPreview?.enabled = prefs.getBoolean("key_preview", true)
        inlineSymbols = prefs.getBoolean("inline_emoji_kaomoji", true)
        dictManager.postureMode = postureOf(prefs.getString(PREF_POSTURE_MODE, "auto"))
        dictManager.postureFallback = postureOf(prefs.getString(PREF_POSTURE_FALLBACK, "two"))
        lastCommittedWord = null
        screenContextWord = null
        lastEnglishWord = null
        clearComposer()
        val methods = com.p3dro.scorpio.ime.engine.ChineseMethod.enabled(this, prefs)
        val savedId = prefs.getString(com.p3dro.scorpio.ime.engine.ChineseMethod.PREF_CURRENT, null)
        val method = methods.firstOrNull { it.id == savedId } ?: methods.first()
        if (method != chineseMethod || composer.decoder?.kind != method.decoderKind) applyChineseMethod(method)
        finishEnglishWord(learn = false)
        // 依輸入框類型開對的鍵盤：數字、電話、日期時間開數字鍵盤；密碼、Email、網址開英文鍵盤；其他照常開中文
        fieldKind = fieldKindOf(info)
        numpadFromUser = false
        // 換輸入框：Shift 回到小寫（上一個輸入框按了 Shift 沒打字、或開著大寫鎖定，不帶過來）
        resetShift()
        setKeyboardMode(
            when (fieldKind) {
                FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATETIME -> KeyboardMode.NUMPAD
                FieldKind.PASSWORD, FieldKind.EMAIL, FieldKind.URI -> KeyboardMode.ENGLISH
                FieldKind.TEXT -> KeyboardMode.ZHUYIN
            }
        )
        toolsExpanded = false
        resumePendingUpdate()
        keyboardRootView?.let { refreshToolbar(it) }
        refreshRecentBar()
        updateBarVisibility()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        finishEnglishWord(learn = false)
        keyPreview?.hideAll()
        // 工具列直接露出過有紅點的「更新」：鍵盤收起就算看過了
        updateDotShownTag?.let { com.p3dro.scorpio.ime.engine.AppUpdater.markSeen(this, it) }
        updateDotShownTag = null
        // 離開這個輸入框：上一個輸入框的自動填入建議不能留著（新的輸入框會再送一次）
        showInlineSuggestions(emptyList())
    }

    // ---------- 自動填入建議（Android 11 以上的 Inline Suggestions） ----------

    /**
     * 跟系統說鍵盤可以顯示自動填入建議、要什麼樣式。建議由自動填入服務（例如 Google 自動填入的「簡訊驗證碼」）產生並畫好，
     * 鍵盤拿到的是已經畫好的小視窗，看不到簡訊內容，也不需要任何權限。
     * 膠囊在自動填入服務的行程裡畫，底色只能用一般顏色資源（日夜各一），字色用目前主題的顏色
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest {
        val d = resources.displayMetrics.density
        val style = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                androidx.autofill.inline.common.ViewStyle.Builder()
                    .setBackground(android.graphics.drawable.Icon.createWithResource(this, R.drawable.bg_inline_chip))
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                androidx.autofill.inline.common.ViewStyle.Builder()
                    .setBackground(android.graphics.drawable.Icon.createWithResource(this, R.drawable.bg_inline_chip))
                    .setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                    .build()
            )
            .setTitleStyle(
                androidx.autofill.inline.common.TextViewStyle.Builder()
                    .setTextColor(ui.getColor(R.color.ime_text_primary))
                    .setTextSize(15f)
                    .build()
            )
            .setSubtitleStyle(
                androidx.autofill.inline.common.TextViewStyle.Builder()
                    .setTextColor(ui.getColor(R.color.ime_text_secondary))
                    .setTextSize(12f)
                    .build()
            )
            .build()
        val styles = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(
            android.util.Size((48 * d).toInt(), (34 * d).toInt()),
            android.util.Size((260 * d).toInt(), (34 * d).toInt())
        ).setStyle(styles).build()
        return android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(6)
            .build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            showInlineSuggestions(emptyList())
            return true
        }
        // 每個建議各自非同步畫好；全部回來後依原本順序放，釘選的（通常是開自動填入設定的圖示）放最後
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        val size = android.util.Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { i, s ->
            s.inflate(this, size, mainExecutor) { v ->
                // 建議是嵌進來的小窗（SurfaceView）：預設疊在鍵盤畫面下面、被鍵盤底色蓋住，要疊到上面才看得到
                v?.setZOrderedOnTop(true)
                views[i] = v
                if (--pending == 0) {
                    val order = suggestions.indices.sortedBy { if (suggestions[it].info.isPinned) 1 else 0 }
                    val shown = order.mapNotNull { views[it] }
                    android.util.Log.i("ScorpioIme", "自動填入建議 ${suggestions.size} 個，畫出 ${shown.size} 個")
                    showInlineSuggestions(shown)
                }
            }
        }
        return true
    }

    /** 有自動填入建議時放在工具列中間（取代最近使用列），沒有就收起來 */
    private fun showInlineSuggestions(views: List<View>) {
        inlineSuggestionViews = views
        val box = layoutInlineSuggestions ?: return
        box.removeAllViews()
        val d = resources.displayMetrics.density
        for (v in views) {
            (v.parent as? ViewGroup)?.removeView(v)
            // 系統畫好建議後會把實際大小設成 layoutParams（這種嵌入的小窗自己不會量寬度，用「自動」放在可滑動的列裡會變成 0 寬）
            val lp = v.layoutParams
            box.addView(v, LinearLayout.LayoutParams(lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, lp?.height ?: (34 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            })
        }
        arrangeIdleBar()
    }

    /** 使用者自己移動游標（點到別處）：英文暫存的字就地確定，不跟著游標跑 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 只看「有暫存區但游標不在它尾端」；暫存區不見（-1）多半是我們自己剛送出的字晚到的通知，打字快時不能誤判
        if (englishWordBuffer.isNotEmpty() && candidatesStart != -1 && (newSelStart != newSelEnd || newSelEnd != candidatesEnd)) {
            finishEnglishWord(learn = false)
            endEnglishPhrase()
            updateBarVisibility()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW(10)／RUNNING_CRITICAL(15)：前景但系統缺記憶體；UI_HIDDEN(20)：鍵盤收起；BACKGROUND(40) 以上：在背景、快被殺了
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) dictManager.trimMemory(level)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyPreview?.hideAll()
        dismissDeleteConfirmation()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        updateWindowAppearance()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (lastNightMode != currentNightMode) {
            lastNightMode = currentNightMode
            updateWindowAppearance()
            setInputView(onCreateInputView())
        }
    }

    private fun updateWindowAppearance() {
        window?.window?.let { win ->
            val isDark = when (themeId()) {
                "dark", "photo" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val bgColor = if (themeId() == "photo") android.graphics.Color.BLACK else ui.getColor(R.color.ime_bg)
            // 視窗本身透明，鍵盤圓角外才看得到底下的 App（底色改由鍵盤根佈局的圓角背景負責）
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.navigationBarColor = bgColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                win.insetsController?.setSystemBarsAppearance(
                    if (isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                var flags = win.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = if (isDark) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = flags
            }
            Unit
        }
    }

    private fun triggerHaptic(view: View) {
        if (isVibrationEnabled) {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    private fun updateBarVisibility() {
        val hasPredictions = (candidateAdapter?.currentList?.isNotEmpty() == true)
        val hasContent = composer.isComposing || englishWordBuffer.isNotEmpty() || codeBuffer.isNotEmpty() || hasPredictions
        if (hasContent) {
            layoutIdleBar?.visibility = View.GONE
            layoutCandidateActive?.visibility = View.VISIBLE
        } else {
            layoutCandidateActive?.visibility = View.GONE
            layoutIdleBar?.visibility = View.VISIBLE
        }
    }

    private fun setKeyboardMode(mode: KeyboardMode) {
        // 從符號頁打開的數字鍵盤也不算「原本的鍵盤」：返回時回到注音／英文
        if (currentMode != KeyboardMode.SYMBOLS && currentMode != KeyboardMode.EXPANDED && currentMode != KeyboardMode.HANDWRITING &&
            !(currentMode == KeyboardMode.NUMPAD && numpadFromUser)
        ) {
            previousMode = currentMode
        }
        val from = currentMode
        currentMode = mode
        keyPreview?.hideAll()
        // 跟 Gboard 一樣：切到別的鍵盤再回來，Shift（含大寫鎖定）回到小寫；展開候選字再收回不算切換
        if (mode != from && from != KeyboardMode.EXPANDED) resetShift()
        // 每次進符號鍵盤都回第 1 頁，並依來源（注音／英文）重建全形或半形標點
        if (mode == KeyboardMode.SYMBOLS) {
            symbolsPage = 0
            buildSymbolsPanel()
        }

        if (mode != KeyboardMode.EXPANDED) {
            isCandidateExpanded = false
            currentExpandedType = ExpandedType.NONE
            ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_down)
        }

        // 展開面板（候選字、Emoji、顏文字、擴充功能）跟它蓋掉的鍵盤一樣高：版面檔的 246dp 是 5 排的設計值，
        // 開了數字列或調過按鍵高度時跟鍵盤實際高度不同，展開時整個鍵盤會縮或長
        if (mode == KeyboardMode.EXPANDED) {
            val shown = listOf(panelZhuyin, panelEnglish, panelSymbols, panelNumpad, panelHandwriting)
                .firstOrNull { it != null && it.visibility == View.VISIBLE && it.height > 0 }
            if (shown != null) panelExpanded?.let { it.layoutParams = it.layoutParams.apply { height = shown.height } }
        }

        val qwertyChinese = mode == KeyboardMode.ZHUYIN && chineseMethod.usesQwerty
        if (mode == KeyboardMode.ZHUYIN && !qwertyChinese) applyZhuyinLayout(chineseMethod.zhuyinLayout)
        panelZhuyin?.visibility = if (mode == KeyboardMode.ZHUYIN && !qwertyChinese) View.VISIBLE else View.GONE
        panelEnglish?.visibility = if (mode == KeyboardMode.ENGLISH || qwertyChinese) View.VISIBLE else View.GONE
        if (mode == KeyboardMode.ENGLISH || qwertyChinese) applyQwertyLayout(currentQwertyLayout())
        if (mode == KeyboardMode.ENGLISH || mode == KeyboardMode.ZHUYIN) applyQwertyLabels()
        panelSymbols?.visibility = if (mode == KeyboardMode.SYMBOLS) View.VISIBLE else View.GONE
        if (mode == KeyboardMode.NUMPAD) buildNumpad()
        panelNumpad?.visibility = if (mode == KeyboardMode.NUMPAD) View.VISIBLE else View.GONE
        if (mode == KeyboardMode.HANDWRITING) buildHandwritingPanel()
        panelHandwriting?.visibility = if (mode == KeyboardMode.HANDWRITING) View.VISIBLE else View.GONE
        if (mode != KeyboardMode.HANDWRITING) {
            handwritingJob?.cancel()
            handwritingView?.clear()
            hwResults = emptyList()
            hwLastCommit = null
        }
        panelExpanded?.visibility = if (mode == KeyboardMode.EXPANDED) View.VISIBLE else View.GONE

        if (mode != KeyboardMode.ZHUYIN && mode != KeyboardMode.EXPANDED) {
            clearComposer()
        }
        if (mode != KeyboardMode.ZHUYIN && mode != KeyboardMode.EXPANDED && codeBuffer.isNotEmpty()) clearCodeBuffer()
        if (mode != KeyboardMode.ENGLISH && englishWordBuffer.isNotEmpty()) {
            finishEnglishWord()
            lastEnglishWord = null
        }
        updateBarVisibility()
        arrangeIdleBar()
    }

    /** 大千鍵盤目前畫的版面（null 是還沒畫） */
    private var renderedZhuyinLayout: com.p3dro.scorpio.ime.engine.KeyboardLayout? = null

    /** 大千鍵盤的最下排是英文那種排法（宣告的版面把 ㄦ、刪除鍵放在字鍵區）：空白鍵右邊是句號，符號鍵盤也照英文的排 */
    private val zhuyinBottomLikeQwerty: Boolean get() = renderedZhuyinLayout.let { it != null && it != com.p3dro.scorpio.ime.engine.KeyboardLayout.DACHEN }

    /**
     * 依版面宣告畫大千鍵盤的四排：內建 10×4 照原本的權重平分（像素跟原本一樣），ㄦ、刪除鍵在最下排；
     * 宣告的版面（三星的鍵位：第 1 排 11 鍵含 ㄦ、第 2、3 排錯開半鍵、刪除鍵在第 4 排最右邊）每個鍵照欄位精確排，
     * 刪除鍵搬到宣告的位置，最下排改成跟英文一樣（ㄦ 那一鍵改成句號）。
     * 落點模型（dictManager.spatial）同時換成這個版面的鍵位與那一組的習慣
     */
    private fun applyZhuyinLayout(layout: com.p3dro.scorpio.ime.engine.KeyboardLayout) {
        dictManager.useZhuyinLayout(layout)
        if (renderedZhuyinLayout == layout) return
        val root = keyboardRootView ?: return
        renderedZhuyinLayout = layout
        lastZhuyinTap = null
        val builtIn = layout == com.p3dro.scorpio.ime.engine.KeyboardLayout.DACHEN
        val backspace = root.findViewById<View>(R.id.btn_backspace) ?: return
        val bottom = root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(R.id.row_5) ?: return
        (backspace.parent as? ViewGroup)?.removeView(backspace)
        zhuyinKeyViews.clear()
        val rowIds = listOf(R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4)
        for ((r, id) in rowIds.withIndex()) {
            val row = root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(id)
            val keys = layout.rows.getOrNull(r).orEmpty()
            setupRow(row, keys.mapNotNull { it.char })
            if (builtIn) {
                row.clearColumnSpans()
                continue
            }
            val at = keys.indexOfFirst { it.type == com.p3dro.scorpio.ime.engine.KeyboardLayout.Type.BACKSPACE }
            if (at >= 0) row.addView(backspace, at)
            row.setColumnSpans(layout.columns, keys.map { it.start to it.width })
        }
        // 內建版面：刪除鍵回到最下排 ㄦ 的右邊
        if (builtIn) bottom.addView(backspace, bottom.indexOfChild(root.findViewById(R.id.btn_er)) + 1)
        bottom.segments = if (builtIn) com.p3dro.scorpio.ime.ui.KeyRowLayout.ZHUYIN else com.p3dro.scorpio.ime.ui.KeyRowLayout.QWERTY
        bindErKey(root, asPeriod = !builtIn)
        root.findViewById<com.p3dro.scorpio.ime.ui.ZhuyinGridLayout>(R.id.grid_zhuyin)?.rebuildKeyGrid()
    }

    /** 最下排 ㄦ 那一鍵：內建版面是 ㄦ（點一下是注音，長按直接輸入「ㄦ」這個符號）；ㄦ 在字鍵區的版面改成句號 */
    private fun bindErKey(root: View, asPeriod: Boolean) {
        val er = root.findViewById<ViewGroup>(R.id.btn_er) ?: return
        (er.getChildAt(0) as? TextView)?.text = if (asPeriod) "。" else "ㄦ"
        if (asPeriod) {
            er.setOnTouchListener(null)
            er.setOnClickListener {
                triggerHaptic(it)
                commitChinesePunctuation("。")
            }
            return
        }
        er.setOnTouchListener(longPressKeyTouchListener("ㄦ", onTap = {
            val (ox, oy) = erTouchOffset(er, root.findViewById(R.id.grid_zhuyin))
            onZhuyinKeyPressed('ㄦ', ox, oy)
        }, onLong = {
            if (composer.isComposing) {
                clearComposer(discardComposingText = true)
            }
            currentInputConnection?.commitText("ㄦ", 1)
            updateBarVisibility()
        }))
    }

    private fun setupRow(rowLayout: LinearLayout, keys: List<Char>) {
        rowLayout.removeAllViews()
        for (zhuyin in keys) {
            val keyView = ZhuyinKeyView(ui).apply {
                // 不顯示實體鍵盤對應的英文字母與數字（這個鍵盤打不出來，只會干擾）
                setKey(zhuyin, "")
                // 恢復原版 2.5dp 完美間距與按鍵尺寸，全域觸控由父層 ZhuyinGridLayout 空間座標定位模式接管
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
                }
            }
            zhuyinKeyViews[zhuyin] = keyView
            rowLayout.addView(keyView)
        }
    }

    /**
     * 有長按功能的單一按鍵：長按成立時預覽泡泡變成選中色，手指放開才執行；
     * 長按成立後滑出按鍵範圍則取消，放開什麼都不送
     */
    @SuppressLint("ClickableViewAccessibility")
    /** longLabel：長按成立後泡泡改顯示的字（例如逗號鍵長按顯示「。」）；沒給就沿用 label */
    private fun longPressKeyTouchListener(label: String, onTap: () -> Unit, onLong: () -> Unit, longLabel: String? = null): View.OnTouchListener {
        var downTime = 0L
        var longArmed = false
        var cancelled = false
        var armRunnable: Runnable? = null
        val slop = dpToPx(12)
        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downTime = System.currentTimeMillis()
                    longArmed = false
                    cancelled = false
                    triggerHaptic(v)
                    keyPreview?.hideAll()
                    keyPreview?.show(v, v, label)
                    armRunnable = Runnable {
                        longArmed = true
                        triggerHaptic(v)
                        keyPreview?.select(v, longLabel)
                    }.also { v.postDelayed(it, 400L) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val outside = event.x < -slop || event.x > v.width + slop || event.y < -slop || event.y > v.height + slop
                    if (outside && !cancelled) {
                        armRunnable?.let { v.removeCallbacks(it) }
                        if (longArmed) {
                            cancelled = true
                            v.isPressed = false
                            keyPreview?.hide(v)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    armRunnable?.let { v.removeCallbacks(it) }
                    keyPreview?.hide(v)
                    if (!cancelled) {
                        if (longArmed) onLong() else onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    armRunnable?.let { v.removeCallbacks(it) }
                    keyPreview?.hide(v)
                    // 手勢中斷但按壓時間 < 350ms 仍判定輸入，杜絕吞鍵
                    if (!cancelled && !longArmed && System.currentTimeMillis() - downTime < 350L) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupZhuyinFunctionKeys(root: View) {
        root.findViewById<View>(R.id.btn_symbols_toggle).setOnClickListener {
            triggerHaptic(it)
            setKeyboardMode(KeyboardMode.SYMBOLS)
        }

        // 「，」：點一下輸入逗號，長按（放開時）輸入句號
        root.findViewById<View>(R.id.btn_comma).setOnTouchListener(longPressKeyTouchListener("，", onTap = {
            if (composer.isComposing) {
                commitTopCandidateOrRaw()
            }
            currentInputConnection?.commitText("，", 1)
            updateBarVisibility()
        }, onLong = {
            if (composer.isComposing) {
                commitTopCandidateOrRaw()
            }
            currentInputConnection?.commitText("。", 1)
            updateBarVisibility()
        }, longLabel = "。"))

        val btnGlobe = root.findViewById<View>(R.id.btn_globe)
        btnGlobe.setOnClickListener {
            triggerHaptic(it)
            switchToNextLanguage()
        }
        btnGlobe.setOnLongClickListener {
            triggerHaptic(it)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                switchInputMethod(null)
            }
            true
        }

        val btnSpace = root.findViewById<View>(R.id.btn_space)
        btnSpace.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    triggerHaptic(v)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    onZhuyinSpace()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        // ㄦ：點一下是注音，長按（放開時）直接輸入「ㄦ」這個符號（ㄦ 在字鍵區的版面改成句號，見 applyZhuyinLayout）
        bindErKey(root, asPeriod = false)

        root.findViewById<View>(R.id.btn_backspace).setOnTouchListener(RepeatTouchListener {
            triggerHaptic(root)
            handleBackspace()
        })
        // 展開面板（Emoji、顏文字、擴充功能）頁籤列右邊的刪除鍵
        root.findViewById<View>(R.id.btn_panel_backspace)?.setOnTouchListener(RepeatTouchListener {
            triggerHaptic(root)
            handleBackspace()
        })

        val btnEnter = root.findViewById<View>(R.id.btn_enter)
        btnEnter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    triggerHaptic(v)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handleEnterPress()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 刪除游標前「一個看得到的字」：emoji（含膚色、ZWJ 組合、國旗）與擴充區漢字由多個 UTF-16 單元組成，
     * 只刪 1 個單元會留下半個字而變成亂碼；有選取範圍時刪掉整段選取
     */
    private fun deleteCharBeforeCursor() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(32, 0)
        if (before.isNullOrEmpty()) {
            // 拿不到前文（部分 App 不支援）：送實體刪除鍵交給 App 處理
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            return
        }
        val text = before.toString()
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = text.length
        val start = it.preceding(end).takeIf { p -> p != android.icu.text.BreakIterator.DONE } ?: (end - 1)
        ic.deleteSurroundingText(end - start, 0)
    }

    private fun handleBackspace() {
        lastZhuyinTap = null
        val ic = currentInputConnection
        if (currentMode == KeyboardMode.ENGLISH) {
            if (englishWordBuffer.isNotEmpty()) {
                englishWordBuffer.deleteCharAt(englishWordBuffer.length - 1)
                val removed = englishTouches.removeLastOrNull()
                rememberRetype(removed)
                ic?.setComposingText(englishWordBuffer, 1)
                if (englishWordBuffer.isEmpty()) ic?.finishComposingText()
                updateEnglishCandidates()
            } else {
                endEnglishPhrase()
                deleteCharBeforeCursor()
                updateBarVisibility()
            }
        } else {
            // 手寫：板上有字先清掉筆跡，沒有才刪前一個字
            if (currentMode == KeyboardMode.HANDWRITING) {
                hwLastCommit = null
                hwResults = emptyList()
                if (handwritingView?.isEmpty == false) {
                    handwritingJob?.cancel()
                    handwritingView?.clear()
                    candidateAdapter?.submitList(emptyList())
                    updateBarVisibility()
                    return
                }
                candidateAdapter?.submitList(emptyList())
            }
            // 倉頡／速成：刪掉最後一碼
            if (codeBuffer.isNotEmpty()) {
                codeBuffer.deleteCharAt(codeBuffer.length - 1)
                rememberRetype(codeTouches.removeLastOrNull())
                updateCodeComposing()
                return
            }
            // 注音模式：若在拼音中，按一下刪除鍵刪掉最後一個注音符號
            if (composer.isComposing) {
                // 只刪一個鍵就重打：被刪掉的那一下是「想按別的鍵卻按歪」的樣本；連刪多個鍵多半是改主意，不算
                val removed = composer.getTouchPoints().lastOrNull()
                val hasRemaining = composer.backspace()
                if (hasRemaining) {
                    updateComposingAndCandidates()
                } else {
                    // 徹底清除輸入框中的暫存注音，絕不殘留成已上屏字！
                    clearComposer(discardComposingText = true)
                }
                rememberRetype(removed)
                return
            } else {
                // 若已經選好字或純文字，按一下刪除鍵刪掉前面一個字，並取消接續詞聯想
                predictionJob?.cancel()
                candidateAdapter?.submitList(emptyList())
                lastCommittedWord = null
                trackRetypeDelete()
                deleteCharBeforeCursor()
                updateBarVisibility()
            }
        }
    }

    /**
     * 英文 QWERTY 鍵盤配置
     */
    private fun setupEnglishKeyboard(root: View) {
        englishKeyViews.clear()

        // 空間座標定位：整塊英文面板由最近按鍵判定，按鍵間隙（含 a 左側、l 右側）不再吞鍵
        val spatial = root.findViewById<com.p3dro.scorpio.ime.ui.SpatialKeyLayout>(R.id.panel_english)
        spatial.clearKeys()
        spatial.onKeyHapticListener = { triggerHaptic(it) }
        spatial.keyPreview = keyPreview
        spatial.previewLabel = { c ->
            if (isChineseQwerty && !c.isDigit()) qwertyLabel(c) else (if (shiftState != ShiftState.OFF) c.uppercaseChar() else c).toString()
        }
        spatial.onKeyTapListener = { char, offsetX, offsetY ->
            when {
                char.isDigit() && isChineseQwerty -> {
                    if (composer.isComposing) commitTopCandidateOrRaw()
                    currentInputConnection?.commitText(char.toString(), 1)
                }
                char.isDigit() -> onEnglishNumberInput(char)
                isChineseQwerty -> onChineseLetterKey(char.lowercaseChar(), offsetX, offsetY)
                else -> onEnglishCharInput(char, offsetX, offsetY)
            }
        }

        // 數字列與字鍵排照版面宣告排（setKeyboardMode 時依目前的輸入法換）
        renderedQwertyLayout = null
        enShiftKey = root.findViewById(R.id.btn_en_shift)
        enBackspaceKey = root.findViewById(R.id.btn_en_backspace)
        applyQwertyLayout(currentQwertyLayout())

        ivEnShift = enShiftKey?.findViewById(R.id.iv_en_shift)
        enShiftKey?.setOnClickListener {
            triggerHaptic(it)
            if (isChineseQwerty) {
                // 拼音：這個鍵是分隔號（xi'an）；其他中文輸入法不用 Shift
                if (chineseMethod.isPinyin && composer.isComposing) {
                    composer.append('\'')
                    updateComposingAndCandidates()
                }
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            shiftState = when {
                shiftState == ShiftState.OFF -> ShiftState.ONCE
                shiftState == ShiftState.ONCE && (now - lastShiftClickTime < 400) -> ShiftState.LOCKED
                else -> ShiftState.OFF
            }
            lastShiftClickTime = now
            updateShiftVisuals()
        }

        enBackspaceKey?.setOnTouchListener(RepeatTouchListener {
            triggerHaptic(root)
            handleBackspace()
        })

        root.findViewById<View>(R.id.btn_en_symbols).setOnClickListener {
            triggerHaptic(it)
            setKeyboardMode(KeyboardMode.SYMBOLS)
        }

        root.findViewById<View>(R.id.btn_en_comma).setOnClickListener {
            triggerHaptic(it)
            if (isChineseQwerty) {
                commitChinesePunctuation("，")
                return@setOnClickListener
            }
            finishEnglishWord()
            endEnglishPhrase()
            currentInputConnection?.commitText(englishCommaKey(), 1)
            updateBarVisibility()
        }

        root.findViewById<View>(R.id.btn_en_globe).setOnClickListener {
            triggerHaptic(it)
            switchToNextLanguage()
        }

        root.findViewById<View>(R.id.btn_en_space).setOnClickListener {
            triggerHaptic(it)
            if (isChineseQwerty) {
                onZhuyinSpace()
                return@setOnClickListener
            }
            val word = englishWordBuffer.toString()
            finishEnglishWord()
            currentInputConnection?.commitText(" ", 1)
            if (word.isNotEmpty()) onEnglishWordDone(word) else endEnglishPhrase()
            updateBarVisibility()
        }

        root.findViewById<View>(R.id.btn_en_period).setOnClickListener {
            triggerHaptic(it)
            if (isChineseQwerty) {
                commitChinesePunctuation("。")
                return@setOnClickListener
            }
            finishEnglishWord()
            endEnglishPhrase()
            currentInputConnection?.commitText(".", 1)
            updateBarVisibility()
        }

        root.findViewById<View>(R.id.btn_en_enter).setOnClickListener {
            triggerHaptic(it)
            handleEnterPress()
        }

        updateShiftVisuals()
    }

    /** QWERTY 面板目前畫的版面（null 是還沒畫） */
    private var renderedQwertyLayout: com.p3dro.scorpio.ime.engine.KeyboardLayout? = null
    // Shift、刪除鍵（版面檔裡的那兩個 View）：依版面搬到宣告的位置，沒宣告 Shift 時不在畫面上，要自己記著
    private var enShiftKey: View? = null
    private var enBackspaceKey: View? = null

    /** QWERTY 面板該畫的版面：中文 QWERTY 輸入法有宣告字鍵就用它的，英文與其他用內建 QWERTY */
    private fun currentQwertyLayout(): com.p3dro.scorpio.ime.engine.KeyboardLayout =
        (if (currentMode == KeyboardMode.ENGLISH) null else chineseMethod.keys) ?: com.p3dro.scorpio.ime.engine.KeyboardLayout.QWERTY

    /**
     * 依版面宣告畫 QWERTY 面板的數字列與字鍵排（最下排固定不動）：每個鍵照欄位精確排（KeyRowLayout），
     * Shift、刪除鍵放到宣告的位置；落點模型（dictManager.qwerty）同時換成這個版面的鍵位與那一組的習慣
     */
    private fun applyQwertyLayout(layout: com.p3dro.scorpio.ime.engine.KeyboardLayout) {
        // 落點模型每次都對一下（同樣鍵位的輸入法也要記下它用哪一組，之後改版才搬得了習慣）
        dictManager.useQwertyLayout(layout, chineseMethod.id.takeIf { currentMode != KeyboardMode.ENGLISH })
        if (renderedQwertyLayout == layout) return
        val root = keyboardRootView ?: return
        val spatial = root.findViewById<com.p3dro.scorpio.ime.ui.SpatialKeyLayout>(R.id.panel_english) ?: return
        renderedQwertyLayout = layout
        englishKeyViews.clear()
        spatial.clearKeys()

        val shift = enShiftKey ?: return
        val backspace = enBackspaceKey ?: return
        (shift.parent as? ViewGroup)?.removeView(shift)
        (backspace.parent as? ViewGroup)?.removeView(backspace)

        val row0 = root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(R.id.en_row_0)
        row0.removeAllViews()
        val numbers = layout.numberRow
        row0.visibility = if (numbers == null) View.GONE else View.VISIBLE
        if (numbers != null) {
            val start = (layout.columns - numbers.size) / 2.0
            for (c in numbers) row0.addView(createEnglishNumberKey(c, 1f).also { spatial.registerKey(it, c) })
            row0.setColumnSpans(layout.columns, numbers.indices.map { (start + it) to 1.0 })
        }

        val rowIds = listOf(R.id.en_row_1, R.id.en_row_2, R.id.en_row_3, R.id.en_row_extra)
        for ((r, id) in rowIds.withIndex()) {
            val row = root.findViewById<com.p3dro.scorpio.ime.ui.KeyRowLayout>(id)
            row.removeAllViews()
            val keys = layout.rows.getOrNull(r)
            row.visibility = if (keys == null) View.GONE else View.VISIBLE
            // 沒有數字列時第一排貼齊上緣（跟注音鍵盤一樣），有數字列時跟其他排一樣隔 4dp
            if (r == 0) (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (numbers == null) 0 else dpToPx(4)
            if (keys == null) continue
            for (k in keys) row.addView(
                when (k.type) {
                    com.p3dro.scorpio.ime.engine.KeyboardLayout.Type.CHAR -> createEnglishKey(k.char!!, 1f).also { spatial.registerKey(it, k.char) }
                    com.p3dro.scorpio.ime.engine.KeyboardLayout.Type.SHIFT -> shift
                    com.p3dro.scorpio.ime.engine.KeyboardLayout.Type.BACKSPACE -> backspace
                }
            )
            row.setColumnSpans(layout.columns, keys.map { it.start to it.width })
        }

        // 功能鍵只參與範圍劃分，觸控交還按鈕本身（Shift 連按、刪除長按連刪等）；沒宣告 Shift 的版面不放 Shift
        if (shift.parent != null) spatial.registerKey(shift, null)
        spatial.registerKey(backspace, null)
        for (id in listOf(R.id.btn_en_symbols, R.id.btn_en_comma, R.id.btn_en_globe, R.id.btn_en_space, R.id.btn_en_period, R.id.btn_en_enter)) {
            spatial.registerKey(root.findViewById(id), null)
        }
        applyQwertyLabels()
        updateShiftVisuals()
    }

    private fun createEnglishNumberKey(char: Char, weight: Float): View {
        val tv = com.p3dro.scorpio.ime.ui.KeyLabelView(ui).apply {
            text = char.toString()
            textSize = 20f
            setTextColor(ui.getColor(R.color.ime_text_primary))
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }

        return FrameLayout(ui).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            }
            background = keyDrawable(R.attr.imeKeyDrawable)
            isClickable = true
            isFocusable = true
            addView(tv)
            setOnClickListener {
                triggerHaptic(it)
                onEnglishNumberInput(char)
            }
        }
    }

    private fun onEnglishNumberInput(char: Char) {
        // 先確定暫存中的字，否則 commitText 會把它換掉
        if (englishWordBuffer.isNotEmpty()) {
            finishEnglishWord(learn = false)
            endEnglishPhrase()
            updateBarVisibility()
        }
        currentInputConnection?.commitText(char.toString(), 1)
    }

    private fun createEnglishKey(char: Char, weight: Float): View {
        val tv = com.p3dro.scorpio.ime.ui.KeyLabelView(ui).apply {
            text = char.toString()
            textSize = 23f
            setTextColor(ui.getColor(R.color.ime_text_primary))
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        tv.tag = char
        englishKeyViews.add(tv)

        return FrameLayout(ui).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            }
            background = keyDrawable(R.attr.imeKeyDrawable)
            isClickable = true
            isFocusable = true
            addView(tv)
            setOnClickListener {
                triggerHaptic(it)
                onEnglishCharInput(char)
            }
        }
    }

    /** 結束目前的英文單字（空白、標點、Enter 等），打完的字寫入英文學習 */
    private fun finishEnglishWord(learn: Boolean = true) {
        if (learn && englishWordBuffer.length >= 2 && personalizationAllowed()) {
            val word = englishWordBuffer.toString()
            serviceScope.launch { dictManager.recordEnglishWord(word) }
        }
        if (learn) recordQwertyTouches(englishTouches, englishWordBuffer)
        // 暫存中的字照目前樣子確定送出
        if (englishWordBuffer.isNotEmpty()) currentInputConnection?.finishComposingText()
        englishWordBuffer.clear()
        englishTouches.clear()
    }

    private fun onEnglishCharInput(char: Char, offsetX: Float = 0f, offsetY: Float = 0f) {
        // 按下當下判定：落點（高斯）＋前面字母接上哪個字母比較像一個字（有上限，點在鍵中央一定照按的鍵）
        val raw = char.lowercaseChar()
        if (isRawKeyEditor()) {
            // 終端機：不暫存（App 收不到暫存中的字，要等空白才一次送出），照按的鍵直接送出、不修正也不列候選
            currentInputConnection?.commitText((if (shiftState != ShiftState.OFF) char.uppercaseChar() else raw).toString(), 1)
            if (shiftState == ShiftState.ONCE) {
                shiftState = ShiftState.OFF
                updateShiftVisuals()
            }
            return
        }
        val resolved = dictManager.resolveEnglishTap(raw, offsetX, offsetY, englishWordBuffer.toString().lowercase())
        fun cased(c: Char) = if (shiftState != ShiftState.OFF) c.uppercaseChar() else c
        val outChar = cased(resolved)
        learnQwertyRetype(resolved)
        englishWordBuffer.append(outChar)
        englishTouches.add(BopomofoComposer.TouchPoint(outChar, offsetX, offsetY, rawChar = cased(raw)))
        // 打字中的字先暫存（底線），空白、標點或選字時才確定送出
        currentInputConnection?.setComposingText(englishWordBuffer, 1)
        updateEnglishCandidates()

        if (shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            updateShiftVisuals()
        }
    }

    /** 一個英文字打完（空白或選字）：學搭配、顯示可能的下一個字 */
    private fun onEnglishWordDone(word: String) {
        val clean = word.lowercase()
        if (!clean.all { it in 'a'..'z' || it == '\'' }) {
            endEnglishPhrase()
            return
        }
        if (personalizationAllowed()) lastEnglishWord?.let { prev -> serviceScope.launch { dictManager.recordBigram(prev, clean) } }
        lastEnglishWord = clean
        englishCandidateJob?.cancel()
        englishCandidateJob = serviceScope.launch {
            val predictions = dictManager.getEnglishPredictions(clean)
            if (currentMode == KeyboardMode.ENGLISH && englishWordBuffer.isEmpty()) {
                candidateAdapter?.submitList(predictions)
                rvCandidates?.scrollToPosition(0)
                updateBarVisibility()
            }
        }
    }

    /** 句子中斷（標點、刪除、Enter）：不再接續上一個字 */
    private fun endEnglishPhrase() {
        lastEnglishWord = null
        englishCandidateJob?.cancel()
        if (englishWordBuffer.isEmpty()) candidateAdapter?.submitList(emptyList())
    }

    private fun updateEnglishCandidates() {
        englishCandidateJob?.cancel()
        if (englishWordBuffer.isNotEmpty()) {
            val prefix = englishWordBuffer.toString()
            englishCandidateJob = serviceScope.launch {
                val list = dictManager.getEnglishCandidates(prefix, englishTouches.toList())
                candidateAdapter?.submitList(list)
                rvCandidates?.scrollToPosition(0)
                updateBarVisibility()
            }
        } else {
            candidateAdapter?.submitList(emptyList())
            updateBarVisibility()
        }
    }

    private fun resetShift() {
        shiftState = ShiftState.OFF
        updateShiftVisuals()
    }

    private fun updateShiftVisuals() {
        if (isChineseQwerty) return
        for (tv in englishKeyViews) {
            val original = tv.text.toString()
            tv.text = if (shiftState != ShiftState.OFF) original.uppercase() else original.lowercase()
        }

        when (shiftState) {
            ShiftState.OFF -> {
                ivEnShift?.setColorFilter(ui.getColor(R.color.ime_text_primary))
                ivEnShift?.alpha = 0.7f
            }
            ShiftState.ONCE -> {
                ivEnShift?.setColorFilter(ui.getColor(R.color.ime_key_action_bg))
                ivEnShift?.alpha = 1.0f
            }
            ShiftState.LOCKED -> {
                ivEnShift?.setColorFilter(ui.getColor(R.color.ime_key_action_bg))
                ivEnShift?.alpha = 1.0f
            }
        }
    }

    // 上一下注音鍵（連續打字時算移動造成的偏差）；中間按了別的鍵就不算連續
    private var lastZhuyinTap: BopomofoComposer.TouchPoint? = null
    private var lastZhuyinTapAt = 0L

    // 刪一個鍵後重打的校正學習
    private var retypeCandidate: BopomofoComposer.TouchPoint? = null
    private var retypeAt = 0L
    private var lastActionWasBackspace = false

    /** 刪掉一個鍵：記下被刪的落點；連刪多個鍵多半是改主意，不算 */
    private fun rememberRetype(removed: BopomofoComposer.TouchPoint?) {
        retypeCandidate = if (lastActionWasBackspace) null else removed
        retypeAt = System.currentTimeMillis()
        lastActionWasBackspace = true
    }

    /** QWERTY 鍵盤剛刪掉一個鍵、馬上改按旁邊的鍵：學「按歪的那一下其實想按 intended」 */
    private fun learnQwertyRetype(intended: Char) {
        val wrong = retypeCandidate
        val key = intended.lowercaseChar()
        if (wrong != null && lastActionWasBackspace && wrong.char.lowercaseChar() != key &&
            dictManager.qwerty.adjacent(key, wrong.rawChar.lowercaseChar()) &&
            System.currentTimeMillis() - retypeAt < RETYPE_WINDOW_MS && personalizationAllowed()
        ) {
            dictManager.learnQwertyRetype(key, wrong)
        }
        retypeCandidate = null
        lastActionWasBackspace = false
    }

    /**
     * QWERTY 落點學習：每個落點對上 intended 同一位置的字母（送出的字、選的候選）。
     * 有一個位置對不上（不是同一鍵也不是鄰鍵，例如補全或改字造成錯位）就整筆不學
     */
    private fun recordQwertyTouches(touches: List<BopomofoComposer.TouchPoint>, intended: CharSequence) {
        if (!personalizationAllowed() || touches.isEmpty() || intended.length < touches.size) return
        val pairs = ArrayList<Pair<Char, BopomofoComposer.TouchPoint>>(touches.size)
        // 長度不同（補完、或少打／多打一個字母的修正）：有任何一個字母跟按的不一樣就不學——
        // 少打／多打時後面的字母全部錯開一格，錯開的鍵剛好相鄰時會被當成按歪而學錯（gbard → gboard）
        val completion = intended.length != touches.size
        for ((i, t) in touches.withIndex()) {
            val want = intended[i].lowercaseChar()
            val raw = t.rawChar.lowercaseChar()
            if (want != raw && (completion || !dictManager.qwerty.adjacent(want, raw))) return
            // 沒有落點的（不是從鍵盤觸控來的）不學
            if (t.offsetX == 0f && t.offsetY == 0f) continue
            pairs.add(want to t)
        }
        dictManager.recordQwertySamples(pairs)
    }

    /**
     * ㄦ 的落點：按下的位置相對 ㄦ 鍵中心，跟注音鍵一樣以「注音鍵的半個鍵寬／鍵高」為 1（ㄦ 比注音鍵窄，
     * 不能用它自己的寬度，否則跟其他鍵的偏移不同尺度）
     */
    private fun erTouchOffset(er: View, grid: ViewGroup): Pair<Float, Float> {
        val root = keyboardRootView ?: return 0f to 0f
        val key = (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0) ?: return 0f to 0f
        if (key.width <= 0 || key.height <= 0) return 0f to 0f
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val erLoc = IntArray(2).also { er.getLocationInWindow(it) }
        val cx = erLoc[0] - rootLoc[0] + er.width / 2f
        val cy = erLoc[1] - rootLoc[1] + er.height / 2f
        return ((lastDownX - cx) / (key.width / 2f)).coerceIn(-1.5f, 1.5f) to ((lastDownY - cy) / (key.height / 2f)).coerceIn(-1.5f, 1.5f)
    }

    private fun onZhuyinKeyPressed(c: Char, offsetX: Float = 0f, offsetY: Float = 0f) {
        predictionJob?.cancel()
        if (!composer.isComposing && lastCommittedWord == null) captureScreenContext()
        // 空間模型判定實際的鍵：落點 + 個人化偏移 + 從上一下移過來的偏差 + 音節是否拼得起來（保留原始落點供解碼回頭修正）
        val now = android.os.SystemClock.uptimeMillis()
        val previous = lastZhuyinTap?.takeIf { now - lastZhuyinTapAt < com.p3dro.scorpio.ime.engine.MotionBias.MAX_GAP_MS }
        val touch = dictManager.resolveZhuyinTap(c, offsetX, offsetY, composer.getTouchPoints(), previous, now)
        lastZhuyinTap = touch
        lastZhuyinTapAt = now
        composer.append(touch)
        // 剛刪掉一個鍵、馬上改按別的鍵：學「按歪的那一下其實想按這個鍵」（離太遠的由空間模型自己略過）
        val wrong = retypeCandidate
        if (wrong != null && lastActionWasBackspace && wrong.char != touch.char &&
            System.currentTimeMillis() - retypeAt < RETYPE_WINDOW_MS && personalizationAllowed()
        ) {
            dictManager.learnRetypeCorrection(touch.char, wrong)
        }
        retypeCandidate = null
        lastActionWasBackspace = false
        updateComposingAndCandidates()
    }

    /** 開始打一個新詞、又沒有上一個送出的詞時：讀游標前幾個字，找出最後一個詞當上文（密碼欄、無痕模式不讀） */
    private fun captureScreenContext() {
        screenContextWord = null
        if (!personalizationAllowed()) return
        val before = try {
            currentInputConnection?.getTextBeforeCursor(8, 0)
        } catch (e: Exception) {
            null
        } ?: return
        screenContextWord = dictManager.lastWordOf(before)
    }

    /**
     * 密碼欄、要求不要個人化學習的輸入框（瀏覽器無痕分頁等）：不讀畫面上的字，也不學任何東西
     * （選字、接續詞、英文字、按鍵落點、慣性打錯、最近用過的 Emoji 都不記）
     */
    /**
     * 英文不暫存、照按的鍵直接送出的輸入框（比照 Gboard）：
     * 終端機（TYPE_NULL；Termius 這類用「可見密碼」欄位，暫存中的字它看不到）與密碼欄（不該猜字也不該列字典裡的字）
     */
    private fun isRawKeyEditor(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return info.inputType == EditorInfo.TYPE_NULL || fieldKindOf(info) == FieldKind.PASSWORD
    }

    private fun personalizationAllowed(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (cls == EditorInfo.TYPE_CLASS_TEXT && variation in setOf(
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        ) return false
        if (cls == EditorInfo.TYPE_CLASS_NUMBER && variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) return false
        return true
    }

    /** 選字用的上文：上一個送出的詞，沒有就用畫面上游標前的最後一個詞 */
    private val contextWord: String? get() = lastCommittedWord ?: screenContextWord
    private val contextWord2: String? get() = if (lastCommittedWord != null) prevCommittedWord else null

    /** 大千注音鍵盤的觸控落點（供空間模型與鄰鍵修正）；字母鍵輸入法沒有注音落點 */
    private fun zhuyinTouches(): List<BopomofoComposer.TouchPoint>? =
        if (composer.decoder == null) composer.getTouchPoints() else null

    // ---------- 多種中文輸入法 ----------

    /** 依設定載入目前的中文輸入法，切換鍵盤與解碼器 */
    private fun applyChineseMethod(method: com.p3dro.scorpio.ime.engine.ChineseMethod) {
        if (composer.isComposing) clearComposer(discardComposingText = true)
        if (codeBuffer.isNotEmpty()) clearCodeBuffer()
        chineseMethod = method
        prefs.edit().putString(com.p3dro.scorpio.ime.engine.ChineseMethod.PREF_CURRENT, method.id).apply()
        composer.decoder = method.decoderKind?.let { kind ->
            letterDecoders.getOrPut("${method.id}:${method.keymap}") { com.p3dro.scorpio.ime.engine.LetterDecoder(kind, method.keymap) }.also { d ->
                if (!d.isReady) serviceScope.launch { d.setSyllableFrequencies(dictManager.syllableFrequencyMap()) }
            }
        }
        keyboardRootView?.findViewById<TextView>(R.id.tv_space_label)?.text = method.spaceLabel
    }

    /** 地球鍵：依序切換啟用的中文輸入法，最後是英文，再回到第一個 */
    private fun switchToNextLanguage() {
        val methods = com.p3dro.scorpio.ime.engine.ChineseMethod.enabled(this, prefs)
        if (currentMode == KeyboardMode.ENGLISH) {
            applyChineseMethod(methods.first())
            setKeyboardMode(KeyboardMode.ZHUYIN)
            return
        }
        val idx = methods.indexOfFirst { it.id == chineseMethod.id }
        if (idx >= 0 && idx < methods.size - 1) {
            applyChineseMethod(methods[idx + 1])
            setKeyboardMode(KeyboardMode.ZHUYIN)
        } else {
            if (composer.isComposing) commitTopCandidateOrRaw()
            setKeyboardMode(KeyboardMode.ENGLISH)
        }
    }

    /**
     * 程式建立的面板（符號、數字、手寫）要跟哪個鍵盤一樣高：切過來的那個（英文、或用 QWERTY 的中文輸入法是英文鍵盤，大千是注音鍵盤）。
     * 還沒量到就用另一個；注音與英文鍵盤高度差一點點，取錯了切換時整個鍵盤會長高、按鍵移位
     */
    private fun sourcePanelHeight(): Int {
        val english = previousMode == KeyboardMode.ENGLISH || chineseMethod.usesQwerty
        val from = (if (english) panelEnglish?.height else panelZhuyin?.height) ?: 0
        return if (from > 0) from else maxOf(panelZhuyin?.height ?: 0, panelEnglish?.height ?: 0)
    }

    /**
     * 切過來的那個鍵盤最下排每個鍵的左右邊界（像素，相對於那一排）。程式建立的面板最下排照這個位置排，切換時返回、逗號、
     * 地球／1234、Enter 跟原本的鍵完全重合（用權重排的話，鍵數不同，邊界會差幾個像素）；還沒排版過回傳 null
     */
    private fun sourceBottomRowBounds(): List<IntArray>? {
        val english = previousMode == KeyboardMode.ENGLISH || chineseMethod.usesQwerty
        val row = keyboardRootView?.findViewById<ViewGroup>(if (english) R.id.en_row_4 else R.id.row_5) ?: return null
        if (row.width == 0) return null
        return (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility != View.GONE }.map { intArrayOf(it.left, it.right) }
    }

    /**
     * 最下排鍵上的字：跟版面檔裡注音／英文最下排一模一樣（一般 TextView、保留字型上下空白、置中），
     * 程式建立的面板（符號、數字、手寫）切換時字的大小與高低才不會跳。功能鍵 14sp、標點 18sp、空白鍵 13sp 次要色
     */
    private fun bottomRowLabel(text: String, sizeSp: Float, colorRes: Int = R.color.ime_text_primary) = TextView(ui).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(ui.getColor(colorRes))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }

    /** 最下排的圖示（地球、刪除、Enter）：20dp，刪除與地球著主要文字色、Enter 用原色，同版面檔 */
    private fun bottomRowIcon(res: Int, tint: Boolean) = ImageView(ui).apply {
        setImageDrawable(ui.getDrawable(res))
        if (tint) imageTintList = android.content.res.ColorStateList.valueOf(ui.getColor(R.color.ime_text_primary))
        layoutParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20), Gravity.CENTER)
    }

    /** 一排鍵改照指定的左右邊界排（取代權重） */
    private fun placeKeys(row: LinearLayout, bounds: List<IntArray>) {
        row.weightSum = 0f
        var x = 0
        for (i in 0 until row.childCount) {
            val (l, r) = bounds[i]
            row.getChildAt(i).layoutParams = LinearLayout.LayoutParams(r - l, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = l - x }
            x = r
        }
    }

    /** 英文鍵盤逗號鍵的字：Email 欄是 @、網址欄是 /，其他是逗號 */
    private fun englishCommaKey() = when (fieldKind) {
        FieldKind.EMAIL -> "@"
        FieldKind.URI -> "/"
        else -> ","
    }

    private fun fieldKindOf(info: EditorInfo?): FieldKind {
        val type = info?.inputType ?: return FieldKind.TEXT
        val variation = type and EditorInfo.TYPE_MASK_VARIATION
        return when (type and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
            EditorInfo.TYPE_CLASS_PHONE -> FieldKind.PHONE
            EditorInfo.TYPE_CLASS_DATETIME -> FieldKind.DATETIME
            EditorInfo.TYPE_CLASS_TEXT -> when (variation) {
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> FieldKind.PASSWORD
                EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                EditorInfo.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
    }

    /**
     * 數字鍵盤（比照 Gboard）：3×3 數字＋0，右邊一排功能鍵；上面三排把多出來的高度分掉（按鍵比一般鍵盤高）。
     * 最下排跟其他鍵盤的最下排一樣高、一樣位置：右欄在 Enter 的欄位（KeyRowLayout 第 7 欄）、數字三欄平分其餘的寬度
     *   數字：右邊 － 空白 ⌫ ↵，下排 ， 0 .（金額、數量、PIN 碼）
     *   電話：右邊 ＋ 空白 ⌫ ↵，下排 * 0 #
     *   日期時間：右邊 / : ⌫ ↵，下排 - 0 空白
     */
    private fun buildNumpad() {
        if (numpadFromUser) {
            buildUserNumpad()
            return
        }
        val pad = panelNumpad as? com.p3dro.scorpio.ime.ui.SpatialKeyLayout ?: return
        pad.removeAllViews()
        pad.clearKeys()
        pad.onKeyHapticListener = { triggerHaptic(it) }
        pad.keyPreview = keyPreview
        pad.onKeyTapListener = { char, _, _ -> currentInputConnection?.commitText(char.toString(), 1) }

        val referenceHeight = sourcePanelHeight()
        val panelHeight = if (referenceHeight > 0) referenceHeight else (5 * keyRowHeightPx() + dpToPx(4 * 4 + 4 + 6))
        pad.layoutParams = pad.layoutParams.apply { height = panelHeight }
        val gap = dpToPx(4)
        val bottomHeight = keyRowHeightPx()
        val upper = panelHeight - pad.paddingTop - pad.paddingBottom - bottomHeight - 3 * gap

        val (side, bottom) = when (fieldKind) {
            FieldKind.PHONE -> listOf("+", " ") to listOf("*", "0", "#")
            FieldKind.DATETIME -> listOf("/", ":") to listOf("-", "0", " ")
            else -> listOf("-", " ") to listOf(",", "0", ".")
        }
        fun key(parent: LinearLayout, bgAttr: Int, weight: Float = 1f) = FrameLayout(ui).apply {
            background = keyDrawable(bgAttr)
            isClickable = true
            isFocusable = true
            parent.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            })
        }
        fun label(text: String, size: Float, colorRes: Int = R.color.ime_text_primary) = TextView(ui).apply {
            this.text = text
            textSize = size
            setTextColor(ui.getColor(colorRes))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        fun charKey(parent: LinearLayout, s: String, function: Boolean = false) {
            key(parent, if (function) R.attr.imeKeyFunctionDrawable else R.attr.imeKeyDrawable).apply {
                if (s == " ") addView(label("空白", 14f, R.color.ime_text_secondary)) else addView(label(s, if (s[0].isDigit()) 24f else 20f))
                pad.registerKey(this, s[0])
            }
        }
        // 最下排的符號跟其他鍵盤最下排的標點一樣（一般鍵、18sp；空白 13sp 次要色），0 跟上面的數字一樣大
        fun bottomKey(parent: LinearLayout, s: String) {
            key(parent, R.attr.imeKeyDrawable).apply {
                addView(when {
                    s == " " -> bottomRowLabel("空白", 13f, R.color.ime_text_secondary)
                    s[0].isDigit() -> label(s, 24f)
                    else -> bottomRowLabel(s, 18f)
                })
                pad.registerKey(this, s[0])
            }
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), bottom)
        for ((r, digits) in rows.withIndex()) {
            val h = when (r) { 3 -> bottomHeight; 2 -> upper - 2 * (upper / 3); else -> upper / 3 }
            val row = com.p3dro.scorpio.ime.ui.KeyRowLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                segments = com.p3dro.scorpio.ime.ui.KeyRowLayout.NUMPAD
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
                    if (r > 0) topMargin = gap
                }
            }
            for (d in digits) if (r == 3) bottomKey(row, d) else charKey(row, d, function = !d[0].isDigit())
            when (r) {
                0, 1 -> charKey(row, side[r], function = true)
                2 -> key(row, R.attr.imeKeyFunctionDrawable).apply {
                    addView(ImageView(ui).apply {
                        setImageDrawable(ui.getDrawable(R.drawable.ic_ime_backspace))
                        setColorFilter(ui.getColor(R.color.ime_text_primary))
                        layoutParams = FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.CENTER)
                    })
                    pad.registerKey(this, null)
                    setOnTouchListener(com.p3dro.scorpio.ime.ui.RepeatTouchListener {
                        triggerHaptic(pad)
                        handleBackspace()
                    })
                }
                3 -> key(row, R.attr.imeKeyActionDrawable).apply {
                    addView(bottomRowIcon(R.drawable.ic_ime_enter, tint = false))
                    pad.registerKey(this, null)
                    setOnClickListener {
                        triggerHaptic(it)
                        handleEnterPress()
                    }
                }
            }
            pad.addView(row)
        }
    }

    /**
     * 從符號頁「1234」打開的數字鍵盤（比照 Gboard）：左邊一排 + - *，中間 3×3 數字，右邊 % 空白 ⌫；
     * 最下排 返回、逗號、!?#（回符號頁）、0、/、句點、Enter。欄寬 1 : 1.4 : 1.4 : 1.4 : 1，0 對齊 5
     */
    private fun buildUserNumpad() {
        val pad = panelNumpad as? com.p3dro.scorpio.ime.ui.SpatialKeyLayout ?: return
        pad.removeAllViews()
        pad.clearKeys()
        pad.onKeyHapticListener = { triggerHaptic(it) }
        pad.keyPreview = keyPreview
        pad.onKeyTapListener = { char, _, _ -> currentInputConnection?.commitText(char.toString(), 1) }
        val referenceHeight = sourcePanelHeight()
        val panelHeight = if (referenceHeight > 0) referenceHeight else (5 * keyRowHeightPx() + dpToPx(4 * 4 + 4 + 6))
        pad.layoutParams = pad.layoutParams.apply { height = panelHeight }
        val gap = dpToPx(4)
        val bottomHeight = keyRowHeightPx()
        val upper = panelHeight - pad.paddingTop - pad.paddingBottom - bottomHeight - 3 * gap
        val chinese = previousMode != KeyboardMode.ENGLISH

        fun row(index: Int, h: Int) = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { if (index > 0) topMargin = gap }
            pad.addView(this)
        }
        fun key(parent: LinearLayout, weight: Float, bgAttr: Int, ch: Char?) = FrameLayout(ui).apply {
            background = keyDrawable(bgAttr)
            isClickable = true
            isFocusable = true
            parent.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            })
            pad.registerKey(this, ch)
        }
        fun text(t: String, size: Float, color: Int = R.color.ime_text_primary) = com.p3dro.scorpio.ime.ui.KeyLabelView(ui).apply {
            this.text = t
            textSize = size
            setTextColor(ui.getColor(color))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        fun charKey(parent: LinearLayout, s: String, weight: Float, function: Boolean) =
            key(parent, weight, if (function) R.attr.imeKeyFunctionDrawable else R.attr.imeKeyDrawable, s[0]).apply {
                if (s == " ") addView(text("空白", 13f, R.color.ime_text_secondary)) else addView(text(s, if (s[0].isDigit()) 26f else 20f))
            }
        fun iconKey(parent: LinearLayout, weight: Float, bgAttr: Int, icon: Int, tint: Boolean) = key(parent, weight, bgAttr, null).apply {
            addView(ImageView(ui).apply {
                setImageDrawable(ui.getDrawable(icon))
                if (tint) setColorFilter(ui.getColor(R.color.ime_text_primary))
                layoutParams = FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.CENTER)
            })
        }

        // 欄位照來源鍵盤的最下排：左欄在返回鍵、右欄在 Enter 鍵的位置，中間三欄數字平分，切換時返回與 Enter 不移位
        // （最下排 , !?# 與 / . 各占一欄的一半）。來源鍵盤還沒排版時照權重排
        val src = sourceBottomRowBounds()?.takeIf { it.size >= 5 }
        val colGap = src?.let { it[1][0] - it[0][1] } ?: 0
        val digitCols = src?.let { com.p3dro.scorpio.ime.ui.KeyRowLayout.split(it[1][0], it[it.size - 2][1], listOf(1f, 1f, 1f), colGap) }
        val upperBounds = if (src != null && digitCols != null) listOf(src[0]) + digitCols + listOf(src.last()) else null
        val bottomBounds = if (src != null && digitCols != null) listOf(src[0]) +
            com.p3dro.scorpio.ime.ui.KeyRowLayout.split(digitCols[0][0], digitCols[0][1], listOf(1f, 1f), colGap) + listOf(digitCols[1]) +
            com.p3dro.scorpio.ime.ui.KeyRowLayout.split(digitCols[2][0], digitCols[2][1], listOf(1f, 1f), colGap) + listOf(src.last()) else null

        val ops = listOf("+", "-", "*")
        val digits = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        val rightCol = listOf("%", " ")
        for (r in 0 until 3) {
            val h = if (r < 2) upper / 3 else upper - 2 * (upper / 3)
            val line = row(r, h)
            charKey(line, ops[r], 1f, function = true)
            for (d in digits[r]) charKey(line, d, 1.4f, function = false)
            if (r < 2) charKey(line, rightCol[r], 1f, function = true)
            else iconKey(line, 1f, R.attr.imeKeyFunctionDrawable, R.drawable.ic_ime_backspace, tint = true).apply {
                setOnTouchListener(com.p3dro.scorpio.ime.ui.RepeatTouchListener {
                    triggerHaptic(pad)
                    handleBackspace()
                })
            }
            upperBounds?.let { placeKeys(line, it) }
        }
        // 最下排的字和圖示跟注音／英文鍵盤最下排一樣（bottomRowLabel／bottomRowIcon）；0 跟上面的數字一樣大
        val bottom = row(3, bottomHeight)
        key(bottom, 1f, R.attr.imeKeyFunctionDrawable, null).apply {
            addView(bottomRowLabel(if (chinese) "返回" else "ABC", 14f))
            setOnClickListener {
                triggerHaptic(it)
                numpadFromUser = false
                setKeyboardMode(previousMode)
            }
        }
        fun punctKey(s: String, weight: Float) = key(bottom, weight, R.attr.imeKeyDrawable, s[0]).apply { addView(bottomRowLabel(s, 18f)) }
        punctKey(",", 0.7f)
        key(bottom, 0.7f, R.attr.imeKeyFunctionDrawable, null).apply {
            addView(bottomRowLabel("!?#", 14f))
            setOnClickListener {
                triggerHaptic(it)
                setKeyboardMode(KeyboardMode.SYMBOLS)
                numpadFromUser = false
            }
        }
        charKey(bottom, "0", 1.4f, function = false)
        punctKey("/", 0.7f)
        punctKey(".", 0.7f)
        key(bottom, 1f, R.attr.imeKeyActionDrawable, null).apply {
            addView(bottomRowIcon(R.drawable.ic_ime_enter, tint = false))
            setOnClickListener {
                triggerHaptic(it)
                handleEnterPress()
            }
        }
        bottomBounds?.let { placeKeys(bottom, it) }
    }

    /** QWERTY 鍵盤在中文輸入法時鍵上顯示的符號（拼音為字母、26 鍵注音為該鍵的注音） */
    private fun qwertyLabel(c: Char): String {
        if (chineseMethod.isCodeTable) return com.p3dro.scorpio.ime.engine.ChineseMethod.CANGJIE_RADICALS[c]?.toString() ?: c.toString()
        val keymap = chineseMethod.keymap ?: return c.toString()
        val syms = com.p3dro.scorpio.ime.engine.LetterDecoder.keyMap(keymap)[c] ?: return c.toString()
        return syms.joinToString("")
    }

    /** 依目前模式更新 QWERTY 鍵盤的鍵面、空白鍵與 Shift 鍵 */
    private fun applyQwertyLabels() {
        val chinese = isChineseQwerty
        val zhuyinLabels = chinese && !chineseMethod.isPinyin
        for (tv in englishKeyViews) {
            val c = (tv.tag as? Char) ?: continue
            tv.text = when {
                zhuyinLabels -> qwertyLabel(c)
                chinese -> c.toString()
                shiftState != ShiftState.OFF -> c.uppercaseChar().toString()
                else -> c.toString()
            }
            tv.textSize = if (zhuyinLabels) (if (tv.text.length > 1) 15f else 19f) else 23f
        }
        keyboardRootView?.findViewById<TextView>(R.id.tv_en_space_label)?.text = if (chinese) chineseMethod.spaceLabel else "English"
        // Email 欄的逗號鍵換成 @、網址欄換成 /
        (keyboardRootView?.findViewById<ViewGroup>(R.id.btn_en_comma)?.getChildAt(0) as? TextView)?.text = if (chinese) "，" else englishCommaKey()
        keyboardRootView?.findViewById<TextView>(R.id.tv_space_label)?.text = chineseMethod.spaceLabel
        // Shift：英文是大小寫；拼音是分隔號；其他中文輸入法不用
        val pinyin = chinese && chineseMethod.isPinyin
        ivEnShift?.visibility = if (chinese) View.GONE else View.VISIBLE
        enShiftKey?.findViewById<TextView>(R.id.tv_en_shift_label)?.apply {
            visibility = if (pinyin) View.VISIBLE else View.GONE
        }
    }

    /** 中文 QWERTY 輸入法按下字母：按下當下依解碼先驗判定（動態感應區），再交給組字 */
    private fun onChineseLetterKey(raw: Char, offsetX: Float, offsetY: Float) {
        predictionJob?.cancel()
        if (chineseMethod.isCodeTable) {
            onCodeKey(raw, offsetX, offsetY)
            return
        }
        if (!composer.isComposing && lastCommittedWord == null) captureScreenContext()
        val decoder = composer.decoder
        val history = composer.keyHistory
        val resolved = if (decoder != null && decoder.isReady) {
            dictManager.resolveQwertyTap(raw, offsetX, offsetY) { c -> decoder.bestScoreWith(history, c) }
        } else raw
        learnQwertyRetype(resolved)
        composer.append(BopomofoComposer.TouchPoint(resolved, offsetX, offsetY, raw))
        updateComposingAndCandidates()
    }

    // ---------- 手寫 ----------

    /**
     * 手寫面板：上方手寫板，下方一排功能鍵（返回、，、空白、。、刪除、Enter）
     * 寫完一筆 350ms 後辨識整塊筆跡，結果放在候選列；點候選或空白送出並清空
     */
    private fun buildHandwritingPanel() {
        val panel = panelHandwriting ?: return
        val referenceHeight = sourcePanelHeight()
        panel.layoutParams = panel.layoutParams.apply { height = if (referenceHeight > 0) referenceHeight else (5 * keyRowHeightPx() + dpToPx(4 * 4 + 4 + 6)) }
        if (panel.childCount == 0) createHandwritingViews(panel)
        val board = handwritingView ?: return
        // 輸入法重新啟動後要再確認一次模型；已經下載過的直接顯示可以寫，真的要下載時才換成下載提示（不會每次閃一下）
        if (!handwritingReady) {
            board.hint = "在這裡手寫"
            serviceScope.launch {
                handwritingReady = handwriting.ensureModel(onDownloading = { board.hint = "正在準備手寫辨識（第一次使用需下載約 20MB）…" })
                board.hint = if (handwritingReady) "在這裡手寫" else "手寫模型下載失敗，請確認網路後再開一次"
            }
        }
    }

    private fun createHandwritingViews(panel: LinearLayout) {
        val board = com.p3dro.scorpio.ime.ui.HandwritingView(ui).apply {
            background = keyDrawable(R.attr.imeKeyDrawable)
        }
        handwritingView = board
        panel.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), dpToPx(4))
        })
        // 落筆：取消等待中的自動送出（同一個字的下一筆）；候選先留著，等新的辨識結果出來才換
        board.onStrokeStarted = { _, _ ->
            handwritingJob?.cancel()
            val pause = System.currentTimeMillis() - hwLastStrokeEnd
            val top = hwResults.firstOrNull()
            // 停筆一下才落筆、且上一個字已經辨識出來：當成下一個字，上一個字送出並淡出
            if (!board.isEmpty && hwResultsCurrent && top != null && pause >= HW_NEW_CHAR_PAUSE_MS) {
                serviceScope.launch { hwCommitLock.withLockSafe { commitHandwriting(top, auto = true) } }
            }
        }
        // 每寫完一筆：稍等辨識；停筆夠久就自動送出第一個結果、淡掉筆跡，候選留著可以改選
        board.onStrokeFinished = { ink ->
            hwLastStrokeEnd = System.currentTimeMillis()
            hwResultsCurrent = false
            handwritingJob?.cancel()
            handwritingJob = serviceScope.launch {
                kotlinx.coroutines.delay(HW_RECOGNIZE_DELAY_MS)
                if (!handwritingReady) return@launch
                val before = currentInputConnection?.getTextBeforeCursor(20, 0)?.toString().orEmpty()
                val results = handwriting.recognize(ink, board.width.toFloat(), board.height.toFloat(), before)
                if (currentMode != KeyboardMode.HANDWRITING || board.isEmpty || results.isEmpty()) return@launch
                hwResults = results
                hwResultsCurrent = true
                candidateAdapter?.submitList(results)
                rvCandidates?.scrollToPosition(0)
                updateBarVisibility()
                kotlinx.coroutines.delay(HW_AUTO_COMMIT_DELAY_MS)
                if (currentMode != KeyboardMode.HANDWRITING || board.isEmpty) return@launch
                hwCommitLock.withLockSafe { if (!board.isEmpty) commitHandwriting(results.first(), auto = true) }
            }
        }

        // 最下排跟注音／英文鍵盤同一組鍵位（KeyRowLayout），返回、逗號、刪除、Enter 不移位
        val row = com.p3dro.scorpio.ime.ui.KeyRowLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10.4f
            segments = com.p3dro.scorpio.ime.ui.KeyRowLayout.HANDWRITING
        }
        fun key(weight: Float, bgAttr: Int, label: String? = null, icon: Int? = null, onClick: (View) -> Unit) = FrameLayout(ui).apply {
            background = keyDrawable(bgAttr)
            isClickable = true
            // 字和圖示跟注音／英文鍵盤最下排一樣：功能鍵 14sp、標點 18sp、空白 13sp 次要色；刪除鍵圖示著色、Enter 原色
            if (label != null) addView(when {
                bgAttr == R.attr.imeKeyFunctionDrawable -> bottomRowLabel(label, 14f)
                label == "空白" -> bottomRowLabel(label, 13f, R.color.ime_text_secondary)
                else -> bottomRowLabel(label, 18f)
            })
            if (icon != null) addView(bottomRowIcon(icon, tint = bgAttr != R.attr.imeKeyActionDrawable))
            setOnClickListener { triggerHaptic(it); onClick(it) }
            row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            })
        }
        key(1.4f, R.attr.imeKeyFunctionDrawable, label = "返回") { collapseCandidates() }
        key(0.9f, R.attr.imeKeyDrawable, label = "，") { commitHandwritingTop(); currentInputConnection?.commitText("，", 1) }
        key(4.2f, R.attr.imeKeyDrawable, label = "空白") {
            if (handwritingView?.isEmpty == false) commitHandwritingTop() else currentInputConnection?.commitText(" ", 1)
        }
        key(0.9f, R.attr.imeKeyDrawable, label = "。") { commitHandwritingTop(); currentInputConnection?.commitText("。", 1) }
        val del = key(1.5f, R.attr.imeKeyFunctionDrawable, icon = R.drawable.ic_ime_backspace) { }
        del.setOnClickListener(null)
        del.setOnTouchListener(com.p3dro.scorpio.ime.ui.RepeatTouchListener { triggerHaptic(del); handleBackspace() })
        key(1.5f, R.attr.imeKeyActionDrawable, icon = R.drawable.ic_ime_enter) { commitHandwritingTop(); handleEnterPress() }
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyRowHeightPx()))
    }

    private suspend fun kotlinx.coroutines.sync.Mutex.withLockSafe(block: suspend () -> Unit) {
        lock()
        try {
            block()
        } finally {
            unlock()
        }
    }

    /** 手寫板上還有沒送出的字時，先送出第一個辨識結果 */
    private fun commitHandwritingTop() {
        if (handwritingView?.isEmpty != false) return
        val top = hwResults.firstOrNull()
        if (top != null) commitHandwriting(top, auto = false) else handwritingView?.clear()
    }

    /**
     * 送出手寫結果：筆跡淡出，可以馬上寫下一個字
     * 自動送出時候選列保留這次的其他結果（點了就替換剛送出的字）；手動選字則顯示接續詞
     */
    private fun commitHandwriting(word: String, auto: Boolean, fade: Boolean = true) {
        if (fade) handwritingJob?.cancel()
        currentInputConnection?.commitText(word, 1)
        if (fade) handwritingView?.fadeOut()
        hwLastCommit = word
        if (auto) {
            // 學搭配但不換成接續詞：候選列留著讓人改選
            onWordCommitted("", word, isPartial = true)
        } else {
            onWordCommitted("", word, isPartial = false)
        }
        updateBarVisibility()
    }

    // ---------- 倉頡／速成 ----------

    /** 倉頡／速成按鍵：按下當下依碼表先驗判定（動態感應區），碼滿了就不再接受 */
    private fun onCodeKey(raw: Char, offsetX: Float, offsetY: Float) {
        if (codeBuffer.length >= chineseMethod.maxCodeLength) return
        val quick = chineseMethod.isQuick
        val prefix = codeBuffer.toString()
        val resolved = dictManager.resolveQwertyTap(raw, offsetX, offsetY) { c -> dictManager.codePrefixLogMass(prefix + c, quick) }
        learnQwertyRetype(resolved)
        codeBuffer.append(resolved)
        codeTouches.add(BopomofoComposer.TouchPoint(resolved, offsetX, offsetY, raw))
        updateCodeComposing()
    }

    /** 輸入框顯示字根（日月…），候選列顯示碼表查到的字 */
    private fun updateCodeComposing() {
        val ic = currentInputConnection
        codeCandidateJob?.cancel()
        if (codeBuffer.isEmpty()) {
            ic?.setComposingText("", 1)
            ic?.finishComposingText()
            candidateAdapter?.submitList(emptyList())
            updateBarVisibility()
            return
        }
        ic?.setComposingText(codeBuffer.map { com.p3dro.scorpio.ime.engine.ChineseMethod.CANGJIE_RADICALS[it] ?: it }.joinToString(""), 1)
        val code = codeBuffer.toString()
        val quick = chineseMethod.isQuick
        codeCandidateJob = serviceScope.launch {
            val list = dictManager.codeCandidates(code, quick)
            if (codeBuffer.toString() != code) return@launch
            candidateAdapter?.submitList(list)
            rvCandidates?.scrollToPosition(0)
            updateBarVisibility()
        }
    }

    private fun commitCodeCandidate(word: String) {
        codeCandidateJob?.cancel()
        // 選了這組碼查到的字：打的每一碼都是想按的鍵
        recordQwertyTouches(codeTouches, codeBuffer)
        codeBuffer.clear()
        codeTouches.clear()
        currentInputConnection?.commitText(word, 1)
        candidateAdapter?.submitList(emptyList())
        if (isCandidateExpanded) collapseCandidates()
        // 不記注音讀音；照樣學接續搭配、顯示接續詞
        onWordCommitted("", word, isPartial = false)
        updateBarVisibility()
    }

    private fun clearCodeBuffer() {
        codeCandidateJob?.cancel()
        codeBuffer.clear()
        codeTouches.clear()
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        candidateAdapter?.submitList(emptyList())
        updateBarVisibility()
    }

    /** 中文 QWERTY 鍵盤的標點：先送出組字中的第一個候選 */
    private fun commitChinesePunctuation(p: String) {
        if (codeBuffer.isNotEmpty()) candidateAdapter?.topCandidate?.let { commitCodeCandidate(it) } ?: clearCodeBuffer()
        if (composer.isComposing) commitTopCandidateOrRaw()
        currentInputConnection?.commitText(p, 1)
        lastCommittedWord = null
        retypeCommit = null
        candidateAdapter?.submitList(emptyList())
        updateBarVisibility()
    }

    private fun updateComposingAndCandidates() {
        dismissDeleteConfirmation() // 繼續打字就當作取消
        val ic = currentInputConnection
        val display = composer.getDisplayComposingText()

        candidateJob?.cancel()
        if (display.isNotEmpty()) {
            ic?.setComposingText(display, 1)
            val segmentations = composer.getAllSegmentations()
            val hasTone = composer.hasExplicitTone()
            val touchPoints = zhuyinTouches()

            candidateJob = serviceScope.launch {
                val candidates = dictManager.getCandidates(segmentations, hasTone, contextWord, touchPoints, inlineSymbols, contextWord2)
                candidateAdapter?.submitList(candidates)
                candidateAdapter?.markUserWords(candidates, dictManager.userOnlyWords(candidates.take(40)))
                rvCandidates?.scrollToPosition(0)
                updateBarVisibility()
            }
        } else {
            clearComposer()
        }
    }

    private fun onCandidateSelected(candidate: String) {
        lastZhuyinTap = null
        if (isSymbolCandidate(candidate)) recordRecentSymbol(candidate)
        retypeCandidate = null
        lastActionWasBackspace = false
        val ic = currentInputConnection
        if (currentMode == KeyboardMode.ENGLISH) {
            // 替換使用者輸入的前綴，補全英文單字並加一個空格
            // 暫存中的字直接被選中的字取代（commitText 會換掉暫存區）
            ic?.commitText("$candidate ", 1)
            recordQwertyTouches(englishTouches, candidate)
            finishEnglishWord(learn = false)
            if (personalizationAllowed()) serviceScope.launch { dictManager.recordEnglishWord(candidate) }
            onEnglishWordDone(candidate)
            updateBarVisibility()
        } else if (codeBuffer.isNotEmpty()) {
            commitCodeCandidate(candidate)
        } else if (currentMode == KeyboardMode.HANDWRITING) {
            val last = hwLastCommit
            when {
                // 板上還有字：選哪個送哪個
                handwritingView?.isEmpty == false -> commitHandwriting(candidate, auto = false)
                // 剛自動送出：點其他結果就替換掉剛送出的字
                last != null && candidate in hwResults &&
                    ic?.getTextBeforeCursor(last.length, 0)?.toString() == last -> {
                    ic.deleteSurroundingText(last.length, 0)
                    ic.commitText(candidate, 1)
                    hwLastCommit = candidate
                }
                // 接續詞
                else -> {
                    ic?.commitText(candidate, 1)
                    hwLastCommit = null
                    hwResults = emptyList()
                    onWordCommitted("", candidate, isPartial = false)
                    updateBarVisibility()
                }
            }
        } else {
            val isChineseWord = candidate.isNotEmpty() && candidate.all { c ->
                Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
            }
            val syllables = if (composer.isComposing) composer.getAllSyllables() else emptyList()

            // 聲母＋拼不起來的介音被拆開（ㄏ＋ㄩㄢ）時，選了修正後的整句（字數＝不拆開的音節數）是整個送出，不是只選前幾個音節
            val coversAll = composer.isComposing && candidate.length == composer.mergedSyllableCount()
            if (composer.isComposing && isChineseWord && candidate.length < syllables.size && !coversAll) {
                // 部分音節選字（例如輸入「ㄏㄠˇ ㄉㄚ」選了「好」，保留「ㄉㄚ」繼續選字）
                val consumedCount = candidate.length
                val consumedReading = syllables.take(consumedCount).joinToString("-")
                sessionComposingReadings.add(consumedReading)
                sessionCommittedWords.add(candidate)

                // 1. 從 composer 移除已消費的音節（移除前先留下這幾個音節的落點，學習個人化偏移）
                recordTouchSamples(composer.touchPointsForSyllables(consumedCount), candidate)
                composer.consumeSyllables(consumedCount)

                // 2. 將選中的部分字上屏（替換目前的 composing 區域）
                ic?.commitText(candidate, 1)

                // 3. 學習已確認選定的字詞（標記 isPartial = true，避免覆蓋剩餘音節的候選字）
                onWordCommitted(consumedReading, candidate, isPartial = true)

                // 4. 即刻更新輸入框中剩餘的注音組合（例如「ㄉㄚ」），並刷新候選字
                updateComposingAndCandidates()
            } else {
                // 全部音節皆完成選字，或選了接續聯想詞/表情/顏文字
                val reading = if (composer.isComposing) composer.getDictLookupKey() else ""
                if (reading.isNotEmpty()) {
                    sessionComposingReadings.add(reading)
                }
                sessionCommittedWords.add(candidate)
                val fullCompoundWord = if (sessionCommittedWords.size > 1) sessionCommittedWords.joinToString("") else null
                val fullCompoundReading = if (sessionComposingReadings.size > 1) sessionComposingReadings.joinToString("-") else null

                if (composer.isComposing && isChineseWord && (candidate.length == syllables.size || coversAll)) {
                    recordTouchSamples(composer.getTouchPoints(), candidate)
                    // 排第一的是學過才往前排的詞，卻選了別的：扣回它的學習
                    val top = candidateAdapter?.topCandidate
                    val primary = composer.getAllSegmentations().firstOrNull().orEmpty()
                    val ctx = contextWord
                    if (top != null && top != candidate && personalizationAllowed()) {
                        serviceScope.launch { dictManager.noteSkippedLearned(ctx, primary, top, candidate) }
                    }
                    noteFullCommit(candidate, primary)
                }
                ic?.commitText(candidate, 1)
                composer.clear()
                ic?.finishComposingText()
                // 立刻清掉舊候選：接續詞查好之前，連點兩下不會把同一個詞再送一次
                candidateAdapter?.submitList(emptyList())
                if (isCandidateExpanded) {
                    collapseCandidates()
                }

                onWordCommitted(reading, candidate, isPartial = false, compoundReading = fullCompoundReading, compoundWord = fullCompoundWord)
                sessionComposingReadings.clear()
                sessionCommittedWords.clear()
                // 保險：接續詞沒來（查無或流程中斷）時，空的候選列要換回工具列
                rvCandidates?.postDelayed({
                    if (!composer.isComposing && candidateAdapter?.currentList.isNullOrEmpty()) updateBarVisibility()
                }, 300L)
            }
        }

        if (currentMode == KeyboardMode.EXPANDED) {
            if (!composer.isComposing) {
                collapseCandidates()
            }
        }
    }

    /**
     * 整個送出一個中文詞（送出前呼叫，composer 還在）：剛把上一個送出的詞整個刪掉、改打同樣字數的別的詞（一分鐘內）時，
     * 扣回刪掉那次的學習、拿那次的落點對新的詞學；再記下這次的，給下一次比
     */
    private fun noteFullCommit(word: String, syllables: List<String>) {
        val now = System.currentTimeMillis()
        val old = retypeCommit
        if (old != null && retypeDeleted == old.word.length && now - old.at < 60_000L && personalizationAllowed() &&
            old.word != word && old.word.length == word.length
        ) {
            serviceScope.launch { dictManager.learnCommitRetype(old.word, old.syllables, old.touches, old.prev, word, syllables) }
        }
        retypeCommit = if (personalizationAllowed() && composer.decoder == null) FullCommit(word, syllables, composer.getTouchPoints().toList(), contextWord, now) else null
        retypeDeleted = 0
    }

    /** 刪掉已送出的字：剛送出的詞從最後一個字開始一路刪，才算「整個刪掉重打」 */
    private fun trackRetypeDelete() {
        val rc = retypeCommit ?: return
        if (retypeDeleted == 0) {
            val before = try { currentInputConnection?.getTextBeforeCursor(rc.word.length, 0)?.toString() } catch (e: Exception) { null }
            if (before != rc.word) {
                retypeCommit = null
                return
            }
        }
        retypeDeleted++
        if (retypeDeleted > rc.word.length) retypeCommit = null
    }

    private fun recordTouchSamples(points: List<BopomofoComposer.TouchPoint>, word: String) {
        if (!personalizationAllowed()) return
        // 26 鍵注音、拼音：選了這些字母解出來的字，按下當下判定的字母就是想按的鍵
        if (composer.decoder != null) {
            recordQwertyTouches(points, points.map { it.char }.joinToString(""))
            return
        }
        if (points.isEmpty() || !word.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return
        serviceScope.launch { dictManager.recordTouchSamples(points, word) }
    }

    // 按了第一下空白之後的注音；再按空白時注音沒變（中間沒打字、沒刪）就是「第二下」
    private var spaceArmedFor: String? = null

    /**
     * 注音鍵盤空白鍵
     * - 第一下：一聲（大千標準；已經打了聲調的音節不再加）
     * - 第二下：依設定送出第一個候選字，或送出打的注音本身（例如「ㄏㄠˇ」）
     */
    private fun onZhuyinSpace() {
        lastZhuyinTap = null
        if (codeBuffer.isNotEmpty()) {
            // 倉頡／速成：空白送出第一個候選字
            val top = candidateAdapter?.topCandidate
            if (top != null) commitCodeCandidate(top) else clearCodeBuffer()
            return
        }
        val ic = currentInputConnection
        if (composer.isComposing && composer.isPinyin) {
            // 漢語拼音沒有一聲鍵：空白直接選第一個候選
            commitTopCandidateOrRaw()
            return
        }
        if (composer.isComposing) {
            if (spaceArmedFor == composer.rawInputText) {
                spaceArmedFor = null
                if (prefs.getString("double_space_action", "candidate") == "raw") commitRawZhuyin() else commitTopCandidateOrRaw()
                return
            }
            if (composer.getCurrentSyllablePart().isNotEmpty()) onZhuyinKeyPressed(BopomofoComposer.FIRST_TONE)
            spaceArmedFor = composer.rawInputText
            return
        }
        spaceArmedFor = null
        retypeCommit = null
        ic?.commitText(" ", 1)
    }

    /** 目前打的注音（不含代表一聲的空白標記） */
    private fun rawZhuyinText(): String {
        // 26 鍵注音送出解出來的注音；拼音與大千送出原本打的鍵
        if (composer.decoder != null && !composer.isPinyin) return composer.getAllSyllables().joinToString("").replace(BopomofoComposer.FIRST_TONE.toString(), "")
        return composer.rawInputText.replace(BopomofoComposer.FIRST_TONE.toString(), "").replace("'", "")
    }

    private fun commitRawZhuyin() {
        val raw = rawZhuyinText()
        currentInputConnection?.commitText(raw, 1)
        clearComposer()
        lastCommittedWord = null
    }

    /** 送出第一個候選（沒有候選就送出注音），回傳送出的字 */
    private fun commitTopCandidateOrRaw(): String? {
        val ic = currentInputConnection ?: return null
        val topWord = candidateAdapter?.topCandidate ?: composer.rawInputText
        val reading = composer.getDictLookupKey()
        if (topWord.isNotEmpty() && (topWord.length == composer.getAllSyllables().size || topWord.length == composer.mergedSyllableCount())) {
            recordTouchSamples(composer.getTouchPoints(), topWord)
            if (topWord.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                noteFullCommit(topWord, composer.getAllSegmentations().firstOrNull().orEmpty())
            }
        }
        ic.commitText(topWord, 1)
        composer.clear()
        ic.finishComposingText()
        if (isCandidateExpanded) {
            collapseCandidates()
        }
        sessionComposingReadings.clear()
        sessionCommittedWords.clear()
        if (topWord.isNotEmpty()) {
            onWordCommitted(reading, topWord, isPartial = false)
        }
        return topWord
    }

    private fun onWordCommitted(
        reading: String,
        word: String,
        isPartial: Boolean = false,
        compoundReading: String? = null,
        compoundWord: String? = null
    ) {
        val prev = lastCommittedWord
        val prev2 = contextWord2
        val prevReading = lastCommittedReading
        val now = System.currentTimeMillis()

        if (personalizationAllowed()) serviceScope.launch {
            // 1. 記錄本次單詞至自主學習詞庫
            if (reading.isNotEmpty() && word.isNotEmpty()) {
                dictManager.recordSelection(reading, word)
            }
            // 2. 若為多音節部分選字產生的組合詞（如「佩」+「卓」->「佩卓」），學習為完整多字長詞
            if (!compoundWord.isNullOrEmpty() && !compoundReading.isNullOrEmpty() && compoundWord.length > 1 && compoundWord != word) {
                dictManager.recordSelection(compoundReading, compoundWord)
            }
            // 3. 連續單字或短詞快速打字（5秒內），自動串聯為多字詞與 Bigram 搭配機率（前兩個詞都是連續打的再記三個詞的搭配）
            if (!prev.isNullOrBlank() && word.isNotBlank() && (now - lastCommitTime) < 5000L) {
                dictManager.recordBigram(prev, word)
                if (!prev2.isNullOrBlank()) dictManager.recordTrigram(prev2, prev, word)
                if (!prevReading.isNullOrBlank() && reading.isNotEmpty() && (prev.length + word.length) <= 6) {
                    val seqWord = "$prev$word"
                    val seqReading = "$prevReading-$reading"
                    dictManager.recordSelection(seqReading, seqWord)
                }
            }
        }
        prevCommittedWord = if (!prev.isNullOrBlank() && (now - lastCommitTime) < 5000L) prev else null
        lastCommittedWord = word
        lastCommittedReading = reading
        lastCommitTime = now

        if (!isPartial) {
            showNextWordPredictions(word)
        }
    }

    private fun showNextWordPredictions(word: String) {
        if (word.isBlank() || (currentMode != KeyboardMode.ZHUYIN && currentMode != KeyboardMode.HANDWRITING)) {
            if (!composer.isComposing && englishWordBuffer.isEmpty()) {
                candidateAdapter?.submitList(emptyList())
                updateBarVisibility()
            }
            return
        }
        candidateJob?.cancel()
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            val predictions = dictManager.getNextWordPredictions(word, prevCommittedWord)
            if (predictions.isNotEmpty() && !composer.isComposing && englishWordBuffer.isEmpty()) {
                candidateAdapter?.submitList(predictions)
                candidateAdapter?.markUserWords(predictions, dictManager.userOnlyWords(predictions.take(40)))
                rvCandidates?.scrollToPosition(0)
                layoutIdleBar?.visibility = View.GONE
                layoutCandidateActive?.visibility = View.VISIBLE
            } else if (!composer.isComposing && englishWordBuffer.isEmpty()) {
                candidateAdapter?.submitList(emptyList())
                updateBarVisibility()
            }
        }
    }

    private fun clearComposer(discardComposingText: Boolean = false) {
        composer.clear()
        spaceArmedFor = null
        sessionComposingReadings.clear()
        sessionCommittedWords.clear()
        val ic = currentInputConnection
        if (discardComposingText) {
            ic?.setComposingText("", 0)
        }
        ic?.finishComposingText()
        candidateAdapter?.submitList(emptyList())
        if (isCandidateExpanded) {
            collapseCandidates()
        }
        updateBarVisibility()
    }

    private var deleteConfirmBar: View? = null

    /**
     * 長按候選字：在候選列上就地顯示「移除？」確認列，從自主學習庫中永久移除該詞
     * 不用系統對話框——對話框會搶走輸入框焦點，鍵盤會被收起來
     */
    private fun showCandidateDeleteConfirmation(word: String) {
        if (word.isBlank()) return
        val bar = keyboardRootView?.findViewById<FrameLayout>(R.id.candidate_bar_container) ?: return
        keyboardRootView?.let { triggerHaptic(it) }
        dismissDeleteConfirmation()

        val primary = ui.getColor(R.color.ime_text_primary)
        fun button(text: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(ui).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpToPx(16).toFloat()
                setColor(fill)
            }
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), 0, dpToPx(8), 0)
            isClickable = true // 擋住底下的候選字
            // 底色用鍵盤的圓角底（鍵盤不一定整個依圓角裁切，直接塗方形底色左上右上會露出直角）
            background = ui.getDrawable(R.drawable.bg_keyboard_root)
            addView(TextView(ui).apply {
                text = "從個人學習庫移除「$word」？"
                textSize = 15f
                setTextColor(primary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(34)).apply { marginStart = dpToPx(6) } }
            addView(button("取消", ui.getColor(R.color.ime_key_function_bg), primary) { dismissDeleteConfirmation() }, lp())
            addView(button("移除", ui.getColor(R.color.ime_key_action_bg), ui.getColor(R.color.ime_key_action_icon)) {
                dismissDeleteConfirmation()
                serviceScope.launch {
                    dictManager.deleteUserWord(word)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScorpioImeService, "已移除「$word」", Toast.LENGTH_SHORT).show()
                        updateComposingAndCandidates()
                    }
                }
            }, lp())
        }
        bar.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        deleteConfirmBar = row
    }

    private fun dismissDeleteConfirmation() {
        deleteConfirmBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        deleteConfirmBar = null
    }

    private fun handleEnterPress() {
        lastZhuyinTap = null
        if (currentMode == KeyboardMode.ENGLISH) endEnglishPhrase()
        if (codeBuffer.isNotEmpty()) {
            val top = candidateAdapter?.topCandidate
            if (top != null) commitCodeCandidate(top) else clearCodeBuffer()
        } else if (composer.isComposing) {
            commitTopCandidateOrRaw()
        } else if (englishWordBuffer.isNotEmpty()) {
            finishEnglishWord()
            updateBarVisibility()
        } else {
            val info = currentInputEditorInfo
            val imeOptions = info?.imeOptions ?: 0
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val multiLine = info != null && (info.inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT &&
                (info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            when {
                // App 說 Enter 不做動作（一般多行輸入框：聊天、備忘錄，系統的 TextView 會自動加這個旗標）就換行；
                // 直接送出換行字元，避免 App 把 Enter 按鍵事件當成送出
                noEnterAction -> currentInputConnection?.commitText("\n", 1)
                // 有指定動作就照做，多行也一樣（比照 Gboard：Google App 的搜尋框是多行、動作是搜尋，按 Enter 要搜尋）
                action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                    currentInputConnection?.performEditorAction(action)
                multiLine -> currentInputConnection?.commitText("\n", 1)
                else -> sendKeyChar('\n')
            }
        }
    }

    /**
     * 展開候選字面板（依設定動態配置 3、4、5 欄，預設 4 欄）
     */
    private fun expandCandidates(root: View) {
        isCandidateExpanded = true
        currentExpandedType = ExpandedType.CANDIDATES
        ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_up)
        setKeyboardMode(KeyboardMode.EXPANDED)
        layoutExpandedHeader?.visibility = View.GONE
        val rvGrid = root.findViewById<RecyclerView>(R.id.rv_expanded_grid)
        loadAllWordCandidates(rvGrid)
    }

    private fun collapseCandidates() {
        isCandidateExpanded = false
        currentExpandedType = ExpandedType.NONE
        ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_down)
        val target = if (previousMode == KeyboardMode.EXPANDED) KeyboardMode.ZHUYIN else previousMode
        setKeyboardMode(target)
    }

    /**
     * 繪製極簡 Material 3 膠囊頁籤列（Gboard 風格圓角膠囊）
     */
    private fun renderPillTabs(
        tabsLayout: LinearLayout,
        categories: List<String>,
        labelOf: (String) -> String = { it },
        initialIndex: Int = 0,
        onTabSelected: (String) -> Unit
    ) {
        tabsLayout.removeAllViews()
        var selectedIndex = initialIndex
        val tabViews = mutableListOf<TextView>()

        fun updateTabStyles() {
            for ((idx, tv) in tabViews.withIndex()) {
                val isSelected = (idx == selectedIndex)
                tv.setBackgroundResource(
                    if (isSelected) R.drawable.bg_category_tab_selected
                    else R.drawable.bg_category_tab_unselected
                )
                tv.setTextColor(
                    if (isSelected) ui.getColor(R.color.ime_key_action_icon)
                    else ui.getColor(R.color.ime_text_primary)
                )
                tv.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        for ((idx, cat) in categories.withIndex()) {
            val tv = TextView(ui).apply {
                text = labelOf(cat)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(32)
                ).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setOnClickListener {
                    triggerHaptic(it)
                    selectedIndex = idx
                    updateTabStyles()
                    onTabSelected(cat)
                }
            }
            tabViews.add(tv)
            tabsLayout.addView(tv)
        }
        updateTabStyles()
    }

    // ---------- 常駐數字列、最近使用的 Emoji／顏文字 ----------

    /** 注音鍵盤上方的數字列：打到一半按數字會先送出目前的字 */
    private fun setupNumberRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.row_numbers) ?: return
        row.removeAllViews()
        row.visibility = if (prefs.getBoolean(PREF_NUMBER_ROW, false)) View.VISIBLE else View.GONE
        if (row.visibility != View.VISIBLE) return
        for (c in "1234567890") {
            row.addView(FrameLayout(ui).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
                }
                background = keyDrawable(R.attr.imeKeyDrawable)
                isClickable = true
                addView(com.p3dro.scorpio.ime.ui.KeyLabelView(ui).apply {
                    text = c.toString()
                    textSize = 19f
                    setTextColor(ui.getColor(R.color.ime_text_primary))
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener {
                    triggerHaptic(it)
                    if (composer.isComposing) commitTopCandidateOrRaw()
                    currentInputConnection?.commitText(c.toString(), 1)
                    updateBarVisibility()
                }
            })
        }
    }

    /** 候選字裡的 Emoji、顏文字：沒有漢字、注音、英文字母與數字；單一字元的只算符號類（避開「，」這種標點） */
    private fun isSymbolCandidate(s: String) = s.isNotEmpty() &&
        s.none { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' || it in '\u3105'..'\u312F' || (it.code < 128 && it.isLetterOrDigit()) } &&
        (s.length >= 2 || Character.getType(s[0]) == Character.OTHER_SYMBOL.toInt())

    private fun recentSymbols(): List<String> =
        prefs.getString(PREF_RECENT_LIST, "")!!.split(RECENT_SEP).filter { it.isNotEmpty() }.take(MAX_RECENT_SYMBOLS)

    private fun recordRecentSymbol(s: String) {
        if (!personalizationAllowed()) return
        val list = (listOf(s) + recentSymbols().filter { it != s }).take(MAX_RECENT_SYMBOLS)
        prefs.edit().putString(PREF_RECENT_LIST, list.joinToString(RECENT_SEP)).apply()
        refreshRecentBar()
    }

    private fun refreshRecentBar() {
        val box = keyboardRootView?.findViewById<LinearLayout>(R.id.layout_recent) ?: return
        box.removeAllViews()
        if (!prefs.getBoolean(PREF_RECENT_BAR, false)) return
        val list = recentSymbols()
        if (list.isEmpty()) {
            box.addView(TextView(ui).apply {
                text = "最近用過的 Emoji 會出現在這裡"
                textSize = 12f
                setTextColor(ui.getColor(R.color.ime_text_secondary))
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
            })
            return
        }
        for (item in list) {
            box.addView(TextView(ui).apply {
                text = item
                textSize = if (item.length > 3) 13f else 19f
                gravity = Gravity.CENTER
                setTextColor(ui.getColor(R.color.ime_text_primary))
                setPadding(dpToPx(10), 0, dpToPx(10), 0)
                background = keyDrawable(R.attr.imeItemBackgroundBorderless)
                setOnClickListener {
                    triggerHaptic(it)
                    if (composer.isComposing) commitTopCandidateOrRaw()
                    currentInputConnection?.commitText(item, 1)
                    recordRecentSymbol(item)
                    updateBarVisibility()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // ---------- App 更新 ----------

    private enum class UpdateStage { OFFER, DOWNLOADING, MESSAGE }

    /** 工具列上的更新列：null 是沒在更新（照常顯示工具） */
    private var updateStage: UpdateStage? = null
    private var updateMessage = ""
    private var updateProgress = -1
    private var updateJob: Job? = null
    private var updateStripView: View? = null
    private var updateStripText: TextView? = null
    private var updateStripBar: android.widget.ProgressBar? = null
    /** 這次鍵盤顯示期間亮過紅點的版本（鍵盤收起時記成看過；這段期間紅點留著） */
    private var updateDotShownTag: String? = null

    private fun closeUpdateStrip() {
        updateStage = null
        arrangeIdleBar()
    }

    private fun showUpdateMessage(text: String) {
        updateStage = UpdateStage.MESSAGE
        updateMessage = text
        arrangeIdleBar()
    }

    /** 更新列：說明文字（下載中加一條進度）＋右邊的按鈕 */
    private fun buildUpdateStrip(): View {
        val strip = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), 0, 0, 0)
        }
        val col = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = TextView(ui).apply {
            textSize = 13f
            setTextColor(ui.getColor(R.color.ime_text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(text)
        strip.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(label: String, onClick: () -> Unit) = strip.addView(TextView(ui).apply {
            this.text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ui.getColor(R.color.ime_text_primary))
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
            background = keyDrawable(R.attr.imeItemBackgroundBorderless)
            setOnClickListener {
                triggerHaptic(it)
                onClick()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateStripText = text
        updateStripBar = null
        when (updateStage) {
            UpdateStage.OFFER -> {
                val tag = com.p3dro.scorpio.ime.engine.AppUpdater.keyboardOffer(this) ?: ""
                val size = com.p3dro.scorpio.ime.engine.AppUpdater.cachedSize(this)
                text.text = "新版本 $tag" + if (size > 0) "（${"%.0f".format(size / 1048576.0)} MB）" else ""
                action("稍後") { closeUpdateStrip() }
                action("下載更新") { startAppUpdate() }
            }
            UpdateStage.DOWNLOADING -> {
                text.text = if (updateProgress < 0) "準備下載…" else "下載中… $updateProgress%"
                updateStripBar = android.widget.ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = updateProgress < 0
                    progress = updateProgress.coerceAtLeast(0)
                }
                col.addView(updateStripBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4)).apply { topMargin = dpToPx(4) })
                action("取消") {
                    updateJob?.cancel()
                    updateJob = null
                    closeUpdateStrip()
                }
            }
            else -> {
                text.text = updateMessage
                action("好") { closeUpdateStrip() }
            }
        }
        return strip
    }

    /** 下載進度：只改文字和進度條，不重排整條 */
    private fun showUpdateProgress(p: Int) {
        updateProgress = p
        if (updateStage != UpdateStage.DOWNLOADING) return
        updateStripText?.text = "下載中… $p%"
        updateStripBar?.let {
            it.isIndeterminate = false
            it.progress = p
        }
    }

    /** 按「下載更新」：先問一次最新版（拿下載網址），下載完交給系統安裝；系統安裝畫面出來時鍵盤會收起 */
    private fun startAppUpdate() {
        val U = com.p3dro.scorpio.ime.engine.AppUpdater
        updateStage = UpdateStage.DOWNLOADING
        updateProgress = -1
        arrangeIdleBar()
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val c = U.check(this@ScorpioImeService)
            val release = (c as? com.p3dro.scorpio.ime.engine.AppUpdater.Check.Available)?.release
            if (release?.apkUrl == null) {
                showUpdateMessage(if (c is com.p3dro.scorpio.ime.engine.AppUpdater.Check.Failed) c.reason else "已經是最新版")
                return@launch
            }
            val apk = U.download(this@ScorpioImeService, release) { p ->
                if (p >= 0) serviceScope.launch { showUpdateProgress(p) }
            }
            updateJob = null
            if (apk == null) {
                showUpdateMessage("下載失敗，請稍後再試")
                return@launch
            }
            installAppUpdate(apk, release.tag)
        }
    }

    private fun installAppUpdate(apk: java.io.File, tag: String?) {
        val U = com.p3dro.scorpio.ime.engine.AppUpdater
        when (U.install(this, apk)) {
            com.p3dro.scorpio.ime.engine.AppUpdater.Install.STARTED -> closeUpdateStrip()
            com.p3dro.scorpio.ime.engine.AppUpdater.Install.NEED_PERMISSION -> showUpdateMessage("允許安裝後回來，點輸入框就會接著安裝")
            com.p3dro.scorpio.ime.engine.AppUpdater.Install.NOT_NEWER -> {
                tag?.let { U.markNotNewer(this, it) }
                showUpdateMessage("手機上的版本比較新，不用更新")
            }
            com.p3dro.scorpio.ime.engine.AppUpdater.Install.BROKEN -> showUpdateMessage("下載的檔案有問題，請再試一次")
        }
    }

    /** 去開「安裝未知應用程式」回來：權限開了就接著裝剛剛下載好的版本 */
    private fun resumePendingUpdate() {
        if (updateStage != UpdateStage.DOWNLOADING) updateStage = null
        val U = com.p3dro.scorpio.ime.engine.AppUpdater
        val apk = U.pendingApk(this) ?: return
        if (!U.canInstall(this)) return
        U.clearPending(this)
        installAppUpdate(apk, null)
    }

    // ---------- 擴充功能（外掛） ----------

    private var openPluginId: String? = null

    /** 工具列左邊的工具（Emoji、顏文字、手寫、擴充功能），顯示哪些與順序依設定；點了開面板、再點一下收起 */
    private fun refreshToolbar(@Suppress("UNUSED_PARAMETER") root: View) {
        arrangeIdleBar()
    }

    // 開著最近使用列、工具又多於兩個時：先只放前兩個＋「更多」，按了收起最近使用列、整條都是工具
    private var toolsExpanded = false

    /**
     * 閒置工具列的排法（設定也是其中一個工具；開著的面板按它的工具再按一次收起）：
     * - 有自動填入建議：整條只放建議（置中），工具與最近使用列都先收起來
     * - 開著最近使用列：前兩個工具＋「更多」｜最近使用列；展開後整條是工具（可左右滑）＋「收合」
     * - 沒開最近使用列：整條都是工具，可以左右滑
     * - 開著面板（Emoji、顏文字、擴充功能、符號、手寫）：右邊放返回鈕，不放最近使用列（工具照沒開最近使用列的排法）
     */
    private fun arrangeIdleBar() {
        val root = keyboardRootView ?: return
        val scrollTools = root.findViewById<View>(R.id.scroll_tools) ?: return
        val box = root.findViewById<LinearLayout>(R.id.layout_tools) ?: return
        val divider = root.findViewById<View>(R.id.tool_divider)
        val back = root.findViewById<View>(R.id.btn_idle_back)
        val panelOpen = currentMode == KeyboardMode.EXPANDED || currentMode == KeyboardMode.SYMBOLS || currentMode == KeyboardMode.HANDWRITING
        back?.setOnClickListener {
            triggerHaptic(it)
            collapseCandidates()
        }
        // 按了「更新」：整條換成更新列（版本、下載進度、按鈕）
        val idleBar = layoutIdleBar as? LinearLayout
        updateStripView?.let { idleBar?.removeView(it) }
        updateStripView = null
        if (updateStage != null && idleBar != null) {
            for (v in listOf(scrollTools, divider, scrollRecent, back)) v?.visibility = View.GONE
            updateStripView = buildUpdateStrip().also {
                idleBar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            scrollInlineSuggestions?.visibility = View.GONE
            return
        }
        if (inlineSuggestionViews.isNotEmpty()) {
            for (v in listOf(scrollTools, divider, scrollRecent, back)) v?.visibility = View.GONE
            scrollInlineSuggestions?.visibility = View.VISIBLE
            return
        }
        scrollInlineSuggestions?.visibility = View.GONE
        scrollTools.visibility = View.VISIBLE
        back?.visibility = if (panelOpen) View.VISIBLE else View.GONE

        val all = com.p3dro.scorpio.ime.engine.Toolbar.visible(this)
        val recentOn = prefs.getBoolean(PREF_RECENT_BAR, false) && !panelOpen
        val canFold = recentOn && all.size > MAX_TOOLS_WITH_RECENT
        val compact = canFold && !toolsExpanded
        // 有新版：「更新」放在工具最前面（收合時只在「⋯」上亮紅點，展開才看得到）；紅點每一版只亮到看過為止
        val offer = com.p3dro.scorpio.ime.engine.AppUpdater.keyboardOffer(this)
        val unseen = offer != null && !com.p3dro.scorpio.ime.engine.AppUpdater.seen(this, offer)
        box.removeAllViews()
        // 最多 MAX_SLOTS 格：展開時「收合」也算一格
        val shown = when {
            compact -> all.take(MAX_TOOLS_WITH_RECENT)
            canFold -> all.take(com.p3dro.scorpio.ime.engine.Toolbar.MAX_SLOTS - 1)
            else -> all.take(com.p3dro.scorpio.ime.engine.Toolbar.MAX_SLOTS)
        }
        if (offer != null && !compact) {
            val dot = unseen || updateDotShownTag == offer
            if (dot) updateDotShownTag = offer
            box.addView(barButton(R.drawable.ic_ime_update, "更新到 $offer", dot) {
                com.p3dro.scorpio.ime.engine.AppUpdater.markSeen(this, offer)
                updateDotShownTag = null
                updateStage = UpdateStage.OFFER
                arrangeIdleBar()
            }, toolButtonParams())
        }
        for (t in shown) box.addView(toolButton(root, t), toolButtonParams())
        if (canFold) {
            box.addView(barButton(if (compact) R.drawable.ic_ime_more else R.drawable.ic_ime_chevron_left, if (compact) "更多工具" else "收合工具",
                compact && unseen && updateDotShownTag != offer) {
                toolsExpanded = !toolsExpanded
                arrangeIdleBar()
            }, toolButtonParams())
        }
        // 只有工具時佔滿整條（可滑）；跟最近使用列並排時只佔自己的寬度
        val showRecent = recentOn && !(canFold && toolsExpanded)
        scrollTools.layoutParams = LinearLayout.LayoutParams(
            if (showRecent) ViewGroup.LayoutParams.WRAP_CONTENT else 0, ViewGroup.LayoutParams.MATCH_PARENT, if (showRecent) 0f else 1f
        )
        scrollRecent?.visibility = if (showRecent) View.VISIBLE else View.GONE
        divider?.visibility = if (showRecent && box.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun toolButtonParams() = LinearLayout.LayoutParams(dpToPx(40), dpToPx(36)).apply { marginEnd = dpToPx(4) }

    private fun barButton(icon: Int, description: String, dot: Boolean = false, onClick: (View) -> Unit) = FrameLayout(ui).apply {
        contentDescription = description
        background = keyDrawable(R.attr.imeItemBackgroundBorderless)
        addView(ImageView(ui).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.CENTER))
        // 紅點：有新版還沒看過
        if (dot) addView(View(ui).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
        }, FrameLayout.LayoutParams(dpToPx(7), dpToPx(7), Gravity.TOP or Gravity.END).apply {
            topMargin = dpToPx(5)
            marginEnd = dpToPx(6)
        })
        setOnClickListener {
            triggerHaptic(it)
            onClick(it)
        }
    }

    private fun toolButton(root: View, t: com.p3dro.scorpio.ime.engine.Toolbar.Tool) = barButton(t.icon, t.name) {
        when (t.id) {
            "emoji" -> if (currentMode == KeyboardMode.EXPANDED && currentExpandedType == ExpandedType.EMOJI) collapseCandidates() else openEmojiPanel(root)
            "kaomoji" -> if (currentMode == KeyboardMode.EXPANDED && currentExpandedType == ExpandedType.KAOMOJI) collapseCandidates() else openKaomojiPanel(root)
            "handwriting" -> if (currentMode == KeyboardMode.HANDWRITING) collapseCandidates() else setKeyboardMode(KeyboardMode.HANDWRITING)
            "settings" -> startActivity(Intent(this, com.p3dro.scorpio.ime.ui.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
                val id = t.id.removePrefix("plugin:")
                if (currentMode == KeyboardMode.EXPANDED && currentExpandedType == ExpandedType.PLUGINS && openPluginId == id) collapseCandidates()
                else openPluginPanel(root, id)
            }
        }
    }

    /** 外掛面板：一個分頁一個頁籤 */
    private fun openPluginPanel(root: View, id: String) {
        val plugin = com.p3dro.scorpio.ime.engine.Plugins.installed(this).firstOrNull { it.id == id } ?: return
        isCandidateExpanded = false
        currentExpandedType = ExpandedType.PLUGINS
        openPluginId = id
        ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_down)
        setKeyboardMode(KeyboardMode.EXPANDED)
        layoutExpandedHeader?.visibility = View.VISIBLE
        val tabsLayout = root.findViewById<LinearLayout>(R.id.layout_expanded_tabs)
        val rvGrid = root.findViewById<RecyclerView>(R.id.rv_expanded_grid)
        setExpandedDividers(rvGrid, false)
        fun show(name: String) {
            val tab = plugin.tabs.firstOrNull { it.name == name } ?: return
            val now = java.util.Date()
            val shown = tab.items.map { item ->
                item.title?.let { com.p3dro.scorpio.ime.engine.Plugins.expand(it, now) } to com.p3dro.scorpio.ime.engine.Plugins.expand(item.label, now)
            }
            // 有小標題或內容較長的分頁一排兩個，短的符號一排四個；同一頁字一樣大
            val wide = shown.any { it.first != null || it.second.length > 5 }
            rvGrid.layoutManager = GridLayoutManager(this, if (wide) 2 else 4)
            rvGrid.adapter = PluginGridAdapter(shown, if (wide) 16f else 19f) { i -> tab.items.getOrNull(i)?.let { runPluginItem(it) } }
        }
        renderPillTabs(tabsLayout, plugin.tabs.map { it.name }) { show(it) }
        plugin.tabs.firstOrNull()?.let { show(it.name) }
    }

    private fun runPluginItem(item: com.p3dro.scorpio.ime.engine.Plugins.Item) {
        val ic = currentInputConnection ?: return
        if (composer.isComposing) commitTopCandidateOrRaw()
        val selection = ic.getSelectedText(0)?.toString().orEmpty()
        when (item.action) {
            "insert" -> ic.commitText(com.p3dro.scorpio.ime.engine.Plugins.expand(item.insert ?: return, java.util.Date(), selection), 1)
            "transform" -> {
                if (selection.isEmpty()) {
                    android.widget.Toast.makeText(this, "先選取要轉換的文字", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                ic.commitText(com.p3dro.scorpio.ime.engine.Plugins.transform(item.op ?: return, selection), 1)
            }
            "open_url" -> {
                val text = selection.ifEmpty { ic.getTextBeforeCursor(40, 0)?.toString()?.trim().orEmpty() }
                if (text.isEmpty()) {
                    android.widget.Toast.makeText(this, "先選取要查的文字", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                val url = com.p3dro.scorpio.ime.engine.Plugins.expand(item.url ?: return, java.util.Date(), text, encode = true)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "沒有可以開啟網址的 App", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 開啟 Emoji 獨立面板（支援 8 大完整標準分類與 260 面完整國旗）
     */
    private fun openEmojiPanel(root: View) {
        isCandidateExpanded = false
        currentExpandedType = ExpandedType.EMOJI
        ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_down)
        setKeyboardMode(KeyboardMode.EXPANDED)
        layoutExpandedHeader?.visibility = View.VISIBLE
        val tabsLayout = root.findViewById<LinearLayout>(R.id.layout_expanded_tabs)
        val rvGrid = root.findViewById<RecyclerView>(R.id.rv_expanded_grid)

        rvGrid.layoutManager = GridLayoutManager(this, 7)
        setExpandedDividers(rvGrid, false)

        serviceScope.launch {
            val categories = dictManager.getEmojiCategories()
            // 分類名稱前的 emoji 圖示（「😀 表情人物」）不顯示，只留文字
            renderPillTabs(tabsLayout, categories, labelOf = { it.substringAfter(' ').trim() }) { cat ->
                loadEmojisForCategory(cat, rvGrid)
            }
            if (categories.isNotEmpty()) {
                loadEmojisForCategory(categories.first(), rvGrid)
            }
        }
    }

    private fun loadEmojisForCategory(cat: String, rvGrid: RecyclerView) {
        serviceScope.launch {
            val list = dictManager.getEmojisByCategory(cat)
            rvGrid.adapter = ExpandedGridAdapter(list, { emo ->
                currentInputConnection?.commitText(emo, 1)
                recordRecentSymbol(emo)
            })
        }
    }

    /**
     * 開啟顏文字獨立面板（支援 12 大日本顏文字分類）
     */
    private fun openKaomojiPanel(root: View) {
        isCandidateExpanded = false
        currentExpandedType = ExpandedType.KAOMOJI
        ivExpandChevron?.setImageResource(R.drawable.ic_ime_chevron_down)
        setKeyboardMode(KeyboardMode.EXPANDED)
        layoutExpandedHeader?.visibility = View.VISIBLE
        val tabsLayout = root.findViewById<LinearLayout>(R.id.layout_expanded_tabs)
        val rvGrid = root.findViewById<RecyclerView>(R.id.rv_expanded_grid)

        // 顏文字一排四個，長的顏文字自動縮小字級塞進格子
        rvGrid.layoutManager = GridLayoutManager(this, 4)
        setExpandedDividers(rvGrid, false)

        serviceScope.launch {
            val categories = dictManager.getKaomojiCategories()
            renderPillTabs(tabsLayout, categories) { cat ->
                loadKaomojis(cat, rvGrid)
            }
            if (categories.isNotEmpty()) {
                loadKaomojis(categories.first(), rvGrid)
            }
        }
    }

    private fun loadKaomojis(cat: String, rvGrid: RecyclerView) {
        serviceScope.launch {
            val list = dictManager.getKaomojisByCategory(cat)
            rvGrid.adapter = ExpandedGridAdapter(list, { km ->
                currentInputConnection?.commitText(km, 1)
                recordRecentSymbol(km)
            }, autoSizeText = true)
        }
    }

    /**
     * 展開面板只列「從開頭算起的一組詞」（比照 Gboard）：打很長時，整句候選留在候選列，
     * 面板裡選一組送一組，剩下的重新選字後接著選下一組；短的輸入（4 個音節以內）照常全列
     */
    private fun segmentCandidates(list: List<String>, syllableCount: Int): List<String> {
        if (syllableCount <= MAX_SEGMENT_CHARS) return list
        return list.filter { w ->
            val hanOnly = w.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            !hanOnly || w.codePointCount(0, w.length) <= MAX_SEGMENT_CHARS
        }
    }

    private fun loadAllWordCandidates(rvGrid: RecyclerView) {
        serviceScope.launch {
            val syllables = composer.getAllSyllables()
            if (syllables.isEmpty()) {
                layoutExpandedHeader?.visibility = View.GONE
                showExpandedWords(rvGrid, candidateAdapter?.currentList ?: emptyList())
                return@launch
            }
            val groups = dictManager.getCandidateGroups(composer.getAllSegmentations(), composer.hasExplicitTone(), contextWord, zhuyinTouches())
            if (inlineSymbols || (groups.emoji.isEmpty() && groups.kaomoji.isEmpty())) {
                layoutExpandedHeader?.visibility = View.GONE
                showExpandedWords(rvGrid, segmentCandidates(if (inlineSymbols) groups.inline else groups.words, syllables.size))
                return@launch
            }
            // emoji／顏文字不放候選列時：展開面板以「字詞／Emoji／顏文字」分頁呈現
            val tabWords = "字詞"
            val tabEmoji = "Emoji"
            val tabKaomoji = "顏文字"
            val tabs = buildList {
                add(tabWords)
                if (groups.emoji.isNotEmpty()) add(tabEmoji)
                if (groups.kaomoji.isNotEmpty()) add(tabKaomoji)
            }
            layoutExpandedHeader?.visibility = View.VISIBLE
            val tabsLayout = keyboardRootView?.findViewById<LinearLayout>(R.id.layout_expanded_tabs) ?: return@launch
            renderPillTabs(tabsLayout, tabs) { tab ->
                when (tab) {
                    tabEmoji -> showExpandedSymbols(rvGrid, groups.emoji, columns = 6, autoSize = false)
                    tabKaomoji -> showExpandedSymbols(rvGrid, groups.kaomoji, columns = 4, autoSize = true)
                    else -> serviceScope.launch { showExpandedWords(rvGrid, segmentCandidates(groups.words, syllables.size)) }
                }
            }
            showExpandedWords(rvGrid, segmentCandidates(groups.words, syllables.size))
        }
    }

    /** 展開面板一排的格數：單字佔 1 格、詞佔 2 格（很長的顏文字佔 3 格） */
    private fun expandedCells() = prefs.getInt("candidate_grid_cells", 6).coerceIn(5, 7)

    private fun graphemeCount(s: String): Int {
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        var n = 0
        while (it.next() != java.text.BreakIterator.DONE) n++
        return n
    }

    private val expandedMeasurePaint by lazy {
        android.graphics.Paint().apply {
            textSize = EXPANDED_TEXT_SP * resources.displayMetrics.scaledDensity
            textLocale = java.util.Locale.TAIWAN
        }
    }

    /** 單字 1 格、其餘 2 格；2 格放不下（同樣字級）就佔整排 */
    private fun expandedSpan(word: String, cells: Int): Int {
        val panelW = (keyboardRootView?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - dpToPx(8)
        val cellW = panelW.toFloat() / cells
        val need = expandedMeasurePaint.measureText(word) + dpToPx(12)
        return when {
            graphemeCount(word) <= 1 && need <= cellW -> 1
            need <= cellW * 2 -> 2
            else -> cells
        }.coerceAtMost(cells)
    }

    /**
     * 排成整齊的列：這一排剩下的格子放不下下一個詞時，從後面幾個候選裡挑放得下的（通常是單字）補進來，
     * 排名只會小幅挪動，每排盡量填滿不留空洞
     */
    private fun packExpandedRows(list: List<String>, cells: Int): List<String> {
        val pending = list.toMutableList()
        val out = ArrayList<String>(list.size)
        var used = 0
        while (pending.isNotEmpty()) {
            val room = cells - used
            val idx = (0 until minOf(pending.size, 6)).firstOrNull { expandedSpan(pending[it], cells) <= room }
            if (idx == null) {
                used = 0 // 這排補不滿，下一個詞換到新的一排
                continue
            }
            val w = pending.removeAt(idx)
            out.add(w)
            used += expandedSpan(w, cells)
            if (used >= cells) used = 0
        }
        return out
    }

    /** 同一排相鄰格子之間的直分隔線（比照 Gboard），不畫橫線 */
    private inner class CellDividerDecoration : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint().apply {
            // 候選列的分隔線色是透明的（候選列刻意不畫線），這裡用次要文字色調淡，各主題都看得到
            color = ui.getColor(R.color.ime_text_secondary).let { androidx.core.graphics.ColorUtils.setAlphaComponent(it, 90) }
            strokeWidth = maxOf(1f, resources.displayMetrics.density)
        }

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val lp = child.layoutParams as? GridLayoutManager.LayoutParams ?: continue
                if (lp.spanIndex == 0) continue
                val x = child.left.toFloat() + child.translationX
                val inset = child.height * 0.28f
                c.drawLine(x, child.top + inset, x, child.bottom - inset, paint)
            }
        }
    }

    private fun setExpandedDividers(rvGrid: RecyclerView, on: Boolean) {
        for (i in rvGrid.itemDecorationCount - 1 downTo 0) {
            if (rvGrid.getItemDecorationAt(i) is CellDividerDecoration) rvGrid.removeItemDecorationAt(i)
        }
        if (on) rvGrid.addItemDecoration(CellDividerDecoration())
    }

    private suspend fun showExpandedWords(rvGrid: RecyclerView, words: List<String>) {
        val cells = expandedCells()
        val list = packExpandedRows(words, cells)
        rvGrid.layoutManager = GridLayoutManager(this, cells).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = list.getOrNull(position)?.let { expandedSpan(it, cells) } ?: 1
            }
        }
        setExpandedDividers(rvGrid, true)
        val userWords = dictManager.userOnlyWords(list)
        rvGrid.adapter = ExpandedGridAdapter(list, cellStyle = true, userWords = userWords, onSelect = { word ->
            onCandidateSelected(word)
            if (!composer.isComposing) {
                collapseCandidates()
            } else {
                loadAllWordCandidates(rvGrid)
            }
        }, onLongSelect = { word ->
            showCandidateDeleteConfirmation(word)
        })
    }

    /** emoji／顏文字分頁：點選後跟候選列一樣取代目前的注音 */
    private fun showExpandedSymbols(rvGrid: RecyclerView, list: List<String>, columns: Int, autoSize: Boolean) {
        setExpandedDividers(rvGrid, false)
        rvGrid.layoutManager = GridLayoutManager(this, columns)
        rvGrid.adapter = ExpandedGridAdapter(list, { item ->
            onCandidateSelected(item)
            if (!composer.isComposing) collapseCandidates() else loadAllWordCandidates(rvGrid)
        }, autoSizeText = autoSize)
    }

    private var symbolsPage = 0

    private fun setupSymbolsKeyboard(root: View) {
        symbolsPage = 0
        buildSymbolsPanel()
    }

    /**
     * 符號鍵盤（比照 Gboard）：第 1 頁數字與常用標點，第 2 頁（=\<）罕用符號
     * 從注音切過來時標點用全形、括號與引號改成中文常用的「」『』《》；從英文切過來用半形
     */
    private fun buildSymbolsPanel() {
        val symPanel = panelSymbols as? SpatialKeyLayout ?: return
        symPanel.removeAllViews()
        // 符號鍵同注音／英文鍵盤：點擊位置對應最近的鍵，按鍵間隙不吞字，可雙手連打
        symPanel.clearKeys()
        symPanel.onKeyHapticListener = { triggerHaptic(it) }
        symPanel.keyPreview = keyPreview
        symPanel.onKeyTapListener = { char, _, _ -> currentInputConnection?.commitText(char.toString(), 1) }
        val chinese = previousMode != KeyboardMode.ENGLISH

        // 每頁的符號在鍵盤設定（ImeConfig，跟著資料 repo 更新）：比照 Gboard，第 1 頁數字、常用符號、標點（中文是全形標點），
        // 第 2 頁數學、貨幣、括號（中文多了「」與 §）
        val pages = com.p3dro.scorpio.ime.engine.ImeConfig.get(this).symbols[if (chinese) "zh" else "en"].orEmpty()
        if (pages.isEmpty()) return
        if (symbolsPage >= pages.size) symbolsPage = 0
        val rows = pages[symbolsPage].rows
        val row3 = pages[symbolsPage].row3
        val lastPage = symbolsPage == pages.size - 1

        // 版面照切過來的那個鍵盤：大千注音鍵盤（底排有 ㄦ 與刪除鍵）或 QWERTY（英文、26 鍵注音、拼音）
        val fromZhuyinPanel = chinese && !chineseMethod.usesQwerty
        // 最下排照切過來的鍵盤：大千注音（底排有 ㄦ 與刪除鍵）；ㄦ、刪除鍵在字鍵區的大千版面（三星的鍵位）最下排跟英文一樣
        val bottomLikeZhuyin = fromZhuyinPanel && !zhuyinBottomLikeQwerty

        // 整個符號鍵盤跟「切過來的那個鍵盤」同高（實際量到的高度；尚未量到時用另一個，再沒有用 5 排的設計值），切換時畫面不跳動。
        // 按鍵跟來源鍵盤一樣高（少一排多出來的高度平均分給排距，按鍵不加高）；除不盡的像素給最後一個排距，最下排跟來源鍵盤的最下排對齊
        val referenceHeight = sourcePanelHeight()
        val panelHeight = if (referenceHeight > 0) referenceHeight else (5 * keyRowHeightPx() + dpToPx(4 * 4 + 4 + 6))
        symPanel.layoutParams = symPanel.layoutParams.apply { height = panelHeight }
        val keyHeight = keyRowHeightPx()
        val gapSpace = panelHeight - symPanel.paddingTop - symPanel.paddingBottom - 4 * keyHeight
        val rowGap = (gapSpace / 3).coerceAtLeast(dpToPx(4))
        val lastGap = (gapSpace - 2 * rowGap).coerceAtLeast(dpToPx(4))

        fun newRow(index: Int, weightSum: Float, row: LinearLayout = LinearLayout(ui)) = row.apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight).apply {
                setMargins(0, when (index) { 0 -> 0; 3 -> lastGap; else -> rowGap }, 0, 0)
            }
            this.weightSum = weightSum
        }

        fun keyFrame(weight: Float, bgAttr: Int) = FrameLayout(ui).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            }
            background = keyDrawable(bgAttr)
            isClickable = true
            isFocusable = true
        }

        // 功能鍵只參與最近鍵判定，點擊交還給按鈕本身
        fun functionKey(weight: Float, bgAttr: Int) = keyFrame(weight, bgAttr).also { symPanel.registerKey(it, null) }

        // 鍵上的字照來源鍵盤的置中方式：注音鍵去掉字型上下的預留空白；英文鍵以字形實際的中心置中（KeyLabelView）
        fun label(text: String, size: Float, colorRes: Int = R.color.ime_text_primary): TextView =
            (if (fromZhuyinPanel) TextView(ui).apply { includeFontPadding = false } else com.p3dro.scorpio.ime.ui.KeyLabelView(ui)).apply {
                this.text = text
                textSize = size
                setTextColor(ui.getColor(colorRes))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            }

        fun icon(res: Int, tint: Boolean = true) = ImageView(ui).apply {
            setImageDrawable(ui.getDrawable(res))
            if (tint) setColorFilter(ui.getColor(R.color.ime_text_primary))
            layoutParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20), android.view.Gravity.CENTER)
        }

        fun backspaceKey(weight: Float) = functionKey(weight, R.attr.imeKeyFunctionDrawable).apply {
            addView(icon(R.drawable.ic_ime_backspace))
            setOnTouchListener(RepeatTouchListener {
                triggerHaptic(symPanel)
                handleBackspace()
            })
        }

        // 字的大小跟來源鍵盤一樣：注音鍵盤 18sp、英文字母 23sp；底排的逗號、句點兩邊都是 18sp
        fun symbolKey(sym: String, weight: Float = 1f, size: Float = if (fromZhuyinPanel) 18f else 23f) = keyFrame(weight, R.attr.imeKeyDrawable).apply {
            addView(label(sym, size))
            symPanel.registerKey(this, sym[0])
        }


        // 第 1、2 排：各 10 鍵
        for ((index, row) in rows.withIndex()) {
            val rowLayout = newRow(index, row.size.toFloat())
            // 第 1 頁第一排的數字跟來源鍵盤的數字列一樣大（英文 20sp、注音 19sp）
            val digitRow = symbolsPage == 0 && index == 0
            for (sym in row) rowLayout.addView(if (digitRow) symbolKey(sym, size = if (fromZhuyinPanel) 19f else 20f) else symbolKey(sym))
            symPanel.addView(rowLayout)
        }

        // 第 3 排：翻頁鍵 + 7 個符號 + 刪除鍵（同 Gboard）
        val thirdRow = newRow(2, 10f)
        thirdRow.addView(functionKey(1.5f, R.attr.imeKeyFunctionDrawable).apply {
            addView(label(if (lastPage && symbolsPage > 0) "?123" else "=\\<", 14f))
            setOnClickListener {
                triggerHaptic(it)
                symbolsPage = if (lastPage) 0 else symbolsPage + 1
                buildSymbolsPanel()
            }
        })
        for (sym in row3) thirdRow.addView(symbolKey(sym))
        thirdRow.addView(backspaceKey(1.5f))
        symPanel.addView(thirdRow)

        // 第 4 排（比照 Gboard）：返回、逗號（第 2 頁是 <）、數字鍵盤 1234、空白、句號（第 2 頁是 >）、Enter，
        // 跟注音／英文鍵盤最下排同一組鍵位（KeyRowLayout）：英文／QWERTY 一鍵對一鍵；從大千注音來的空白鍵蓋住空白＋ㄦ、句號在刪除鍵的位置
        val bottomRow = newRow(3, 10.4f, com.p3dro.scorpio.ime.ui.KeyRowLayout(ui).apply {
            segments = if (bottomLikeZhuyin) com.p3dro.scorpio.ime.ui.KeyRowLayout.SYMBOLS_FROM_ZHUYIN else com.p3dro.scorpio.ime.ui.KeyRowLayout.QWERTY
        })
        bottomRow.addView(functionKey(1.4f, R.attr.imeKeyFunctionDrawable).apply {
            addView(bottomRowLabel(if (chinese) "返回" else "ABC", 14f))
            setOnClickListener {
                triggerHaptic(it)
                setKeyboardMode(previousMode)
            }
        })
        // 最下排的字跟注音／英文鍵盤最下排同一種寫法（bottomRowLabel），不照上面幾排的置中方式
        fun bottomSymbolKey(sym: String, weight: Float) = keyFrame(weight, R.attr.imeKeyDrawable).apply {
            addView(bottomRowLabel(sym, 18f))
            symPanel.registerKey(this, sym[0])
        }
        val left = if (symbolsPage > 0) "<" else if (chinese) "，" else ","
        val right = if (symbolsPage > 0) ">" else if (chinese) "。" else "."
        bottomRow.addView(bottomSymbolKey(left, 0.9f))
        bottomRow.addView(functionKey(0.9f, R.attr.imeKeyFunctionDrawable).apply {
            // 兩行字用一般 TextView（KeyLabelView 只照第一行置中）
            addView(TextView(ui).apply {
                text = "12\n34"
                textSize = 11f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(0f, 0.9f)
                setTextColor(ui.getColor(R.color.ime_text_primary))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            })
            contentDescription = "數字鍵盤"
            setOnClickListener {
                triggerHaptic(it)
                numpadFromUser = true
                setKeyboardMode(KeyboardMode.NUMPAD)
            }
        })
        bottomRow.addView(functionKey(if (bottomLikeZhuyin) 4.2f else 4.7f, R.attr.imeKeyDrawable).apply {
            // 空白鍵的字跟切過來的鍵盤一樣（注音／English…）
            addView(bottomRowLabel(if (chinese) chineseMethod.spaceLabel else "English", 13f, R.color.ime_text_secondary))
            setOnClickListener {
                triggerHaptic(it)
                currentInputConnection?.commitText(" ", 1)
            }
        })
        bottomRow.addView(bottomSymbolKey(right, if (bottomLikeZhuyin) 1.5f else 1.0f))
        bottomRow.addView(functionKey(1.5f, R.attr.imeKeyActionDrawable).apply {
            addView(bottomRowIcon(R.drawable.ic_ime_enter, tint = false))
            setOnClickListener {
                triggerHaptic(it)
                handleEnterPress()
            }
        })
        symPanel.addView(bottomRow)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /** 擴充功能面板的格子：小標題（可省略）＋內容，同一頁字一樣大，太長的以 … 結尾 */
    private class PluginGridAdapter(
        private val items: List<Pair<String?, String>>,
        private val textSp: Float,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<PluginGridAdapter.Holder>() {

        class Holder(val view: LinearLayout, val title: TextView, val value: TextView) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val hasTitles = items.any { it.first != null }
            fun text(sp: Float, color: Int) = TextView(ctx).apply {
                textSize = sp
                setTextColor(ctx.getColor(color))
                textLocale = java.util.Locale.TAIWAN
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val title = text(12f, R.color.ime_text_secondary)
            val value = text(textSp, R.color.ime_candidate_text)
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(R.attr.imeItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                addView(title)
                addView(value)
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ((if (hasTitles) 56 else 44) * d).toInt()).apply {
                    val m = (2 * d).toInt()
                    setMargins(m, m, m, m)
                }
            }
            return Holder(cell, title, value)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (title, value) = items[position]
            holder.title.text = title.orEmpty()
            holder.title.visibility = if (title == null) View.GONE else View.VISIBLE
            holder.value.text = value
            holder.view.contentDescription = listOfNotNull(title, value).joinToString(" ")
            holder.view.setOnClickListener {
                val i = holder.bindingAdapterPosition
                if (i >= 0) onSelect(i)
            }
        }
    }

    private class ExpandedGridAdapter(
        private val list: List<String>,
        private val onSelect: (String) -> Unit,
        private val onLongSelect: ((String) -> Unit)? = null,
        private val autoSizeText: Boolean = false,
        // 候選字詞格：格子緊貼（中間靠分隔線區隔）、高一點
        private val cellStyle: Boolean = false,
        // 使用者自建詞：標小鉛筆，只有這些可以長按刪除
        private val userWords: Set<String> = emptySet()
    ) : RecyclerView.Adapter<ExpandedGridAdapter.Holder>() {

        class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                val outValue = android.util.TypedValue()
                // 按下效果跟著「按鍵漣漪效果」：關著是瞬間變色
                parent.context.theme.resolveAttribute(R.attr.imeItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setTextColor(parent.context.getColor(R.color.ime_candidate_text))
                textLocale = java.util.Locale.TAIWAN
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                val d = parent.context.resources.displayMetrics.density
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ((if (cellStyle) 50 else 42) * d).toInt()
                ).apply {
                    val m = (2 * d).toInt()
                    if (cellStyle) setMargins(0, 0, 0, 0) else setMargins(m, m, m, m)
                }
                if (cellStyle) setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
                if (autoSizeText) {
                    isSingleLine = false
                    maxLines = 1
                    ellipsize = null
                    setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            val isUserWord = item in userWords
            holder.tv.text = if (isUserWord) com.p3dro.scorpio.ime.ui.UserWordMark.apply(holder.tv.context, item) else item
            // 依字數動態調整字體大小，保持候選字/顏文字清晰易讀且不被裁切
            if (cellStyle) {
                // 格子排版：字一律同樣大小，太長的詞改佔整排而不是縮小
                holder.tv.textSize = EXPANDED_TEXT_SP
            } else if (!autoSizeText) {
                holder.tv.textSize = when {
                    item.length <= 3 -> 20f
                    item.length <= 6 -> 17f
                    else -> 14f
                }
            }
            holder.tv.setOnClickListener {
                onSelect(item)
            }
            if (isUserWord && onLongSelect != null) {
                holder.tv.setOnLongClickListener {
                    onLongSelect.invoke(item)
                    true
                }
            } else {
                holder.tv.setOnLongClickListener(null)
                holder.tv.isLongClickable = false
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
