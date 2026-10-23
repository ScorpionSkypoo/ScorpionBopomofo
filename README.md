<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="96" alt="Scorpio 智慧注音圖示">
</p>

<h1 align="center">Scorpio 智慧注音</h1>

<p align="center">Android 注音輸入法：整句選字、免聲調、打錯也猜得到，詞庫可以分開開關、匯入匯出。</p>

---

## 功能

### 打字
- **整句選字**：依詞庫和前後文，用 Viterbi 一次挑出整句最通順的組合；第一個候選就是整句，長句也能一次選完。
- **免聲調、簡拼**：可以不打聲調，也可以只打聲母，例如 `ㄎㄅㄉ` → 看不到、`ㄏㄏ` → 哈哈。
- **打錯也猜得到**：落點只調整權重、不直接關掉鄰鍵，拼不起來時換鄰鍵重新組字；常打錯的注音（例如 `ㄉㄨㄠ` → `ㄉㄧㄠ`）選過一次正確的字就記住。
- **個人化按鍵校正**：記住你按每個鍵時習慣偏哪個方向，收集約 300 次按鍵後建立基準，之後持續微調。
- **越打越順**：選過的字詞和詞與詞的搭配會往前排；系統詞典沒有、你自己打出來的詞會標小鉛筆，長按可刪除。
- **分組選字**：打超過 4 個音節時，展開面板只列從開頭算起的詞，一組一組往下選（比照 Gboard）。

### 輸入法
- 注音：大千 41 鍵、許氏 26 鍵、倚天 26 鍵、大千 26 鍵
- 漢語拼音（可只打聲母，`kbd` → 看不到）
- 倉頡、速成
- 手寫（Google ML Kit 離線辨識，第一次使用時下載模型）
- 英文：4.7 萬字補全、鄰鍵容錯、接續詞
- Emoji（依中文詞意聯想）與顏文字

### 詞庫
- **可以個別開關**：主詞庫之外，流行用語、台灣地名路名、台灣景點、世界地名、世界地標、Emoji、顏文字都能各自開關。
- **接續詞依來源拆開**：維基百科、PTT、Dcard 三份語料各自開關，只影響整句選字和選完字後聯想的下一個詞。
- **匯入、匯出**：見下面的〈匯入詞庫〉。匯入的詞存在獨立檔案，App 改版換詞庫也不會不見。
- **每個詞庫都寫清楚來源與授權**，見 [DATA_LICENSES.md](DATA_LICENSES.md)。

### 介面
- 鍵盤主題：動態取色（跟著桌布）、經典、淺色、深色、照片背景
- 設定 App 採用 Material 3，Android 12 以上跟著桌布配色
- 按鍵預覽泡泡、按鍵高度調整、震動回饋

### 隱私
打字內容、學習紀錄都只存在手機上，不會上傳。App 唯一的網路用途是第一次使用手寫時下載 ML Kit 辨識模型。

## 安裝

目前還沒有發布 APK，請自行建置（見下一節）。需要 Android 8.0（API 26）以上、arm64 裝置。

裝好後打開「Scorpio 注音輸入法」，照首頁的引導在系統設定啟用、切換成這個輸入法即可。

## 建置

需求：JDK 17、Android SDK 34。

```bash
git clone https://github.com/ScorpionSkypoo/ScorpionBopomofo.git
cd ScorpionBopomofo
./gradlew assembleRelease          # 產出 app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest      # 單元測試
```

- release 版目前用 debug 金鑰簽章，只適合自己安裝。
- 只打包 arm64-v8a（`app/build.gradle.kts` 的 `abiFilters`）。
- 在 arm64 Linux 上建置時，Maven 上的 aapt2 是 x86 版，需要在 `~/.gradle/gradle.properties` 指定 arm64 版：`android.aapt2FromMavenOverride=/path/to/aapt2`。
- 版本號跟著 git 產生：versionName 是最近的 `v*` tag（之後有 commit 會變成 `1.0.0-3-gabc1234`），versionCode 是 100 加上 commit 數。

## 匯入詞庫

