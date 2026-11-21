package com.sportshub.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 各数据源适配器：复刻 proxy/server.js 的字段映射，输出与 Node 代理一致的前端统一结构。
 * 所有入参/出参均为 org.json 结构；失败按 Node 端约定返回空态或 null，不阻断聚合。
 */
object ProxyAdapters {

    private fun numOrNull(v: Any?): Any? = ProxyCore.toNum(v) ?: JSONObject.NULL

    /* ============================== CBA（普通 JSON 赛程/排名） ============================== */

    private fun cbaStatus(row: JSONObject): String {
        return if (row.has("HomeTeamScore") && !row.isNull("HomeTeamScore") &&
            row.has("VisitingTeamScore") && !row.isNull("VisitingTeamScore")) "done" else "upcoming"
    }

    fun mapCbaGame(row: JSONObject): JSONObject? {
        if (row.optString("ScheduleID").isBlank()) return null
        val status = cbaStatus(row)
        val date = row.optString("dates", "") as String
        val time = row.optString("time", "") as String
        val utc8 = if (time.isBlank()) date else "$date $time"
        val quarter = when {
            status == "done" -> "完场"
            row.optString("Quarter", "").toString().isNotBlank() -> "第${row.optString("Quarter")}节"
            else -> ""
        }
        val home = JSONObject().apply {
            put("id", row.optString("HomeTeamID", "").toString())
            put("name", ProxyCore.optStr(row, "HomeTeamName"))
            put("abbr", "")
            put("color", "#22D3EE")
            put("logo", ProxyCore.secureUrl(row.optString("HomeTeamSmallLogo", "").toString()))
        }
        val away = JSONObject().apply {
            put("id", row.optString("VisitingTeamID", "").toString())
            put("name", ProxyCore.optStr(row, "VisitingTeamName"))
            put("abbr", "")
            put("color", "#F472B6")
            put("logo", ProxyCore.secureUrl(row.optString("VisitingTeamSmallLogo", "").toString()))
        }
        return JSONObject().apply {
            put("id", "cba-" + row.optString("ScheduleID"))
            put("leagueId", "cba")
            put("sport", "basketball")
            put("source", "proxy")
            put("status", status)
            put("dateKey", date)
            put("localTime", time)
            put("localTz", "Asia/Shanghai")
            put("utc8", utc8)
            put("quarter", quarter)
            put("clock", "")
            put("eta", "")
            put("score", JSONObject().apply {
                put("home", if (row.has("HomeTeamScore") && !row.isNull("HomeTeamScore")) row["HomeTeamScore"] else JSONObject.NULL)
                put("away", if (row.has("VisitingTeamScore") && !row.isNull("VisitingTeamScore")) row["VisitingTeamScore"] else JSONObject.NULL)
            })
            put("home", home)
            put("away", away)
            put("plays", JSONArray())
            put("shots", JSONArray())
            put("roster", JSONArray())
        }
    }

