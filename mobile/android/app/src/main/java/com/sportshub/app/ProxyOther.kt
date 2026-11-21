package com.sportshub.app

import org.json.JSONArray
import org.json.JSONObject

/** 直播吧 / 斯诺克 WST / 懂球帝适配器。 */
object ProxyOther {

    /* ============================== 直播吧（JSON） ============================== */

    private val ZB_RANK_MAP = mapOf("得分" to "pts", "篮板" to "reb", "助攻" to "ast", "抢断" to "stl", "盖帽" to "blk")

    private fun zbStandings(j: JSONObject): JSONArray {
        val groups = j.optJSONObject("data")?.optJSONObject("paihang")?.optJSONArray("list") ?: JSONArray()
        val out = JSONArray()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val teams = group.optJSONArray("teams") ?: continue
            for (t in 0 until teams.length()) {
                val team = teams.optJSONObject(t) ?: continue
                out.put(JSONObject().apply {
                    put("rank", ProxyCore.toNum(team.opt("排名")) ?: 0)
                    put("name", ProxyCore.optStr(team, "球队"))
                    put("abbr", "")
                    put("color", "#22D3EE")
                    put("win", ProxyCore.toNum(team.opt("胜")) ?: 0)
                    put("lose", ProxyCore.toNum(team.opt("负")) ?: 0)
                    put("pct", if (!team.isNull("胜率")) team.optString("胜率", "") + "%" else "—")
                    put("streak", team.optString("连续成绩", "—"))
                    put("group", group.optString("displayConference", ""))
                })
            }
        }
        return out
    }

    private fun zbRankings(j: JSONObject): JSONObject {
        val leader = j.optJSONObject("data")?.optJSONObject("dailyplayerleader") ?: JSONObject()
        val data = leader.optJSONObject("data") ?: JSONObject()
        val out = JSONObject()
        data.keys().forEach { zh ->
            val k = ZB_RANK_MAP[zh] ?: return@forEach
            val arr = data.optJSONArray(zh) ?: JSONArray()
            val list = JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = ProxyCore.optStr(p, "playerName")
                if (name == "—") continue
                val raw = p.opt("value")?.toString() ?: "—"
                val valStr = Regex("[^\\d.\\-]").replace(raw, "").ifBlank { "—" }
                list.put(JSONObject().apply {
                    put("name", name); put("en", ""); put("team", p.optString("teamName", "")); put("val", valStr)
                })
            }
            out.put(k, list)
        }
        return out
    }

    fun zbLeague(lid: String): JSONObject {
        if (lid !in ProxyCore.ZB_BASKET) return JSONObject().apply {
            put("standings", JSONArray()); put("schedule", JSONArray()); put("rankings", JSONObject())
        }
        var standings = JSONArray()
        val rankings = JSONObject()
        try {
            val j = ProxyCore.cached("zb:rank", ProxyCore.CACHE_TTL) {
                ProxyCore.fetchJson(ProxyCore.UPSTREAM["zbRank"]!!, "https://www.zhibo8.cc/")
            }
            standings = zbStandings(j)
        } catch (_: Throwable) { }
        try {
            val j = ProxyCore.cached("zb:top", ProxyCore.CACHE_TTL) {
                ProxyCore.fetchJson(ProxyCore.UPSTREAM["zbTop"]!!, "https://www.zhibo8.cc/")
            }
            val r = zbRankings(j)
            r.keys().forEach { rankings.put(it, r.optJSONArray(it) ?: JSONArray()) }
        } catch (_: Throwable) { }
        return JSONObject().apply {
            put("standings", standings); put("schedule", JSONArray()); put("rankings", rankings)
        }
    }

    /* ============================== 斯诺克 WST ============================== */

    private fun wstName(p: JSONObject?): String {
        val fn = p?.optString("customFirstName")?.ifBlank { p.optString("firstName", "") } ?: ""
        val sn = p?.optString("customSurname")?.ifBlank { p.optString("surname", "") } ?: ""
        return listOf(fn, sn).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun wstLogo(p: JSONObject?): String {
        val prof = p?.optJSONObject("media")?.optString("profile", "") ?: ""
        if (prof.isBlank()) return ""
        val base = if (Regex("\\.(png|jpg|jpeg|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(prof)) prof else "$prof.png"
        return ProxyCore.UPSTREAM["wstImg"]!! + base
    }

    private fun wstAbbr(p: JSONObject?): String {
        val sn = Regex("[^A-Za-z]").replace(p?.optString("surname", "") ?: "", "")
        return sn.take(3).uppercase()
    }

    private fun wstStatus(a: JSONObject?): String {
        val st = (a?.optString("status", "") ?: "").lowercase()
        return when {
            Regex("progress|live|playing|ongoing").containsMatchIn(st) -> "live"
            Regex("complete|finish|done|won|played").containsMatchIn(st) -> "done"
            else -> "upcoming"
        }
    }

    private fun mapWstMatch(a: JSONObject?): JSONObject? {
        a ?: return null
        val at = a.optJSONObject("attributes") ?: a
        val home = at.optJSONObject("homePlayer") ?: JSONObject()
        val away = at.optJSONObject("awayPlayer") ?: JSONObject()
        val tny = at.optJSONObject("tournament") ?: JSONObject()
        val (dateKey, localTime, iso) = ProxyCore.wstLondonToBj(at.optString("startDateTime", ""))
        val status = wstStatus(at)
        val hs = if (at.has("homePlayerScore") && !at.isNull("homePlayerScore")) ProxyCore.toNum(at.opt("homePlayerScore")) else null
        val as_ = if (at.has("awayPlayerScore") && !at.isNull("awayPlayerScore")) ProxyCore.toNum(at.opt("awayPlayerScore")) else null
        val homeObj = JSONObject().apply {
            put("id", home.optString("playerID", ""))
            put("name", wstName(home))
            put("abbr", wstAbbr(home))
            put("color", "#22D3EE")
            put("logo", wstLogo(home))
        }
        val awayObj = JSONObject().apply {
            put("id", away.optString("playerID", ""))
            put("name", wstName(away))
            put("abbr", wstAbbr(away))
            put("color", "#F472B6")
            put("logo", wstLogo(away))
        }
        return JSONObject().apply {
            put("id", "snooker-" + a.optString("id"))
            put("leagueId", "snooker")
            put("sport", "billiards")
            put("source", "wst")
            put("status", status)
            put("dateKey", dateKey)
            put("localTime", localTime)
            put("localTz", "Asia/Shanghai")
            put("utc8", iso)
            put("quarter", ""); put("clock", ""); put("eta", "")
            put("tourney", tny.optString("name", ""))
            put("venueCity", tny.optString("city", ""))
            put("score", if (status == "upcoming") JSONObject().apply { put("home", JSONObject.NULL); put("away", JSONObject.NULL) }
                else JSONObject().apply { put("home", hs ?: JSONObject.NULL); put("away", as_ ?: JSONObject.NULL) })
            put("home", homeObj)
            put("away", awayObj)
            put("plays", JSONArray()); put("shots", JSONArray()); put("roster", JSONArray())
        }
    }

    fun wstGames(): JSONArray {
        val j = ProxyCore.cached("wst:matches", 3600 * 1000L) {
            ProxyCore.fetchJson(ProxyCore.UPSTREAM["wstMatches"]!!, "https://www.wst.tv/")
        }
        val out = JSONArray()
        j.optJSONArray("data")?.let {
            for (i in 0 until it.length()) mapWstMatch(it.optJSONObject(i))?.let { g -> out.put(g) }
        }
        return out
    }

    private fun wstStandings(rank: JSONObject?): JSONArray {
        val pos = rank?.optJSONObject("attributes")?.optJSONArray("positions") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until pos.length()) {
            val p = pos.optJSONObject(i) ?: continue
            val pl = p.optJSONObject("player") ?: JSONObject()
            out.put(JSONObject().apply {
                put("rank", ProxyCore.toNum(p.opt("position")) ?: 0)
                put("name", wstName(pl))
                put("abbr", wstAbbr(pl))
                put("color", "#22D3EE")
                put("logo", wstLogo(pl))
                put("win", JSONObject.NULL); put("lose", JSONObject.NULL)
                val money = if (p.has("prizeMoney") && !p.isNull("prizeMoney")) "£" + String.format(java.util.Locale.US, "%,d", ProxyCore.toNum(p.opt("prizeMoney")) ?: 0) else "—"
                put("pct", money)
                put("streak", "—")
            })
        }
        return out
    }

    fun wstLeague(): JSONObject {
        val rkRaw = try {
            ProxyCore.cached("wst:rankings", 12 * 3600 * 1000L) {
                ProxyCore.fetchJson(ProxyCore.UPSTREAM["wstRankings"]!!, "https://www.wst.tv/")
            }
        } catch (_: Throwable) { null }
        val games = try { wstGames() } catch (_: Throwable) { JSONArray() }
        val ranks = rkRaw?.optJSONArray("data") ?: JSONArray()
        var world: JSONObject? = null
        for (i in 0 until ranks.length()) {
            val r = ranks.optJSONObject(i) ?: continue
            if (Regex("world", RegexOption.IGNORE_CASE).containsMatchIn(r.optJSONObject("attributes")?.optString("name", "") ?: "")) { world = r; break }
        }
        if (world == null) world = ranks.optJSONObject(0)
        val standings = wstStandings(world)
        val schedule = JSONArray()
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val home = g.optJSONObject("home") ?: JSONObject()
            val away = g.optJSONObject("away") ?: JSONObject()
            val scoreObj = g.optJSONObject("score")
            val sh = scorePart(scoreObj?.opt("home"))
            val sa = scorePart(scoreObj?.opt("away"))
            val score = if (g.optString("status") == "upcoming") "—" else "$sh : $sa"
            schedule.put(JSONObject().apply {
                put("date", g.optString("dateKey"))
                put("dateRaw", g.optString("dateKey"))
                put("utc8", g.optString("utc8"))
                put("localTime", g.optString("localTime", ""))
                put("localTz", g.optString("localTz", ""))
                put("home", home.optString("name"))
                put("away", away.optString("name"))
                put("tourney", g.optString("tourney", ""))
                put("city", g.optString("venueCity", ""))
                put("score", score)
                put("status", g.optString("status"))
            })
        }
        return JSONObject().apply {
            put("standings", standings); put("schedule", schedule); put("rankings", JSONObject())
        }
    }

    private fun scorePart(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "—"
        else -> v.toString()
    }

    /* ============================== 懂球帝（SSR HTML 解析） ============================== */

    private fun htmlText(s: String?): String = (s ?: "")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .trim()

    private fun dqStandings(html: String): JSONArray {
        val rows = Regex("<tr[^>]*dp-row[\\s\\S]*?</tr>").findAll(html).map { it.value }.toList()
        val out = JSONArray()
        rows.forEachIndexed { i, row ->
            val rank = Regex("dp-td--rank[^>]*>([^<]*)").find(row)?.groupValues?.get(1) ?: ""
            val name = Regex("dp-td--team[\\s\\S]*?dp-team-link[^>]*>([^<]*)").find(row)?.groupValues?.get(1) ?: ""
            val tds = Regex("dp-td(?:--[\\w-]+)?[^>]*>([\\s\\S]*?)</td>").findAll(row)
                .map { htmlText(it.groupValues[1]) }.toList()
            val win = tds.getOrNull(3) ?: ""
            val draw = tds.getOrNull(4) ?: ""
            val lose = tds.getOrNull(5) ?: ""
            val pts = tds.getOrNull(8) ?: ""
            val nameClean = htmlText(name)
            if (nameClean.isBlank() || nameClean == "—") return@forEachIndexed
            out.put(JSONObject().apply {
                put("rank", ProxyCore.toNum(rank) ?: (i + 1))
                put("name", nameClean)
                put("abbr", "")
                put("color", "#22D3EE")
                put("win", ProxyCore.toNum(win) ?: 0)
                put("draw", ProxyCore.toNum(draw) ?: 0)
                put("lose", ProxyCore.toNum(lose) ?: 0)
                put("pct", pts.ifBlank { "—" })
                put("streak", "—")
            })
        }
        return out
    }

    private fun dqRankings(html: String): JSONArray {
        val rows = Regex("<tr[^>]*dp-row[\\s\\S]*?</tr>").findAll(html).map { it.value }.toList()
        val out = JSONArray()
        rows.forEach { row ->
            val name = htmlText(Regex("dp-td--player[\\s\\S]*?dp-team-link[^>]*>([^<]*)").find(row)?.groupValues?.get(1) ?: "")
            val team = htmlText(Regex("dp-td--team[\\s\\S]*?dp-team-link[^>]*>([^<]*)").find(row)?.groupValues?.get(1) ?: "")
            val valStr = htmlText(Regex("dp-td--count[^>]*>([^<]*)").find(row)?.groupValues?.get(1) ?: "")
            if (name.isBlank() || name == "—") return@forEach
            out.put(JSONObject().apply { put("name", name); put("en", ""); put("team", team); put("val", valStr.ifBlank { "—" }) })
        }
        return out
    }

    fun dqLeague(lid: String): JSONObject {
        val cid = ProxyCore.DQ_CID[lid]
            ?: return JSONObject().apply { put("standings", JSONArray()); put("schedule", JSONArray()); put("rankings", JSONObject()) }
        var standings = JSONArray()
        try {
            val html = ProxyCore.cached("dq:st:$cid", ProxyCore.CACHE_TTL) {
                ProxyCore.fetchHtml("${ProxyCore.UPSTREAM["dqData"]}?cid=$cid&tab=standings")
            }
            standings = dqStandings(html)
        } catch (_: Throwable) { }
        val rankings = JSONObject()
        ProxyCore.DQ_PERSON_TYPE.forEach { (k, type) ->
            try {
                val html = ProxyCore.cached("dq:rk:$cid:$type", ProxyCore.CACHE_TTL) {
                    ProxyCore.fetchHtml("${ProxyCore.UPSTREAM["dqData"]}?cid=$cid&tab=person&type=$type")
                }
                val rows = dqRankings(html)
                if (rows.length() > 0) rankings.put(k, rows)
            } catch (_: Throwable) { }
        }
        return JSONObject().apply {
            put("standings", standings); put("schedule", JSONArray()); put("rankings", rankings)
        }
    }
}
