package com.p3dro.scorpio.ime.engine.scd

import android.content.Context
import android.util.Log
import com.p3dro.scorpio.ime.engine.PackVersion
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 手機上所有的 SCD 檔：
 * - App 內建：APK 資產 scd/<id>.scd（不壓縮，直接 mmap，不必解出來）
 * - 線上下載的資料包：files/scd/<id>.scd（下載的 .scd.gz 解壓後放這裡，App 改版也不會不見）
 * - 使用者匯入的詞庫：files/scd/import_<編號>.scd（在手機上由 [ScdWriter] 產生）
 * 同一個 id 內建與下載的都有時，用版本新的那個
 */
class DictFiles(private val context: Context) {

    val dir: File = File(context.filesDir, "scd").apply { mkdirs() }

    fun importFile(id: Long) = File(dir, "import_$id.scd")

    /** 依 id 列出生效的檔（下載的比內建舊就刪掉） */
    fun load(): Map<String, ScdFile> {
        val out = LinkedHashMap<String, ScdFile>()
        for (name in context.assets.list("scd").orEmpty().sorted()) {
            if (!name.endsWith(".scd")) continue
            try {
                context.assets.openFd("scd/$name").use { afd ->
                    val f = ScdFile.open(afd.fileDescriptor, afd.startOffset, afd.length)
                    out[f.id] = f
                }
            } catch (e: Exception) {
                Log.e(TAG, "內建詞庫 $name 讀取失敗", e)
            }
        }
        for (file in dir.listFiles { f -> f.name.endsWith(".scd") }.orEmpty().sortedBy { it.name }) {
            val f = try {
                ScdFile.open(file)
            } catch (e: Exception) {
                Log.e(TAG, "詞庫檔 ${file.name} 損壞，刪除", e)
                file.delete()
                continue
            }
            val cur = out[f.id]
            if (cur == null || PackVersion.compare(f.version, cur.version) > 0) {
                out[f.id] = f
            } else {
                // App 改版內建了更新的版本：下載的舊版不用了
                file.delete()
            }
        }
        return out
    }

    /** 安裝下載的資料包（gzip 壓縮的 SCD）：解壓、檢查格式後放到 files/scd/<id>.scd，回傳檔案；格式不對回傳 null */
    fun installPack(gz: File): ScdFile? {
        val tmp = File(dir, "pack-${System.nanoTime()}.tmp")
        try {
            GZIPInputStream(gz.inputStream()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val id = ScdFile.open(tmp).id
            if (id.isEmpty() || id.startsWith("import:") || id.contains('/')) return null
            val target = File(dir, "$id.scd")
            // 舊檔可能正被 mmap 著：改名覆蓋時舊的對應仍然有效，下次重新載入就換成新檔
            if (!tmp.renameTo(target)) return null
            return ScdFile.open(target)
        } catch (e: Exception) {
            Log.e(TAG, "安裝資料包失敗", e)
            return null
        } finally {
            tmp.delete()
        }
    }

    fun deletePack(id: String) {
        File(dir, "$id.scd").delete()
    }

    /** 把匯入的詞庫寫成 SCD（先寫暫存檔再改名，寫到一半不會留下壞檔） */
    fun writeImport(id: Long, bytes: ByteArray) {
        val tmp = File(dir, "import_$id.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(importFile(id))) {
            tmp.delete()
            throw java.io.IOException("寫入匯入詞庫失敗")
        }
    }

    companion object {
        private const val TAG = "DictFiles"
    }
}