    private fun cbaScheduleRows(games: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val home = g.optJSONObject("home") ?: JSONObject()
            val away = g.optJSONObject("away") ?: JSONObject()
            val scoreObj = g.optJSONObject("score")
            val sh = scorePart(scoreObj?.opt("home"))
            val sa = scorePart(scoreObj?.opt("away"))
            val score = if (g.optString("status") == "upcoming") "—" else "$sh : $sa"
            out.put(JSONObject().apply {
                put("date", g.optString("dateKey"))
                put("dateRaw", g.optString("dateRaw", g.optString("dateKey")))
                put("utc8", g.optString("utc8"))
                put("localTime", g.optString("localTime"))
                put("localTz", g.optString("localTz"))
                put("home", home.optString("name"))
                put("homeEn", "")
                put("away", away.optString("name"))
                put("awayEn", "")
                put("city", g.optString("city", ""))
                put("score", score)
                put("status", g.optString("status"))
            })
        }
        return out
    }

    private fun scorePart(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "—"
        else -> v.toString()
    }

    private fun cbaStandings(rows: JSONArray): JSONArray {
        data class Team(var id: String, var name: String, var logo: String, var win: Int, var lose: Int)
        val map = LinkedHashMap<String, Team>()
        fun put(id: Any?, name: String, logo: String, win: Any?, lose: Any?) {
            val idS = id?.toString() ?: ""
            if (idS.isBlank() || name.isBlank()) return
            val w = ProxyCore.toNum(win) ?: 0
            val l = ProxyCore.toNum(lose) ?: 0
            val prev = map[idS]
            if (prev == null) map[idS] = Team(idS, name, logo, w, l)
            else {
                prev.win = maxOf(prev.win, w)
                prev.lose = maxOf(prev.lose, l)
            }
        }
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            put(r.opt("HomeTeamID"), ProxyCore.optStr(r, "HomeTeamName", ""),
                ProxyCore.secureUrl(r.optString("HomeTeamSmallLogo", "")), r.opt("HomeTeamWins"), r.opt("HomeTeamLosses"))
            put(r.opt("VisitingTeamID"), ProxyCore.optStr(r, "VisitingTeamName", ""),
                ProxyCore.secureUrl(r.optString("VisitingTeamSmallLogo", "")), r.opt("VisitingTeamWins"), r.opt("VisitingTeamLosses"))
        }
        val sorted = map.values.sortedWith(compareByDescending<Team> { it.win }.thenBy { it.lose })
        val out = JSONArray()
        sorted.forEachIndexed { i, t ->
            out.put(JSONObject().apply {
                put("rank", i + 1)
                put("name", t.name)
                put("abbr", "")
                put("color", "#22D3EE")
                put("win", t.win)
                put("lose", t.lose)
                put("pct", ProxyCore.pct(t.win, t.lose))
                put("streak", "—")
            })
        }
        return out
    }

    private fun cbaRawRows(): JSONArray {
        val j = ProxyCore.cached("cba:raw", ProxyCore.CACHE_TTL) {
            ProxyCore.fetchJson(ProxyCore.UPSTREAM["cbaSchedule"]!!, "https://cbaleague.com/")
        }
        return j.optJSONArray("data") ?: JSONArray()
    }

    fun cbaGames(): JSONArray {
        val data = ProxyCore.cached("cba:games", ProxyCore.CACHE_TTL) {
            val rows = cbaRawRows()
            val arr = JSONArray()
            for (i in 0 until rows.length()) mapCbaGame(rows.getJSONObject(i))?.let { arr.put(it) }
            arr
        }
        return data
    }

    fun cbaLeague(): JSONObject {
        val rows = cbaRawRows()
        val games = JSONArray()
        for (i in 0 until rows.length()) mapCbaGame(rows.getJSONObject(i))?.let { games.put(it) }
        return JSONObject().apply {
            put("standings", cbaStandings(rows))
            put("schedule", cbaScheduleRows(games))
        }
    }

    /* ============================== WCBA ============================== */

    private fun mapWcbaGame(g: JSONObject): JSONObject? {
        val hasScore = g.has("homeScore") && !g.isNull("homeScore") && g.has("awayScore") && !g.isNull("awayScore")
        val status = if (hasScore) "done" else "upcoming"
        val date = g.optString("gameDate", "")
        val time = g.optString("startTime", "")
        val utc8 = if (time.isBlank()) date else "$date $time"
        val home = JSONObject().apply {
            put("id", g.optString("homeId", "").toString())
            put("name", ProxyCore.optStr(g, "homeName"))
            put("abbr", "")
            put("color", "#22D3EE")
            put("logo", ProxyCore.secureUrl(g.optString("homeLogo", "").toString()))
        }
        val away = JSONObject().apply {
            put("id", g.optString("awayId", "").toString())
            put("name", ProxyCore.optStr(g, "awayName"))
            put("abbr", "")
            put("color", "#F472B6")
            put("logo", ProxyCore.secureUrl(g.optString("awayLogo", "").toString()))
        }
        return JSONObject().apply {
            put("id", "wcba-" + g.optString("gameId"))
            put("leagueId", "wcba")
            put("sport", "basketball")
            put("source", "proxy")
            put("status", status)
            put("dateKey", date)
            put("localTime", time)
            put("localTz", "Asia/Shanghai")
            put("utc8", utc8)
            put("quarter", if (status == "done") "完场" else "")
            put("clock", "")
            put("eta", "")
            put("score", JSONObject().apply {
                put("home", if (g.has("homeScore") && !g.isNull("homeScore")) g["homeScore"] else JSONObject.NULL)
                put("away", if (g.has("awayScore") && !g.isNull("awayScore")) g["awayScore"] else JSONObject.NULL)
            })
            put("home", home)
            put("away", away)
            put("plays", JSONArray())
            put("shots", JSONArray())
            put("roster", JSONArray())
        }
    }

    private fun wcbaScheduleRows(games: JSONArray): JSONArray = cbaScheduleRows(games)

    private fun wcbaStandings(ranks: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until ranks.length()) {
            val t = ranks.getJSONObject(i)
            val win = ProxyCore.toNum(t.opt("wins")) ?: 0
            val lose = ProxyCore.toNum(t.opt("loses")) ?: 0
            val pct = when {
                !t.isNull("winsPct") && ProxyCore.toNum(t.opt("winsPct"))?.let { it > 0 } == true ->
                    String.format(java.util.Locale.US, "%.3f", t.optDouble("winsPct"))
                else -> ProxyCore.pct(win, lose)
            }
            out.put(JSONObject().apply {
                put("rank", ProxyCore.toNum(t.opt("rank")) ?: (i + 1))
                put("name", ProxyCore.optStr(t, "teamName"))
                put("abbr", "")
                put("color", "#22D3EE")
                put("win", win)
                put("lose", lose)
                put("pct", pct)
                put("streak", "—")
            })
        }
        return out
    }

    fun wcbaGames(): JSONArray {
        val j = ProxyCore.cached("wcba:today", ProxyCore.CACHE_TTL) {
            val r = ProxyCore.fetchJson(ProxyCore.UPSTREAM["wcbaToday"]!!, "https://www.cba.net.cn/")
            r.optJSONObject("data")?.optJSONArray("games") ?: JSONArray()
        }
        val out = JSONArray()
        for (i in 0 until j.length()) mapWcbaGame(j.getJSONObject(i))?.let { out.put(it) }
        return out
    }

    fun wcbaLeague(): JSONObject {
        val today = ProxyCore.cached("wcba:today-raw", ProxyCore.CACHE_TTL) {
            val r = ProxyCore.fetchJson(ProxyCore.UPSTREAM["wcbaToday"]!!, "https://www.cba.net.cn/")
            r.optJSONObject("data")?.optJSONArray("games") ?: JSONArray()
        }
        val rank = ProxyCore.cached("wcba:rank", ProxyCore.CACHE_TTL) {
            val r = ProxyCore.fetchJson(ProxyCore.UPSTREAM["wcbaRank"]!!, "https://www.cba.net.cn/")
            r.optJSONObject("data")?.optJSONArray("ranks") ?: JSONArray()
        }
        val games = JSONArray()
        for (i in 0 until today.length()) mapWcbaGame(today.getJSONObject(i))?.let { games.put(it) }
        return JSONObject().apply {
            put("standings", wcbaStandings(rank))
            put("schedule", wcbaScheduleRows(games))
        }
    }
}
