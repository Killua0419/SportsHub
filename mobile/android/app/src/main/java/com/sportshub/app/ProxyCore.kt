package com.sportshub.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生内嵌本地代理核心层（复刻 PC 端 proxy/server.js，零第三方依赖）。
 *
 * 说明：前端页面经 proxyBase() 拼到 http://127.0.0.1:8787/proxy/ 路径；
 * WebView 在 shouldInterceptRequest 中拦截该回环地址，由本层在后台线程代为请求上游公开源，
 * 解析成与 Node 代理完全一致的前端统一结构后回包。
 */
object ProxyCore {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.234 Safari/537.36"

    val UPSTREAM = mapOf(
        "cbaSchedule" to "https://server.cbaleague.com/home/home_schedules",
        "cbaData" to "https://data-server.cbaleague.com",
        "wcbaToday" to "https://www.cba.net.cn/datahub/cbamatch/home/todaymatches?competitionId=56",
        "wcbaRank" to "https://www.cba.net.cn/datahub/cbamatch/home/matchoutrank?competitionId=56&seasonId=2286",
        "txFeeds" to "https://matchweb.sports.qq.com/feeds/type/list",
        "txStat" to "https://matchweb.sports.qq.com/kbs/matchStat",
        "txDetail" to "https://matchweb.sports.qq.com/kbs/matchDetail",
        "txRank" to "https://matchweb.sports.qq.com/team/rank",
        "txRankings" to "https://matchweb.sports.qq.com/match/api/v2/statistic/rankings",
        "dqData" to "https://www.dongqiudi.com/data",
        "zbRank" to "https://data.zhibo8.cc/manage/public/app.php?_url=/basketball/paihang",
        "zbTop" to "https://data.zhibo8.cc/manage/public/app.php?_url=/nba/league_top_data",
        "wstMatches" to "https://matches.snooker.web.gc.wstservices.co.uk/v2",
        "wstRankings" to "https://rankings.snooker.web.gc.wstservices.co.uk/v2",
        "wstImg" to "https://images.gc.wstservices.co.uk/fit-in/700x700/"
    )

    /** 腾讯：前端 leagueId → competitionId + 运动类型；cid 为空表示该赛事腾讯侧暂无公开赛程流 */
    data class TxCfg(val cid: String, val sport: String)

    val TENCENT_LEAGUES = mapOf(
        "nba" to TxCfg("100000", "basketball"),
        "cba" to TxCfg("100009", "basketball"),
        "epl" to TxCfg("10082", "football"),
        "laliga" to TxCfg("10120", "football"),
        "seriea" to TxCfg("10108", "football"),
        "bundes" to TxCfg("10129", "football"),
        "csl" to TxCfg("10542", "football"),
        "ucl" to TxCfg("10046", "football"),
        "jleague" to TxCfg("", "football"),
        "atp" to TxCfg("100309", "tennis"),
        "wta" to TxCfg("", "tennis"),
        "usopen" to TxCfg("", "tennis"),
        "wimbledon" to TxCfg("", "tennis"),
        "frenchopen" to TxCfg("", "tennis")
    )

    /** 懂球帝 leagueId → 站点 cid */
    val DQ_CID = mapOf(
        "epl" to 4, "laliga" to 3, "seriea" to 9, "bundes" to 5,
        "csl" to 43, "ucl" to 6, "worldcup" to 61
    )

    /** 懂球帝球员榜 type → 前端排名 key */
    val DQ_PERSON_TYPE = mapOf("goals" to "goals", "assists" to "assists", "shots" to "shots")

    val ZB_BASKET = setOf("nba")

    const val CBA_DATA_KEY = "uVayqL4ONKjFbVzQ"

    const val CACHE_TTL = 30L * 1000L

    /* ----------------------------- 内存短缓存 ----------------------------- */

