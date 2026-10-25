package com.p3dro.scorpio.ime.engine

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 詞庫線上更新：讀資料 repo 的 manifest.json，比 App 裡生效的版本新的資料包就下載、驗證 sha256、套用
 * 資料 repo：https://github.com/ScorpionSkypoo/scorpio-ime-data
 */
object PackUpdater {

    private const val TAG = "PackUpdater"
    const val MANIFEST_URL = "https://raw.githubusercontent.com/ScorpionSkypoo/scorpio-ime-data/main/manifest.json"
    private const val SUPPORTED_SCHEMA = 1
    private const val WORK_NAME = "scorpio_pack_update"
    const val PREFS = "scorpio_pack_update"
    const val PREF_AUTO = "auto_update"
    const val PREF_LAST_CHECK = "last_check"
    /** 從舊版（SQLite 詞庫）升級上來：舊的下載資料包都不在了，要馬上檢查一次，把比內建新的資料包重新下載 */
    const val PREF_REFETCH = "refetch_after_upgrade"

    data class ManifestPack(
        val id: String,
        val group: String,
        val name: String,
        val version: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val minAppVersion: Int,
        val description: String = "",
        val source: String = "",
        val license: String = "",
        /** false＝App 不內建、只能線上安裝 */
        val bundled: Boolean = true,
        val rows: Int = 0,
        /** 設定頁的分組：basic、places、symbols、terms、bigram */
        val category: String = "",
        /** App 內建、一律使用的資料（主詞庫、Emoji、英文、倉頡）：不在設定頁另列開關，有新版就自動更新 */
        val system: Boolean = false
    )

    data class ManifestPlugin(
        val id: String,
        val name: String,
        val icon: String,
        val description: String,
        val version: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val minAppVersion: Int
    )

    /** 線上的鍵盤版面（layouts/）：requires 是安裝時要一起下載的資料包 */
    data class ManifestLayout(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val minAppVersion: Int,
        val requires: List<String> = emptyList(),
        /** 版面要用到的資料包下載大小合計（設定頁顯示用） */
        val requiresSize: Long = 0,
        val language: String = "zh-TW",
        val languageName: String = "中文（台灣）"
    )

    /** 線上的鍵盤設定（config/）：見 ImeConfig */
    data class ManifestConfig(val id: String, val version: String, val size: Long, val sha256: String, val url: String, val minAppVersion: Int)