設定 →「字典與學習」→「匯入詞庫」，選一個 UTF-8 文字檔。每行取第一個含漢字的欄位當詞，注音和數字的位置不拘，所以常見格式都能直接匯入：

| 格式 | 範例 |
|---|---|
| 純詞表（讀音自動推） | `情緒價值` |
| 小麥注音、威注音使用者詞庫 | `情緒價值 ㄑㄧㄥˊ-ㄒㄩˋ-ㄐㄧㄚˋ-ㄓˊ` |
| 新酷音 `tsi.src` | `情緒價值 120 ㄑㄧㄥˊ ㄒㄩˋ ㄐㄧㄚˋ ㄓˊ` |
| 小麥注音 `data.txt` | `ㄑㄧㄥˊ-ㄒㄩˋ 情緒 -5.2` |
| RIME `.dict.yaml`（略過 YAML 檔頭，拼音不採用） | `情緒價值	qing xu jia zhi	100` |
| Gboard 匯出的 `dictionary.txt` | `qxjz	情緒價值	zh-TW` |

- 欄位以空白、Tab 或逗號分隔，`#` 開頭的行是註解。
- 注音音節用空白或 `-` 分隔；沒寫注音就依主詞庫推讀音，推不出來的詞會略過，匯入完會顯示略過幾個。
- 數字是正數時當成使用次數，負數時當成 log 詞頻。
- 匯入後會變成一個獨立的詞庫，可以開關，也可以刪除。

「匯出個人詞庫」會把學過的詞和自建詞存成「詞、注音、使用次數」的文字檔，換手機時可以直接匯入回來。

## 重建詞庫

詞庫資產（`app/src/main/assets/scorpio_dict.db.gz`、`scorpio_bigram.db.gz`）由 `tools/` 裡的腳本產生，需要 Python 3 和 `opencc`、`pyarrow`。

```bash
tools/build_packs.sh <工作目錄> [來源...]
```

- `<工作目錄>/raw/`：附加詞庫的原始資料（全國路名、觀光景點、CLDR、Wikidata 匯出檔）；缺的來源會保留原本的詞。
- `<工作目錄>/corpus/`：接續詞語料（`wiki1.bz2`、`pttpre.parquet`、`pttqa.parquet`、`dcard.parquet`）；缺的來源會保留原本的資料。
- 只更新流行用語：改 [`tools/data/trendy.txt`](tools/data/trendy.txt)，執行 `tools/build_packs.sh <工作目錄> trendy`。
- 改完內容要調 [`tools/data/packs.json`](tools/data/packs.json) 裡的 `version`，並把 `ScorpioDictManager` 的 `CURRENT_DB_VERSION`／`CURRENT_BIGRAM_VERSION` 加一，App 才會換上新資產。

各資料的下載位置寫在每支腳本開頭的說明。

## 專案結構

```
app/src/main/java/com/p3dro/scorpio/ime/
├── ScorpioImeService.kt        輸入法服務：鍵盤、候選列、展開面板、Emoji、手寫
├── engine/
│   ├── ScorpioDictManager.kt   詞庫、整句選字（Viterbi）、學習、詞庫開關與匯入
│   ├── BopomofoComposer.kt     注音組字狀態機
│   ├── LetterDecoder.kt        26 鍵注音、拼音的字母解碼
│   ├── SpatialModel.kt         按鍵落點模型與個人化校正
│   ├── DictImportParser.kt     匯入詞庫的格式解析
│   └── HandwritingRecognizer.kt
└── ui/                         設定 App、按鍵、候選列、預覽泡泡
tools/                          詞庫建置腳本與資料（packs.json、trendy.txt）
```

## 授權

- 程式碼：[MIT License](LICENSE)
- 詞庫資料：依各來源授權，維基百科與英文字頻衍生的部分為 CC BY-SA 4.0，詳見 [DATA_LICENSES.md](DATA_LICENSES.md)

主詞庫來自[小麥注音輸入法](https://github.com/openvanilla/McBopomofo)，感謝 OpenVanilla 社群。
