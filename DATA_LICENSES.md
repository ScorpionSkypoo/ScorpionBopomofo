# 詞庫資料來源與授權

程式碼以 [MIT License](LICENSE) 釋出。`app/src/main/assets/` 裡的詞庫資料是從下列來源整理、統計而來，各自依原來源的授權條款使用；再散布這些資料時，請一併保留本檔與 `licenses/` 目錄。

每個詞庫的名稱、來源、授權與版本也記在 [`tools/data/packs.json`](tools/data/packs.json)，App 的「設定 → 字典與學習」和「關於」頁會列出來。

## 中文詞庫（`scorpio_dict.db.gz`）

| 內容 | 來源 | 授權 |
|---|---|---|
| 主詞庫：字詞、讀音、詞頻 | [小麥注音輸入法](https://github.com/openvanilla/McBopomofo)詞庫 | MIT License，著作權聲明見 [`licenses/McBopomofo-MIT.txt`](licenses/McBopomofo-MIT.txt) |
| 流行與口語用語 | 本專案人工整理（[`tools/data/trendy.txt`](tools/data/trendy.txt)） | MIT License（同本專案） |
| 台灣地名與路名 | 內政部戶政司〈[全國路名資料](https://data.gov.tw/dataset/35321)〉 | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 台灣景點與地標 | 交通部觀光署〈[景點－觀光資訊資料庫](https://data.gov.tw/dataset/7777)〉 | 政府資料開放授權條款－第 1 版 |
| 世界國家與城市 | [Unicode CLDR](https://cldr.unicode.org/) 地區與城市名稱、[Wikidata](https://www.wikidata.org/) | Unicode License v3、CC0 1.0 |
| 世界地標與地理 | [Wikidata](https://www.wikidata.org/) | CC0 1.0 |
| Emoji 關鍵字 | [Unicode CLDR](https://github.com/unicode-org/cldr) zh_Hant 註解（release 46） | Unicode License v3，見 [`licenses/Unicode-License-v3.txt`](licenses/Unicode-License-v3.txt) |
| 顏文字 | 專案初期內建的清單，原始出處未記錄 | — |
| 倉頡、速成碼表 | [Unicode Unihan](https://www.unicode.org/Public/UCD/latest/ucd/) `kCangjie` | Unicode License v3 |
| 英文單字與字頻 | [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)（OpenSubtitles 2018） | CC BY-SA 4.0 |
| 英文接續詞 | [OPUS OpenSubtitles v2018](https://opus.nlpl.eu/OpenSubtitles/corpus/version/OpenSubtitles) 英文對白統計 | 依 OPUS 規定引用：P. Lison and J. Tiedemann (2016), *OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles*, LREC 2016 |

## 接續詞語料（`scorpio_bigram.db.gz`）

只存「前一個詞 → 下一個詞」的條件機率（ln P(next | prev)），不含原文。

| 來源 | 原始資料 | 授權 |
|---|---|---|
| 維基百科 | [中文維基百科](https://dumps.wikimedia.org/zhwiki/)條目，以 OpenCC `s2twp` 轉台灣正體用語 | CC BY-SA 4.0 |
| PTT | Hugging Face [yuhuanstudio/PTT-pretrain-zhtw](https://huggingface.co/datasets/yuhuanstudio/PTT-pretrain-zhtw)、[Riyuechang/PTT-Corpus-100K_Gossiping-1400-39400_v2](https://huggingface.co/datasets/Riyuechang/PTT-Corpus-100K_Gossiping-1400-39400_v2) | Apache License 2.0 |
| Dcard | Hugging Face [iddle/dcard_conversations](https://huggingface.co/datasets/iddle/dcard_conversations) | MIT License |
| 流行與口語用語 | 本專案人工整理（`tools/data/trendy.txt` 的 `[pairs]`） | MIT License（同本專案） |

## 相同方式分享（ShareAlike）

維基百科與 FrequencyWords 是 CC BY-SA 4.0，所以從它們衍生的部分（`scorpio_bigram.db.gz` 中 `source = 'bg_wiki'` 的資料、`scorpio_dict.db.gz` 的 `english_dict` 表）再散布時也要以 CC BY-SA 4.0 授權並註明出處。其他程式碼與資料不受影響。

## 重建詞庫

建置腳本在 [`tools/`](tools/)，說明見 [README](README.md#重建詞庫)。
