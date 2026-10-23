package com.p3dro.scorpio.ime.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.util.zip.GZIPInputStream

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

    data class ManifestPack(
        val id: String,
        val group: String,
        val name: String,
        val version: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val minAppVersion: Int
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

    data class Result(val updated: List<Pair<String, String>>, val failed: List<String>, val error: String? = null)

    fun parseManifest(json: String): List<ManifestPack> {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) > SUPPORTED_SCHEMA) return emptyList()
        val arr = root.optJSONArray("packs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ManifestPack(
                o.getString("id"), o.optString("group", "words"), o.optString("name", o.getString("id")),
                o.getString("version"), o.optLong("size", -1), o.getString("sha256"), o.getString("url"),
                o.optInt("minAppVersion", 0)
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

    private fun appVersion(context: Context): Long = try {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    /** 下載到 part 檔並驗證大小與 sha256；不符就丟例外 */
    private fun download(url: String, size: Long, sha: String, part: File) {
        fetch(url, part)
        if ((size >= 0 && part.length() != size) || !sha256(part).equals(sha, ignoreCase = true)) {
            part.delete()
            throw java.io.IOException("檔案校驗不符")
        }
    }

    /** 下載並套用一個資料包（開關打開時、或有新版時） */
    private fun installPack(context: Context, dict: ScorpioDictManager, p: ManifestPack): Boolean {
        val part = File(dict.packDir, "${p.id}.part")
        return try {
            download(p.url, p.size, p.sha256, part)
            val target = File(dict.packDir, "${p.id}.db.gz")
            if (!part.renameTo(target)) part.copyTo(target, overwrite = true)
            dict.applyPack(target) != null
        } catch (e: Exception) {
            Log.w(TAG, "更新 ${p.id} 失敗", e)
            false
        } finally {
            part.delete()
        }
    }

    /** 開關打開時呼叫：線上有比手機上新的版本就馬上下載套用；連不上就沿用手機上的資料 */
    suspend fun ensureLatest(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val dict = ScorpioDictManager.getInstance(context.applicationContext)
        val p = try {
            parseManifest(fetch(MANIFEST_URL) ?: "").firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        } ?: return@withContext false
        if (p.minAppVersion > appVersion(context) || PackVersion.compare(p.version, dict.installedPackVersion(p.id, p.group)) <= 0) {
            return@withContext false
        }
        installPack(context, dict, p)
    }

    /** 設定頁列出可以安裝的外掛 */
    suspend fun availablePlugins(context: Context): List<ManifestPlugin>? = withContext(Dispatchers.IO) {
        try {
            val v = appVersion(context)
            parsePlugins(fetch(MANIFEST_URL) ?: "").filter { it.minAppVersion <= v }
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

    /** 需要更新的資料包：App 版本夠新、而且比目前生效的版本新 */
    fun pending(manifest: List<ManifestPack>, appVersionCode: Long, installed: (ManifestPack) -> String) =
        manifest.filter { it.minAppVersion <= appVersionCode && PackVersion.compare(it.version, installed(it)) > 0 }

    private fun fetch(url: String, out: File? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ScorpioIME")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return if (out != null) {
                conn.inputStream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
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

    /** 讀資料包的 id、版本、group（不套用） */
    fun readMeta(gz: File, cacheDir: File): Triple<String, String, String>? {
        val tmp = File(cacheDir, "meta-${System.nanoTime()}.db")
        return try {
            GZIPInputStream(gz.inputStream()).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { pack ->
                val meta = HashMap<String, String>()
                pack.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
                Triple(meta["id"] ?: return null, meta["version"] ?: return null, meta["group"] ?: "words")
            }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    suspend fun checkAndUpdate(context: Context): Result = withContext(Dispatchers.IO) {
        val dict = ScorpioDictManager.getInstance(context.applicationContext)
        val appVersion = appVersion(context)
        val json: String
        val manifest = try {
            json = fetch(MANIFEST_URL) ?: ""
            parseManifest(json)
        } catch (e: Exception) {
            Log.w(TAG, "讀取更新清單失敗", e)
            return@withContext Result(emptyList(), emptyList(), "連不上更新伺服器")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        val updated = ArrayList<Pair<String, String>>()
        val failed = ArrayList<String>()
        // 只更新有開的詞庫（開關就是安裝）
        val enabled = manifest.filter { dict.isPackEnabled(it.id, it.group) }
        for (p in pending(enabled, appVersion) { dict.installedPackVersion(it.id, it.group) }) {
            if (installPack(context, dict, p)) updated.add(p.name to p.version) else failed.add(p.name)
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
        val auto = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_AUTO, true)
        if (!auto) {
            wm.cancelUniqueWork(WORK_NAME)
            return
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
