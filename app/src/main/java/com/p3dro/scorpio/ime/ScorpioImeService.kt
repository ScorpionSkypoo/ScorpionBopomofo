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
        ZHUYIN, ENGLISH, SYMBOLS, EXPANDED, HANDWRITING
    }

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
    private var keyPreview: com.p3dro.scorpio.ime.ui.KeyPreview? = null
    private var lastCommittedWord: String? = null

    /**
     * 畫面上游標前已經有的字的最後一個詞：還沒有「上一個送出的詞」時（剛點進輸入框、移動過游標、刪過字）當選字的上文。
     * 每次開始打新的詞時讀一次（見 captureScreenContext）；只拿來排序，不拿來學習
     */
    private var screenContextWord: String? = null
    private var lastCommittedReading: String? = null
    private var lastCommitTime: Long = 0L

    // 累加本次輸入會話中連續部分選字的音節與文字（如先選「佩」再選「卓」，智慧串聯為「佩卓」自動學習）
    private val sessionComposingReadings = mutableListOf<String>()
    private val sessionCommittedWords = mutableListOf<String>()

    // 大千注音 4 排鍵盤定義
    private val row1Keys = listOf(
        'ㄅ' to "1", 'ㄉ' to "2", 'ˇ' to "3", 'ˋ' to "4", 'ㄓ' to "5",
        'ˊ' to "6", '˙' to "7", 'ㄚ' to "8", 'ㄞ' to "9", 'ㄢ' to "0"
    )
    private val row2Keys = listOf(
        'ㄆ' to "q", 'ㄊ' to "w", 'ㄍ' to "e", 'ㄐ' to "r", 'ㄔ' to "t",
        'ㄗ' to "y", 'ㄧ' to "u", 'ㄛ' to "i", 'ㄟ' to "o", 'ㄣ' to "p"
    )
    private val row3Keys = listOf(
        'ㄇ' to "a", 'ㄋ' to "s", 'ㄎ' to "d", 'ㄑ' to "f", 'ㄕ' to "g",
        'ㄘ' to "h", 'ㄨ' to "j", 'ㄜ' to "k", 'ㄠ' to "l", 'ㄤ' to ";"
    )
    private val row4Keys = listOf(
        'ㄈ' to "z", 'ㄌ' to "x", 'ㄏ' to "c", 'ㄒ' to "v", 'ㄖ' to "b",
        'ㄙ' to "n", 'ㄩ' to "m", 'ㄝ' to ",", 'ㄡ' to ".", 'ㄥ' to "/"
    )

    // 英文 QWERTY 佈局定義（含頂部獨立數字列）
    private val enRow0 = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    private val enRow1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    private val enRow2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    private val enRow3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

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
            lp.height = dpToPx(44f * scale)
            v.layoutParams = lp
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scaleKeyRows(v.getChildAt(i), scale)
    }

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

        // 建置注音鍵盤
        setupRow(root.findViewById(R.id.row_1), row1Keys)
        setupRow(root.findViewById(R.id.row_2), row2Keys)
        setupRow(root.findViewById(R.id.row_3), row3Keys)
        setupRow(root.findViewById(R.id.row_4), row4Keys)

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
        lastCommittedWord = null
        screenContextWord = null
        lastEnglishWord = null
        clearComposer()
        val methods = com.p3dro.scorpio.ime.engine.ChineseMethod.enabled(this, prefs)
        val savedId = prefs.getString(com.p3dro.scorpio.ime.engine.ChineseMethod.PREF_CURRENT, null)
        val method = methods.firstOrNull { it.id == savedId } ?: methods.first()
        if (method != chineseMethod || composer.decoder?.kind != method.decoderKind) applyChineseMethod(method)
        finishEnglishWord(learn = false)
        setKeyboardMode(KeyboardMode.ZHUYIN)
        toolsExpanded = false
        keyboardRootView?.let { refreshToolbar(it) }
        refreshRecentBar()
        updateBarVisibility()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        finishEnglishWord(learn = false)
        keyPreview?.hideAll()
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
        if (currentMode != KeyboardMode.SYMBOLS && currentMode != KeyboardMode.EXPANDED && currentMode != KeyboardMode.HANDWRITING) {
            previousMode = currentMode
        }
        currentMode = mode
        keyPreview?.hideAll()
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

        val qwertyChinese = mode == KeyboardMode.ZHUYIN && chineseMethod.usesQwerty
        panelZhuyin?.visibility = if (mode == KeyboardMode.ZHUYIN && !qwertyChinese) View.VISIBLE else View.GONE
        panelEnglish?.visibility = if (mode == KeyboardMode.ENGLISH || qwertyChinese) View.VISIBLE else View.GONE
        if (mode == KeyboardMode.ENGLISH || mode == KeyboardMode.ZHUYIN) applyQwertyLabels()
        panelSymbols?.visibility = if (mode == KeyboardMode.SYMBOLS) View.VISIBLE else View.GONE
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
    }

    private fun setupRow(rowLayout: LinearLayout, keys: List<Pair<Char, String>>) {
        rowLayout.removeAllViews()
        for ((zhuyin, sub) in keys) {
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

        // ㄦ：點一下是注音，長按（放開時）直接輸入「ㄦ」這個符號
        root.findViewById<View>(R.id.btn_er).setOnTouchListener(longPressKeyTouchListener("ㄦ", onTap = {
            onZhuyinKeyPressed('ㄦ')
        }, onLong = {
            if (composer.isComposing) {
                clearComposer(discardComposingText = true)
            }
            currentInputConnection?.commitText("ㄦ", 1)
            updateBarVisibility()
        }))

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

        val row0 = root.findViewById<LinearLayout>(R.id.en_row_0)
        row0.removeAllViews()
        for (c in enRow0) {
            row0.addView(createEnglishNumberKey(c, 1f).also { spatial.registerKey(it, c) })
        }

        val row1 = root.findViewById<LinearLayout>(R.id.en_row_1)
        row1.removeAllViews()
        for (c in enRow1) {
            row1.addView(createEnglishKey(c, 1f).also { spatial.registerKey(it, c) })
        }

        val row2 = root.findViewById<LinearLayout>(R.id.en_row_2)
        row2.removeAllViews()

        // a 左側、l 右側的半鍵空白：觸控由空間定位歸給最近的 a / l
        row2.addView(View(ui).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        })
        for (c in enRow2) {
            row2.addView(createEnglishKey(c, 1f).also { spatial.registerKey(it, c) })
        }
        row2.addView(View(ui).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        })

        val row3KeysLayout = root.findViewById<LinearLayout>(R.id.en_row_3_keys)
        row3KeysLayout.removeAllViews()
        for (c in enRow3) {
            row3KeysLayout.addView(createEnglishKey(c, 1f).also { spatial.registerKey(it, c) })
        }

        // 功能鍵只參與範圍劃分，觸控交還按鈕本身（Shift 連按、刪除長按連刪等）
        for (id in listOf(
            R.id.btn_en_shift, R.id.btn_en_backspace, R.id.btn_en_symbols, R.id.btn_en_comma,
            R.id.btn_en_globe, R.id.btn_en_space, R.id.btn_en_period, R.id.btn_en_enter
        )) {
            spatial.registerKey(root.findViewById(id), null)
        }

        ivEnShift = root.findViewById(R.id.iv_en_shift)
        root.findViewById<View>(R.id.btn_en_shift).setOnClickListener {
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

        root.findViewById<View>(R.id.btn_en_backspace).setOnTouchListener(RepeatTouchListener {
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
            currentInputConnection?.commitText(",", 1)
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
            com.p3dro.scorpio.ime.engine.QwertyModel.adjacent(key, wrong.rawChar.lowercaseChar()) &&
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
        for ((i, t) in touches.withIndex()) {
            val want = intended[i].lowercaseChar()
            val raw = t.rawChar.lowercaseChar()
            if (want != raw && !com.p3dro.scorpio.ime.engine.QwertyModel.adjacent(want, raw)) return
            // 沒有落點的（不是從鍵盤觸控來的）不學
            if (t.offsetX == 0f && t.offsetY == 0f) continue
            pairs.add(want to t)
        }
        dictManager.recordQwertySamples(pairs)
    }

    private fun onZhuyinKeyPressed(c: Char, offsetX: Float = 0f, offsetY: Float = 0f) {
        predictionJob?.cancel()
        if (!composer.isComposing && lastCommittedWord == null) captureScreenContext()
        // 空間模型判定實際的鍵：落點 + 個人化偏移 + 音節是否拼得起來（保留原始落點供解碼回頭修正）
        val touch = dictManager.resolveZhuyinTap(c, offsetX, offsetY, composer.getTouchPoints())
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
        keyboardRootView?.findViewById<TextView>(R.id.tv_space_label)?.text = chineseMethod.spaceLabel
        // Shift：英文是大小寫；拼音是分隔號；其他中文輸入法不用
        val pinyin = chinese && chineseMethod.isPinyin
        ivEnShift?.visibility = if (chinese) View.GONE else View.VISIBLE
        keyboardRootView?.findViewById<TextView>(R.id.tv_en_shift_label)?.apply {
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
        val referenceHeight = maxOf(panelZhuyin?.height ?: 0, panelEnglish?.height ?: 0)
        panel.layoutParams = panel.layoutParams.apply { height = if (referenceHeight > 0) referenceHeight else dpToPx(5 * 44 * keyHeightScale() + 4 * 4 + 4 + 6) }
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

        val row = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 10.4f }
        fun key(weight: Float, bgAttr: Int, label: String? = null, icon: Int? = null, onClick: (View) -> Unit) = FrameLayout(ui).apply {
            background = keyDrawable(bgAttr)
            isClickable = true
            if (label != null) addView(TextView(ui).apply {
                text = label
                textSize = 15f
                setTextColor(ui.getColor(R.color.ime_text_primary))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            if (icon != null) addView(ImageView(ui).apply { setImageResource(icon) },
                FrameLayout.LayoutParams(dpToPx(20), dpToPx(20), android.view.Gravity.CENTER))
            setOnClickListener { triggerHaptic(it); onClick(it) }
            row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(2.5f), 0, dpToPx(2.5f), 0)
            })
        }
        key(1.4f, R.attr.imeKeyFunctionDrawable, label = "返回") { collapseCandidates() }
        key(0.9f, R.attr.imeKeyDrawable, label = "，") { commitHandwritingTop(); currentInputConnection?.commitText("，", 1) }
        key(3.4f, R.attr.imeKeyDrawable, label = "空白") {
            if (handwritingView?.isEmpty == false) commitHandwritingTop() else currentInputConnection?.commitText(" ", 1)
        }
        key(0.9f, R.attr.imeKeyDrawable, label = "。") { commitHandwritingTop(); currentInputConnection?.commitText("。", 1) }
        val del = key(1.4f, R.attr.imeKeyFunctionDrawable, icon = R.drawable.ic_ime_backspace) { }
        del.setOnClickListener(null)
        del.setOnTouchListener(com.p3dro.scorpio.ime.ui.RepeatTouchListener { triggerHaptic(del); handleBackspace() })
        key(1.5f, R.attr.imeKeyActionDrawable, icon = R.drawable.ic_ime_enter) { commitHandwritingTop(); handleEnterPress() }
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44f * keyHeightScale())))
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
                val candidates = dictManager.getCandidates(segmentations, hasTone, contextWord, touchPoints, inlineSymbols)
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

            if (composer.isComposing && isChineseWord && candidate.length < syllables.size) {
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

                if (composer.isComposing && isChineseWord && candidate.length == syllables.size) {
                    recordTouchSamples(composer.getTouchPoints(), candidate)
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

    private enum class SpaceAction { TONE, COMMIT }

    private var lastSpaceTime = 0L
    private var lastSpaceAction: SpaceAction? = null
    // 空白鍵送出的字與當時的注音（連按兩下「送出注音」時換回注音用）
    private var lastSpaceCommitted: String? = null
    private var lastSpaceRaw: String? = null

    /**
     * 注音鍵盤空白鍵
     * - 按一下：音節還沒打完 → 一聲（大千標準）；已打完（有聲調） → 送出第一個候選
     * - 連按兩下（350ms 內）：依設定「送出第一個候選字」或「送出注音符號本身」
     */
    private fun onZhuyinSpace() {
        if (codeBuffer.isNotEmpty()) {
            // 倉頡／速成：空白送出第一個候選字
            val top = candidateAdapter?.topCandidate
            if (top != null) commitCodeCandidate(top) else clearCodeBuffer()
            return
        }
        val now = System.currentTimeMillis()
        val isDouble = now - lastSpaceTime < 350L
        val rawMode = prefs.getString("double_space_action", "candidate") == "raw"
        val ic = currentInputConnection
        lastSpaceTime = now

        if (composer.isComposing) {
            if (isDouble && rawMode && lastSpaceAction == SpaceAction.TONE) {
                // 撤掉第一下加的一聲，把打的注音原樣送出
                composer.backspace()
                commitRawZhuyin()
                lastSpaceAction = null
                return
            }
            if (composer.getCurrentSyllablePart().isNotEmpty() && !(isDouble && lastSpaceAction == SpaceAction.TONE)) {
                // 大千標準：空白鍵＝一聲（字典中一聲無符號），結束目前音節
                onZhuyinKeyPressed(BopomofoComposer.FIRST_TONE)
                lastSpaceAction = SpaceAction.TONE
            } else {
                lastSpaceRaw = rawZhuyinText()
                lastSpaceCommitted = commitTopCandidateOrRaw()
                lastSpaceAction = SpaceAction.COMMIT
            }
            return
        }

        if (isDouble && rawMode && lastSpaceAction == SpaceAction.COMMIT) {
            // 第一下已送出候選：換回注音
            val word = lastSpaceCommitted
            val raw = lastSpaceRaw
            if (!word.isNullOrEmpty() && !raw.isNullOrEmpty()) {
                ic?.deleteSurroundingText(word.length, 0)
                ic?.commitText(raw, 1)
                predictionJob?.cancel()
                candidateAdapter?.submitList(emptyList())
                lastCommittedWord = null
                updateBarVisibility()
            }
            lastSpaceAction = null
            return
        }
        lastSpaceAction = null
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
        if (topWord.isNotEmpty() && topWord.length == composer.getAllSyllables().size) {
            recordTouchSamples(composer.getTouchPoints(), topWord)
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
            // 3. 連續單字或短詞快速打字（5秒內），自動串聯為多字詞與 Bigram 搭配機率
            if (!prev.isNullOrBlank() && word.isNotBlank() && (now - lastCommitTime) < 5000L) {
                dictManager.recordBigram(prev, word)
                if (!prevReading.isNullOrBlank() && reading.isNotEmpty() && (prev.length + word.length) <= 6) {
                    val seqWord = "$prev$word"
                    val seqReading = "$prevReading-$reading"
                    dictManager.recordSelection(seqReading, seqWord)
                }
            }
        }
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
            val predictions = dictManager.getNextWordPredictions(word)
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
                // 多行輸入框（聊天、備忘錄）一律換行；直接送出換行字元，避免 App 把 Enter 按鍵事件當成送出
                multiLine || noEnterAction -> currentInputConnection?.commitText("\n", 1)
                action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                    currentInputConnection?.performEditorAction(action)
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
     */
    private fun arrangeIdleBar() {
        val root = keyboardRootView ?: return
        val scrollTools = root.findViewById<View>(R.id.scroll_tools) ?: return
        val box = root.findViewById<LinearLayout>(R.id.layout_tools) ?: return
        val divider = root.findViewById<View>(R.id.tool_divider)
        if (inlineSuggestionViews.isNotEmpty()) {
            for (v in listOf(scrollTools, divider, scrollRecent)) v?.visibility = View.GONE
            scrollInlineSuggestions?.visibility = View.VISIBLE
            return
        }
        scrollInlineSuggestions?.visibility = View.GONE
        scrollTools.visibility = View.VISIBLE

        val all = com.p3dro.scorpio.ime.engine.Toolbar.visible(this)
        val recentOn = prefs.getBoolean(PREF_RECENT_BAR, false)
        val canFold = recentOn && all.size > MAX_TOOLS_WITH_RECENT
        val compact = canFold && !toolsExpanded
        box.removeAllViews()
        // 最多 MAX_SLOTS 格：展開時「收合」也算一格
        val shown = when {
            compact -> all.take(MAX_TOOLS_WITH_RECENT)
            canFold -> all.take(com.p3dro.scorpio.ime.engine.Toolbar.MAX_SLOTS - 1)
            else -> all.take(com.p3dro.scorpio.ime.engine.Toolbar.MAX_SLOTS)
        }
        for (t in shown) box.addView(toolButton(root, t), toolButtonParams())
        if (canFold) {
            box.addView(barButton(if (compact) R.drawable.ic_ime_more else R.drawable.ic_ime_chevron_left, if (compact) "更多工具" else "收合工具") {
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

    private fun barButton(icon: Int, description: String, onClick: (View) -> Unit) = FrameLayout(ui).apply {
        contentDescription = description
        background = keyDrawable(R.attr.imeItemBackgroundBorderless)
        addView(ImageView(ui).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.CENTER))
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

        val rows: List<List<String>>
        val row3: List<String>
        if (symbolsPage == 0) {
            rows = if (chinese) listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "（", "）", "/")
            ) else listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
            )
            row3 = if (chinese) listOf("＊", "「", "」", "：", "；", "！", "？")
            else listOf("*", "\"", "'", ":", ";", "!", "?")
        } else {
            rows = if (chinese) listOf(
                listOf("、", "『", "』", "《", "》", "…", "—", "～", "·", "※"),
                listOf("~", "`", "|", "•", "÷", "×", "=", "{", "}", "\\")
            ) else listOf(
                listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
                listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
            )
            row3 = if (chinese) listOf("%", "<", ">", "^", "°", "[", "]")
            else listOf("%", "©", "®", "™", "✓", "[", "]")
        }

        // 整個符號鍵盤與注音／英文鍵盤同高（實際量到的高度；尚未量到時用 5 排 44dp 的設計值），切換時畫面不跳動
        // 比照 Gboard：按鍵維持 44dp 不變，多出來的高度平均分給排與排之間的間距
        val referenceHeight = maxOf(panelZhuyin?.height ?: 0, panelEnglish?.height ?: 0)
        val panelHeight = if (referenceHeight > 0) referenceHeight else dpToPx(5 * 44 * keyHeightScale() + 4 * 4 + 4 + 6)
        symPanel.layoutParams = symPanel.layoutParams.apply { height = panelHeight }
        val keyHeight = dpToPx(44f * keyHeightScale())
        val rowGap = ((panelHeight - symPanel.paddingTop - symPanel.paddingBottom - 4 * keyHeight) / 3)
            .coerceAtLeast(dpToPx(4))

        fun newRow(index: Int, weightSum: Float) = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight).apply {
                setMargins(0, if (index == 0) 0 else rowGap, 0, 0)
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

        fun label(text: String, size: Float, colorRes: Int = R.color.ime_text_primary) = TextView(ui).apply {
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

        fun symbolKey(sym: String, weight: Float = 1f) = keyFrame(weight, R.attr.imeKeyDrawable).apply {
            addView(label(sym, 18f))
            symPanel.registerKey(this, sym[0])
        }

        // 功能鍵只參與最近鍵判定，點擊交還給按鈕本身
        fun functionKey(weight: Float, bgAttr: Int) = keyFrame(weight, bgAttr).also { symPanel.registerKey(it, null) }

        // 第 1、2 排：各 10 鍵
        for ((index, row) in rows.withIndex()) {
            val rowLayout = newRow(index, row.size.toFloat())
            for (sym in row) rowLayout.addView(symbolKey(sym))
            symPanel.addView(rowLayout)
        }

        // 第 3 排：翻頁鍵 + 7 個符號 + 刪除鍵（同 Gboard）
        val thirdRow = newRow(2, 10f)
        thirdRow.addView(functionKey(1.5f, R.attr.imeKeyFunctionDrawable).apply {
            addView(label(if (symbolsPage == 0) "=\\<" else "?123", 14f))
            setOnClickListener {
                triggerHaptic(it)
                symbolsPage = 1 - symbolsPage
                buildSymbolsPanel()
            }
        })
        for (sym in row3) thirdRow.addView(symbolKey(sym))
        thirdRow.addView(functionKey(1.5f, R.attr.imeKeyFunctionDrawable).apply {
            addView(icon(R.drawable.ic_ime_backspace))
            setOnTouchListener(RepeatTouchListener {
                triggerHaptic(symPanel)
                handleBackspace()
            })
        })
        symPanel.addView(thirdRow)

        // 第 4 排：返回、逗號、語言切換、空白、句號、Enter（權重總和 10.4，與注音／英文底排對齊）
        val bottomRow = newRow(3, 10.4f)
        bottomRow.addView(functionKey(1.4f, R.attr.imeKeyFunctionDrawable).apply {
            addView(label(if (chinese) "注音" else "ABC", 14f))
            setOnClickListener {
                triggerHaptic(it)
                setKeyboardMode(previousMode)
            }
        })
        val comma = if (chinese) "，" else ","
        bottomRow.addView(symbolKey(comma, 0.9f))
        bottomRow.addView(functionKey(0.9f, R.attr.imeKeyFunctionDrawable).apply {
            addView(icon(R.drawable.ic_ime_globe))
            setOnClickListener {
                triggerHaptic(it)
                currentMode = previousMode
                switchToNextLanguage()
            }
        })
        bottomRow.addView(functionKey(4.8f, R.attr.imeKeyDrawable).apply {
            addView(label("空白", 13f, R.color.ime_text_secondary))
            setOnClickListener {
                triggerHaptic(it)
                currentInputConnection?.commitText(" ", 1)
            }
        })
        bottomRow.addView(symbolKey(if (chinese) "。" else ".", 0.9f))
        bottomRow.addView(functionKey(1.5f, R.attr.imeKeyActionDrawable).apply {
            addView(icon(R.drawable.ic_ime_enter, tint = false))
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
