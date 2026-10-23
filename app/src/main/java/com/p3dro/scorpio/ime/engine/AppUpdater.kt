package com.p3dro.scorpio.ime.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 檢查 App 更新：讀 GitHub Releases 最新一版（tag v1.2.0、附 APK），比手機上的新就下載、交給系統安裝
 * repo 還沒公開時 GitHub 會回 404，這時只能自己建置或從 Releases 手動下載
 */
object AppUpdater {

    private const val API = "https://api.github.com/repos/ScorpionSkypoo/ScorpionBopomofo/releases/latest"
    const val RELEASES_PAGE = "https://github.com/ScorpionSkypoo/ScorpionBopomofo/releases"
    private const val PREFS = "scorpio_app_update"

    data class Release(val tag: String, val notes: String, val apkUrl: String?, val apkSize: Long, val page: String)

    sealed class Check {
        data class Available(val release: Release) : Check()
        object UpToDate : Check()
        /** repo 還沒公開或還沒有 Release */
        object NoRelease : Check()
        data class Failed(val reason: String) : Check()
    }

    /** 版本號 → 數字段（v1.2.0 → [1,2,0]；git describe 的 1.0.0-9-gabc → [1,0,0]） */
    private fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }

    /**
     * Release 的 tag 比手機上的版本新嗎？手機版本是 git describe 產生的：1.0.0 是剛好在 tag 上，
     * 1.0.0-9-gabc 是 tag 之後又多 9 個 commit（比 v1.0.0 新，但比 v1.0.1 舊）
     */
    fun isNewer(tag: String, installed: String): Boolean {
        val a = parts(tag)
        val b = parts(installed)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return false
    }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 上次檢查發現的新版本（設定首頁顯示提示用） */
    fun cachedNewer(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("newer", null)
            ?.takeIf { isNewer(it, installedVersion(context)) }

    fun lastCheck(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0)

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ScorpioIME")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val code = conn.responseCode
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            if (code == 404) return@withContext Check.NoRelease
            if (code != 200) return@withContext Check.Failed("GitHub 回應 $code")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a
            }
            val r = Release(
                o.getString("tag_name"), o.optString("body", ""), apk?.optString("browser_download_url"),
                apk?.optLong("size", -1) ?: -1, o.optString("html_url", RELEASES_PAGE)
            )
            if (isNewer(r.tag, installedVersion(context))) {
                prefs.edit().putString("newer", r.tag).apply()
                Check.Available(r)
            } else {
                prefs.edit().remove("newer").apply()
                Check.UpToDate
            }
        } catch (e: Exception) {
            Check.Failed("連不上 GitHub")
        } finally {
            conn.disconnect()
        }
    }

    /** 下載 APK 到快取，回傳檔案；失敗回傳 null */
    suspend fun download(context: Context, r: Release): File? = withContext(Dispatchers.IO) {
        val url = r.apkUrl ?: return@withContext null
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ScorpioIME-${r.tag}.apk")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "ScorpioIME")
            conn.inputStream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            conn.disconnect()
            if (r.apkSize > 0 && out.length() != r.apkSize) null else out
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    /** 交給系統安裝；還沒允許「安裝未知應用程式」就先帶到那個設定頁，回傳 false */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