    fun parseConfigs(json: String): List<ManifestConfig> {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) > SUPPORTED_SCHEMA) return emptyList()
        val arr = root.optJSONArray("configs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ManifestConfig(o.getString("id"), o.getString("version"), o.optLong("size", -1), o.getString("sha256"), o.getString("url"), o.optInt("minAppVersion", 0))
        }
    }

    data class Result(val updated: List<Pair<String, String>>, val failed: List<String>, val error: String? = null)

    fun parseManifest(json: String): List<ManifestPack> {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) > SUPPORTED_SCHEMA) return emptyList()
        val arr = root.optJSONArray("packs") ?: return emptyList()
        return (0 until arr.length()).map { i -> arr.getJSONObject(i) }
            // 舊格式（SQLite，.db.gz）的資料包這版 App 讀不了，資料 repo 重建成 SCD（.scd.gz）之前先不列
            .filter { it.optString("url").endsWith(".scd.gz") }
            .map { o ->
            ManifestPack(
                o.getString("id"), o.optString("group", "words"), o.optString("name", o.getString("id")),
                o.getString("version"), o.optLong("size", -1), o.getString("sha256"), o.getString("url"),
                o.optInt("minAppVersion", 0), o.optString("description", ""), o.optString("source", ""),
                o.optString("license", ""), o.optBoolean("bundled", true), o.optInt("rows", 0),
                o.optString("category", ""), o.optBoolean("system", false)
            )
        }
    }

    fun parsePlugins(json: String): List<ManifestPlugin> {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) > SUPPORTED_SCHEMA) return emptyList()
        val arr = root.optJSONArray("plugins") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ManifestPlugin(
                o.getString("id"), o.optString("name", o.getString("id")), o.optString("icon", ""),
                o.optString("description", ""), o.getString("version"), o.optLong("size", -1),
                o.getString("sha256"), o.getString("url"), o.optInt("minAppVersion", 0)
            )
        }
    }

    fun parseLayouts(json: String): List<ManifestLayout> {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) > SUPPORTED_SCHEMA) return emptyList()
        val sizes = parseManifest(json).associate { it.id to it.size }
        val arr = root.optJSONArray("layouts") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val requires = o.optJSONArray("requires")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            ManifestLayout(
                o.getString("id"), o.optString("name", o.getString("id")), o.optString("description", ""),
                o.getString("version"), o.optLong("size", -1), o.getString("sha256"), o.getString("url"),
                o.optInt("minAppVersion", 0), requires, requires.sumOf { sizes[it]?.coerceAtLeast(0) ?: 0 },
                o.optString("language").ifEmpty { "zh-TW" }, o.optString("languageName").ifEmpty { "中文（台灣）" }
            )
        }
    }

    private fun appVersion(context: Context): Long = try {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    /** 下載到 part 檔並驗證大小與 sha256；不符就丟例外 */
    private fun download(url: String, size: Long, sha: String, part: File, onProgress: ((Float) -> Unit)? = null) {
        fetch(url, part, size, onProgress)
        if ((size >= 0 && part.length() != size) || !sha256(part).equals(sha, ignoreCase = true)) {
            part.delete()
            throw java.io.IOException("檔案校驗不符")
        }
    }

    /** 下載並套用一個資料包（開關打開時、或有新版時） */
    /** onProgress：下載進度 0～1；下載完、開始套用時回報 -1（不確定進度） */
    private fun installPack(context: Context, dict: ScorpioDictManager, p: ManifestPack, onProgress: ((Float) -> Unit)? = null): Boolean {
        // 下載到暫存檔，驗證通過後交給詞庫管理員解壓、放進詞庫資料夾並重新載入
        val part = File(context.cacheDir, "pack-${p.id}.part")
        return try {
            download(p.url, p.size, p.sha256, part, onProgress)
            onProgress?.invoke(-1f)
            dict.applyPack(part) != null
        } catch (e: Exception) {
            Log.w(TAG, "更新 ${p.id} 失敗", e)
            false
        } finally {
            part.delete()
        }
    }

    /** 開關打開時呼叫：線上有比手機上新的版本就馬上下載套用；連不上就沿用手機上的資料 */
    suspend fun ensureLatest(context: Context, id: String, onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val dict = ScorpioDictManager.getInstance(context.applicationContext)
        val p = try {
            parseManifest(fetchManifest(context)).firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        } ?: return@withContext false
        if (p.minAppVersion > appVersion(context) || PackVersion.compare(p.version, dict.installedPackVersion(p.id, p.group)) <= 0) {
            return@withContext false
        }
        installPack(context, dict, p, onProgress)
    }

    /** 設定頁列出可以安裝的外掛 */
    suspend fun availablePlugins(context: Context): List<ManifestPlugin>? = withContext(Dispatchers.IO) {
        try {
            val v = appVersion(context)
            parsePlugins(fetchManifest(context)).filter { it.minAppVersion <= v }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun installPlugin(context: Context, p: ManifestPlugin): Boolean = withContext(Dispatchers.IO) {
        val part = File(context.cacheDir, "plugin-${p.id}.part")
        try {
            download(p.url, p.size, p.sha256, part)
            Plugins.install(context, part.readText()) != null
        } catch (e: Exception) {
            Log.w(TAG, "安裝外掛 ${p.id} 失敗", e)
            false
        } finally {
            part.delete()
        }
    }

    /** 設定頁列出可以安裝的鍵盤版面；連不上回傳 null */
    suspend fun availableLayouts(context: Context): List<ManifestLayout>? = withContext(Dispatchers.IO) {
        try {
            val v = appVersion(context)
            parseLayouts(fetchManifest(context)).filter { it.minAppVersion <= v }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 安裝或更新一個鍵盤版面：先下載它要用的資料包（倉頡碼表等），都好了才放上版面，
     * 不會出現「版面裝好了、碼表還沒到」打不出字的情況
     */
    suspend fun installLayout(context: Context, l: ManifestLayout, onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val dict = ScorpioDictManager.getInstance(context.applicationContext)
        val part = File(context.cacheDir, "layout-${l.id}.part")
        try {
            download(l.url, l.size, l.sha256, part)
            val json = part.readText()
            val method = ChineseMethod.parse(json) ?: return@withContext false
            if (method.requires.isNotEmpty()) {
                val packs = parseManifest(fetchManifest(context)).associateBy { it.id }
                for (id in method.requires) {
                    val p = packs[id] ?: return@withContext false
                    if (PackVersion.compare(p.version, dict.installedPackVersion(p.id, p.group)) > 0 &&
                        !installPack(context, dict, p, onProgress)
                    ) return@withContext false
                }
            }
            ChineseMethod.install(context, ChineseMethod.prefs(context), json) != null
        } catch (e: Exception) {
            Log.w(TAG, "安裝版面 ${l.id} 失敗", e)
            false
        } finally {
            part.delete()
        }
    }

    /** 需要更新的資料包：App 版本夠新、而且比目前生效的版本新 */
    fun pending(manifest: List<ManifestPack>, appVersionCode: Long, installed: (ManifestPack) -> String) =
        manifest.filter { it.minAppVersion <= appVersionCode && PackVersion.compare(it.version, installed(it)) > 0 }

    private const val PREF_MANIFEST = "manifest_cache"

    /** 上次讀到的 manifest（設定頁列出還沒安裝的資料包用；沒網路時也看得到） */
    fun cachedManifest(context: Context): List<ManifestPack> = try {
        parseManifest(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MANIFEST, null) ?: "{}")
    } catch (e: Exception) {
        emptyList()
    }

    private fun fetchManifest(context: Context): String {
        val json = fetch(MANIFEST_URL) ?: ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_MANIFEST, json).apply()
        return json
    }

    /** 重新讀一次 manifest（設定頁打開時），回傳還沒安裝、App 版本夠新的資料包 */
    suspend fun refreshManifest(context: Context): List<ManifestPack> = withContext(Dispatchers.IO) {
        try {
            parseManifest(fetchManifest(context))
        } catch (e: Exception) {
            cachedManifest(context)
        }.filter { it.minAppVersion <= appVersion(context) }
    }

    private fun fetch(url: String, out: File? = null, total: Long = -1, onProgress: ((Float) -> Unit)? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ScorpioIME")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return if (out != null) {
                val size = if (total > 0) total else conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { o ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (size > 0) onProgress?.invoke((done.toFloat() / size).coerceAtMost(1f))
                        }
                    }
                }
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun checkAndUpdate(context: Context): Result = withContext(Dispatchers.IO) {
        val dict = ScorpioDictManager.getInstance(context.applicationContext)
        val appVersion = appVersion(context)
        val json: String
        val manifest = try {
            json = fetchManifest(context)
            parseManifest(json)
        } catch (e: Exception) {
            Log.w(TAG, "讀取更新清單失敗", e)
            return@withContext Result(emptyList(), emptyList(), "連不上更新伺服器")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        val updated = ArrayList<Pair<String, String>>()
        val failed = ArrayList<String>()
        // 只更新有開的詞庫（開關就是安裝）；App 內建的資料（主詞庫、Emoji…）一律更新
        val enabled = manifest.filter { it.system || dict.isPackEnabled(it.id, it.group) }
        for (p in pending(enabled, appVersion) { dict.installedPackVersion(it.id, it.group) }) {
            if (installPack(context, dict, p)) updated.add(p.name to p.version) else failed.add(p.name)
        }
        // 裝著的鍵盤版面有新版就更新；舊版 App 內建、升級後不在手機上的啟用版面重新安裝
        val prefs = ChineseMethod.prefs(context)
        val missing = ChineseMethod.missingEnabled(context, prefs).toSet()
        for (l in parseLayouts(json)) {
            if (l.minAppVersion > appVersion) continue
            val have = ChineseMethod.installedVersion(context, l.id)
            if ((have != null && PackVersion.compare(l.version, have) > 0) || (have == null && l.id in missing)) {
                if (installLayout(context, l)) updated.add(l.name to l.version) else failed.add(l.name)
            }
        }
        // 鍵盤設定（簡拼展開、顏文字對照、讀音混用預設、符號鍵盤）有新版就更新
        for (c in parseConfigs(json)) {
            if (c.id != ImeConfig.ID || c.minAppVersion > appVersion || PackVersion.compare(c.version, ImeConfig.installedVersion(context)) <= 0) continue
            val part = File(context.cacheDir, "config-${c.id}.part")
            try {
                download(c.url, c.size, c.sha256, part)
                if (ImeConfig.install(context, part.readText())) {
                    dict.onConfigChanged()
                    updated.add("鍵盤設定" to c.version)
                } else failed.add("鍵盤設定")
            } catch (e: Exception) {
                Log.w(TAG, "更新鍵盤設定失敗", e)
                failed.add("鍵盤設定")
            } finally {
                part.delete()
            }
        }
        // 已安裝的外掛有新版就更新
        for (p in parsePlugins(json)) {
            val have = Plugins.installedVersion(context, p.id) ?: continue
            if (p.minAppVersion <= appVersion && PackVersion.compare(p.version, have) > 0) {
                if (installPlugin(context, p)) updated.add(p.name to p.version) else failed.add(p.name)
            }
        }
        Result(updated, failed)
    }

    /** 依設定排程每天檢查一次（只在不計費網路，例如 Wi-Fi） */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(PREF_AUTO, true)
        if (!auto) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        if (prefs.getBoolean(PREF_REFETCH, false)) {
            prefs.edit().putBoolean(PREF_REFETCH, false).apply()
            wm.enqueue(
                androidx.work.OneTimeWorkRequestBuilder<Worker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build()
            )
        }
        // 啟用了卻不在手機上的版面（從內建版面的舊版升級）：有網路就馬上裝回來（版面很小，不必等 Wi-Fi）
        if (ChineseMethod.missingEnabled(context, ChineseMethod.prefs(context)).isNotEmpty()) {
            wm.enqueueUniqueWork(
                "${WORK_NAME}_layouts", androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<Worker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
        val req = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            val r = checkAndUpdate(applicationContext)
            return if (r.error != null) androidx.work.ListenableWorker.Result.retry() else androidx.work.ListenableWorker.Result.success()
        }
    }
}