    private data class CacheEntry(val at: Long, val value: Any)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> cached(key: String, ttl: Long, loader: () -> T): T {
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.at < ttl) {
            return hit.value as T
        }
        val value = loader()
        cache[key] = CacheEntry(System.currentTimeMillis(), value as Any)
        return value
    }

    /* ----------------------------- HTTP ----------------------------- */

    data class HttpResult(val status: Int, val body: String, val contentType: String?)

    /** 通用 GET；referer 缺省走 cba.net.cn（与 Node 端一致）。 */
    fun httpGet(url: String, referer: String? = null, accept: String = "application/json, text/plain, */*", timeoutMs: Int = 15000): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer ?: "https://www.cba.net.cn/")
            setRequestProperty("Accept", accept)
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            return HttpResult(code, text, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }

    /** 通用 JSON GET，非 2xx 抛异常。 */
    fun fetchJson(url: String, referer: String? = null): JSONObject {
        val r = httpGet(url, referer)
        if (r.status !in 200..299) throw ProxyException("upstream HTTP ${r.status}")
        return JSONObject(r.body)
    }

    /** HTML GET。 */
    fun fetchHtml(url: String, referer: String? = null): String {
        val r = httpGet(url, referer ?: "https://www.dongqiudi.com/", "text/html,*/*")
        if (r.status !in 200..299) throw ProxyException("upstream HTTP ${r.status}")
        return r.body
    }

    /** CBA data-server POST（AES 解密在 decryptCba 内完成）；返回 JSONObject 或 JSONArray。 */
    fun cbaRequest(method: String, path: String, body: JSONObject? = null): Any? {
        val conn = (URL(UPSTREAM["cbaData"] + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.cbaleague.com/")
            setRequestProperty("Accept", "application/json, */*")
            setRequestProperty("Content-Type", "application/json")
            doOutput = body != null
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw ProxyException("cba upstream HTTP $code")
            return decryptCba(text)
        } finally {
            conn.disconnect()
        }
    }

    /* ----------------------------- 解密 / 解析 ----------------------------- */

    /** AES-128-ECB / PKCS5 解密 base64 响应；失败回退纯 JSON（对象或数组）。 */
    fun decryptCba(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val plain = try {
            val key = SecretKeySpec(CBA_DATA_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            cipher.doFinal(Base64.decode(text, Base64.DEFAULT)).toString(StandardCharsets.UTF_8)
        } catch (_: Throwable) { text }
        return try { JSONObject(plain) } catch (_: Throwable) {
            try { JSONArray(plain) } catch (_: Throwable) { null }
        }
    }

    /** 剥离 JSONP 包裹 callback({...})；纯 JSON 直接解析。 */
    fun parseMaybeJsonp(text: String?): JSONObject? {
        var t = (text ?: "").trim()
        val m = Regex("^[A-Za-z_$][\\w$]*\\(([\\s\\S]*)\\)\\s*;?$").matchEntire(t)
        if (m != null) t = m.groupValues[1]
        return try { JSONObject(t) } catch (_: Throwable) { null }
    }

    /** 协议相对地址补 https。 */
    fun secureUrl(u: String?): String {
        if (u.isNullOrEmpty()) return ""
        return if (u.startsWith("//")) "https:$u" else u
    }

    fun pct(win: Any?, lose: Any?): String {
        val w = toNum(win) ?: 0
        val l = toNum(lose) ?: 0
        val total = w + l
        if (total == 0) return "—"
        val v = w.toDouble() / total
        return String.format(Locale.US, "%.3f", v).trimEnd('0')
    }

    /* ----------------------------- 取值辅助 ----------------------------- */

    fun toNum(s: Any?): Int? = when (s) {
        null -> null
        is Int -> s
        is Long -> s.toInt()
        is Double -> if (s.isNaN()) null else s.toInt()
        is String -> s.trim().toIntOrNull()
        else -> s.toString().trim().toIntOrNull()
    }

    fun optStr(j: JSONObject?, key: String, def: String = "—"): String {
        val v = j?.optString(key, "") ?: ""
        return if (v.isBlank()) def else v
    }

    /** 北京日期偏移 offset 天，返回 yyyy-MM-dd（UTC+8）。 */
    fun txBjDate(offset: Int): String {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.add(java.util.Calendar.DAY_OF_YEAR, offset)
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return f.format(cal.time)
    }

    /**
     * 英国本地时间(Europe/London) → 北京时间；简化 DST：4–10 月 BST(UTC+1)，其余 GMT(UTC+0)。
     * 返回三元组(日期 yyyy-MM-dd, 北京 HH:mm, ISO utc8)；解析失败全空串。
     */
    fun wstLondonToBj(dt: String?): Triple<String, String, String> {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").matchEntire(dt ?: "")
            ?: return Triple("", "", "")
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val da = m.groupValues[3].toInt()
        val h = m.groupValues[4].toInt()
        val mi = m.groupValues[5].toInt()
        val s = m.groupValues[6].toInt()
        val dst = mo in 4..10
        // UTC 毫秒（伦敦本地墙钟 → UTC：BST 减 1h，GMT 不减）
        val utcMs = utcMillis(y, mo - 1, da, h, mi, s) - if (dst) 3600_000L else 0L
        val bjMs = utcMs + 8L * 3600_000L
        val bjCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        bjCal.timeInMillis = bjMs
        val dateKey = String.format(Locale.US, "%04d-%02d-%02d", bjCal.get(java.util.Calendar.YEAR),
            bjCal.get(java.util.Calendar.MONTH) + 1, bjCal.get(java.util.Calendar.DAY_OF_MONTH))
        val localTime = String.format(Locale.US, "%02d:%02d", bjCal.get(java.util.Calendar.HOUR_OF_DAY),
            bjCal.get(java.util.Calendar.MINUTE))
        val iso = isoUtc(bjMs)
        return Triple(dateKey, localTime, iso)
    }

    private fun utcMillis(y: Int, mo: Int, da: Int, h: Int, mi: Int, s: Int): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(y, mo, da, h, mi, s)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun isoUtc(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }
}

class ProxyException(message: String) : Exception(message)
