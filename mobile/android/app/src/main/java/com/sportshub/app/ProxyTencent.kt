package com.sportshub.app

import org.json.JSONArray
import org.json.JSONObject

/** 腾讯体育适配器：复刻 server.js 腾讯段（feeds / rank / rankings / stat）。 */
object ProxyTencent {

    private val TX_RANK_MAP = mapOf(
        "basketball" to mapOf("point" to "pts", "assist" to "ast", "rebound" to "reb", "steal" to "stl", "block" to "blk"),
        "football" to mapOf("goals" to "goals"),
        "tennis" to emptyMap<String, String>()
    )

    private fun fetchTencent(url: String): JSONObject {
        val r = ProxyCore.httpGet(url, "https://sports.qq.com/")
        if (r.status !in 200..299) throw ProxyException("tx upstream HTTP ${r.status}")
        val json = ProxyCore.parseMaybeJsonp(r.body) ?: throw ProxyException("tx upstream parse")
        if (json.optInt("code", -1) != 0) throw ProxyException("tx upstream code ${json.opt("code")}")
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun sportOfType(type: String?): String = when (type) {
        "1" -> "football"
        "2" -> "basketball"
        "10" -> "tennis"
        else -> ""
    }

    private fun status(mi: JSONObject): String {
        val p = mi.optString("matchPeriod", "")
        if (p == "1") return "live"
        if (p == "2") return "done"
        val win = mi.optString("win", "")
        if (win == "left" || win == "right") return "done"
        val q = mi.optString("quarter", "")
        if (q.isNotBlank() && q != "未开始" && !q.contains("预约")) return "live"
        return "upcoming"
    }

    fun mapGame(mi: JSONObject?, lid: String, cfg: ProxyCore.TxCfg): JSONObject? {
        if (mi == null || mi.optString("mid").isBlank()) return null
        val st = status(mi)
        val sport = sportOfType(mi.optString("matchType", "")).ifBlank { cfg.sport }
        val startTime = mi.optString("startTime", "")
        val dateKey = if (startTime.length >= 10) startTime.substring(0, 10) else ""
        val localTime = if (startTime.length >= 16) startTime.substring(11, 16) else ""
        val score = if (st == "upcoming") JSONObject().apply { put("home", JSONObject.NULL); put("away", JSONObject.NULL) }
        else JSONObject().apply {
            put("home", ProxyCore.toNum(mi.opt("leftGoal")) ?: JSONObject.NULL)
            put("away", ProxyCore.toNum(mi.opt("rightGoal")) ?: JSONObject.NULL)
        }
        val home = JSONObject().apply {
            put("id", mi.optString("leftId", "").toString())
            put("name", ProxyCore.optStr(mi, "leftName"))
            put("abbr", "")
            put("color", "#22D3EE")
            put("logo", ProxyCore.secureUrl(mi.optString("leftBadge", "")))
        }
        val away = JSONObject().apply {
            put("id", mi.optString("rightId", "").toString())
            put("name", ProxyCore.optStr(mi, "rightName"))
            put("abbr", "")
            put("color", "#F472B6")
            put("logo", ProxyCore.secureUrl(mi.optString("rightBadge", "")))
        }
        return JSONObject().apply {
            put("id", "tx-" + mi.optString("mid"))
            put("leagueId", lid)
            put("sport", sport)
            put("source", "tencent")
            put("status", st)
            put("dateKey", dateKey)
            put("localTime", localTime)
            put("localTz", "Asia/Shanghai")
            put("utc8", startTime)
            put("quarter", when (st) { "live" -> mi.optString("quarter", ""); "done" -> "完场"; else -> "" })
            put("clock", "")
            put("eta", "")
            put("score", score)
            put("home", home)
            put("away", away)
            put("plays", JSONArray())
            put("shots", JSONArray())
            put("roster", JSONArray())
        }
    }

    private fun basketballRoster(sideName: String, sideId: String, sideArr: JSONArray?): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        sideArr ?: return rows
        for (i in 0 until sideArr.length()) {
            val item = sideArr.optJSONObject(i) ?: continue
            val row = item.optJSONArray("row") ?: continue
            val name = row.optString(1, "—")
            val isTotal = Regex("统计|合计|总分|全队|Total", RegexOption.IGNORE_CASE).containsMatchIn(name)
            rows.add(JSONObject().apply {
                put("id", item.optString("playerId", ""))
                put("num", row.optString(0, ""))
                put("name", name)
                put("pos", item.optString("pos", ""))
                put("starter", item.optString("onCrt") == "1")
                put("didNotPlay", item.optString("onCrt") != "1")
                put("isTotal", isTotal)
                put("team", sideName)
                put("teamId", sideId)
                put("min", row.optString(2, "—"))
                put("pts", row.optString(3, "—"))
                put("reb", row.optString(4, "—"))
                put("ast", row.optString(5, "—"))
                put("stl", row.optString(9, "—"))
                put("blk", row.optString(10, "—"))
                put("tov", "—")
                put("logo", ProxyCore.secureUrl(item.optString("pic", "")))
            })
        }
        return rows
    }

    private fun footballRoster(squard: JSONArray?, bench: JSONObject?, leftName: String, rightName: String, leftId: String, rightId: String): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        fun push(p: JSONObject?, side: String, starter: Boolean) {
            p ?: return
            rows.add(JSONObject().apply {
                put("id", p.optString("playerId", ""))
                put("num", p.optString("jerseyNum", ""))
                put("name", ProxyCore.optStr(p, "playerName"))
                put("pos", p.optString("position", ""))
                put("starter", starter)
                put("team", if (side == "left") leftName else rightName)
                put("teamId", if (side == "left") leftId else rightId)
                put("goals", "—"); put("assists", "—"); put("shots", "—"); put("sot", "—"); put("stl", "—"); put("sv", "—")
                put("logo", ProxyCore.secureUrl(p.optString("playerIcon", "")))
            })
        }
        squard?.let {
            for (i in 0 until it.length()) {
                val it2 = it.optJSONObject(i) ?: continue
                push(it2.optJSONObject("left"), "left", true)
                push(it2.optJSONObject("right"), "right", true)
            }
        }
        bench?.optJSONArray("left")?.let { for (i in 0 until it.length()) push(it.optJSONObject(i), "left", false) }
        bench?.optJSONArray("right")?.let { for (i in 0 until it.length()) push(it.optJSONObject(i), "right", false) }
        return rows
    }

    private fun footballEvents(evs: JSONArray?): JSONArray {
        val out = JSONArray()
        evs ?: return out
        for (i in 0 until evs.length()) {
            val e = evs.optJSONObject(i) ?: continue
            val period = if (e.optString("eventPeriod") == "SecondHalf") 2 else 1
            val type = e.optString("type", "")
            val result = when {
                type.contains("进球") -> "goal"
                !e.optString("cardType", "").isNullOrBlank() -> "card"
                type.contains("换人") -> "sub"
                else -> "other"
            }
            val player = e.optString("playerName", "")
            out.put(JSONObject().apply {
                put("period", period)
                put("clock", e.optString("time", "") + "'")
                put("desc", type + (if (player.isBlank()) "" else " $player"))
                put("type", type)
                put("result", result)
                put("side", e.optString("side", ""))
                put("scoreValue", 0)
                put("scoringPlay", type.contains("进球"))
                put("shootingPlay", false)
                put("score", e.optString("score", ""))
                put("actor", if (player.isBlank()) JSONObject.NULL else JSONObject().apply { put("name", player); put("num", ""); put("pos", "") })
            })
        }
        return out
    }

    private fun rankStandings(data: JSONObject, sport: String): JSONArray {
        val flat = mutableListOf<JSONObject>()
        fun pushItem(item: Any?) {
            when (item) {
                is JSONArray -> for (i in 0 until item.length()) item.optJSONObject(i)?.let { flat.add(it) }
                is JSONObject -> {
                    val arr = item.keys().asSequence().map { item.optJSONArray(it) }.firstOrNull { it != null }
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { flat.add(it) }
                    else flat.add(item)
                }
            }
        }
        val raw = data.opt("list")
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) pushItem(raw.opt(i))
            is JSONObject -> raw.keys().forEach { pushItem(raw.optJSONArray(it) ?: raw.optJSONObject(it)) }
        }
        val out = JSONArray()
        flat.forEachIndexed { i, t ->
            val win = ProxyCore.toNum(t.opt("winMatchCount")) ?: 0
            val lose = ProxyCore.toNum(t.opt("lostMatchCount")) ?: 0
            val pts = ProxyCore.toNum(t.opt("score"))
            val pct = if (sport == "football") (pts?.toString() ?: "—") else ProxyCore.pct(win, lose)
            out.put(JSONObject().apply {
                put("rank", ProxyCore.toNum(t.opt("serial")) ?: (i + 1))
                put("name", t.optString("teamName", t.optString("name", "—")))
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

    private fun playerRankings(data: JSONObject, sport: String): JSONObject {
        val map = TX_RANK_MAP[sport] ?: emptyMap()
        val groups = data.optJSONArray("groups") ?: JSONArray()
        val out = JSONObject()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val k = map[g.optString("code")] ?: continue
            val tables = g.optJSONArray("tables") ?: JSONArray()
            val rows = tables.optJSONObject(0)?.optJSONArray("rows") ?: JSONArray()
            val list = JSONArray()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val v = JSONObject()
                row.optJSONArray("values")?.let {
                    for (x in 0 until it.length()) {
                        val it2 = it.optJSONObject(x) ?: continue
                        v.put(it2.optString("code"), it2.opt("value"))
                    }
                }
                val p = v.optJSONObject("player") ?: JSONObject()
                val t = v.optJSONObject("team") ?: JSONObject()
                val name = p.optString("fullCnName", p.optString("cnName", p.optString("enName", "—")))
                val en = p.optString("fullEnName", p.optString("enName", ""))
                val team = t.optString("cnName", "")
                val valStr = if (v.has("rankValue") && !v.isNull("rankValue")) v.optString("rankValue", "—") else "—"
                if (name.isBlank() || name == "—") continue
                list.put(JSONObject().apply { put("name", name); put("en", en); put("team", team); put("val", valStr) })
            }
            out.put(k, list)
        }
        return out
    }

    fun txGames(lid: String, offsets: List<Int>? = null): JSONArray {
        val cfg = ProxyCore.TENCENT_LEAGUES[lid] ?: return JSONArray()
        if (cfg.cid.isBlank()) return JSONArray()
        if (lid == "nba") return JSONArray() // NBA 赛程走 ESPN
        val off = offsets ?: listOf(0, 1, 2)
        val all = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        for (date in off.map { ProxyCore.txBjDate(it) }) {
            val data = try {
                ProxyCore.cached("tx:feeds:${cfg.cid}:$date", ProxyCore.CACHE_TTL) {
                    fetchTencent(ProxyCore.UPSTREAM["txFeeds"]!! + "?indexID=$date&type=1500&indexKey=${cfg.cid}")
                }
            } catch (_: Throwable) { continue }
            val list = data.optJSONObject("info")?.optJSONObject("type1500")?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val mi = list.optJSONObject(i)?.optJSONObject("matchInfo") ?: continue
                val g = mapGame(mi, lid, cfg) ?: continue
                if (seen.add(g.optString("id"))) all.add(g)
            }
        }
        return JSONArray(all)
    }

    fun txLeague(lid: String): JSONObject {
        val cfg = ProxyCore.TENCENT_LEAGUES[lid]
            ?: return JSONObject().apply { put("standings", JSONArray()); put("schedule", JSONArray()); put("rankings", JSONObject()) }
        var standings = JSONArray()
        val rankings = JSONObject()
        if (cfg.cid.isNotBlank()) {
            try {
                val data = ProxyCore.cached("tx:rank:${cfg.cid}", ProxyCore.CACHE_TTL) {
                    fetchTencent(ProxyCore.UPSTREAM["txRank"]!! + "?competitionId=${cfg.cid}")
                }
                standings = rankStandings(data, cfg.sport)
            } catch (_: Throwable) { standings = JSONArray() }
            try {
                val rk = ProxyCore.cached("tx:rankings:${cfg.cid}", ProxyCore.CACHE_TTL) {
                    fetchTencent(ProxyCore.UPSTREAM["txRankings"]!! + "?competition_id=${cfg.cid}&season_id=&season_type=&type=2&scene=pc")
                }
                val r = playerRankings(rk, cfg.sport)
                rankings.keys().forEach { rankings.put(it, r.optJSONArray(it) ?: JSONArray()) }
            } catch (_: Throwable) { }
        }
        var games = txGames(lid, (-7..7).toList())
        if (games.length() == 0) {
            for (o in listOf(-10, -14, -21, -30)) {
                games = txGames(lid, listOf(o))
                if (games.length() > 0) break
            }
        }
        val schedule = scheduleRows(games)
        return JSONObject().apply {
            put("standings", standings)
            put("schedule", schedule)
            put("rankings", rankings)
        }
    }

    private fun scheduleRows(games: JSONArray): JSONArray {
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

    /** mid 前缀 → 前端 leagueId 反查表。 */
    private val cidToLid: Map<String, String> = ProxyCore.TENCENT_LEAGUES.entries
        .filter { it.value.cid.isNotBlank() }
        .associate { it.value.cid to it.key }

    fun txGame(lidIn: String, mid: String): JSONObject? {
        var lid = lidIn
        if (lid.isBlank() && mid.isNotBlank()) {
            lid = cidToLid[mid.split(":").first()] ?: ""
        }
        val cfg = ProxyCore.TENCENT_LEAGUES[lid] ?: return null
        if (mid.isBlank()) return null

        val games = txGames(lid)
        var base: JSONObject? = null
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            if (g.optString("id") == "tx-$mid") { base = g; break }
        }

        val stat = try {
            ProxyCore.cached("tx:stat:$mid", ProxyCore.CACHE_TTL) {
                fetchTencent(ProxyCore.UPSTREAM["txStat"]!! + "?mid=$mid")
            }
        } catch (_: Throwable) { JSONObject() }

        val ti = stat.optJSONObject("teamInfo") ?: JSONObject()
        if (base == null && ti.optString("leftName").isNotBlank()) {
            val mi = JSONObject().apply {
                put("mid", mid)
                put("startTime", ti.optString("startTime", ""))
                put("quarter", ti.optString("quarterDesc", ""))
                put("matchPeriod", ti.optString("matchPeriod", if (ti.optString("liveType") == "4") "2" else "0"))
                put("leftId", ti.optString("leftId", ""))
                put("leftName", ti.optString("leftName", ""))
                put("leftBadge", ti.optString("leftBadge", ""))
                put("leftGoal", ti.opt("leftGoal") ?: "")
                put("rightId", ti.optString("rightId", ""))
                put("rightName", ti.optString("rightName", ""))
                put("rightBadge", ti.optString("rightBadge", ""))
                put("rightGoal", ti.opt("rightGoal") ?: "")
            }
            base = mapGame(mi, lid, cfg)
        }
        val b = base ?: return null
        val homeObj = b.optJSONObject("home") ?: JSONObject()
        val awayObj = b.optJSONObject("away") ?: JSONObject()
        val hName = homeObj.optString("name", "")
        val aName = awayObj.optString("name", "")
        val hId = homeObj.optString("id", "")
        val aId = awayObj.optString("id", "")

        var roster = JSONArray()
        var plays = JSONArray()
        if (cfg.sport == "basketball" && stat.optJSONObject("playerStats") != null) {
            val ps = stat.optJSONObject("playerStats")!!
            val list = basketballRoster(hName, hId, ps.optJSONArray("left")) +
                basketballRoster(aName, aId, ps.optJSONArray("right"))
            roster = JSONArray(list)
        } else if (cfg.sport == "football") {
            roster = JSONArray(footballRoster(stat.optJSONArray("squard"), stat.optJSONObject("bench"), hName, aName, hId, aId))
            plays = footballEvents(stat.optJSONArray("footballEvents"))
        }

        // CBA：腾讯无动作流，回退官网数据站（AES 解密）补中文动作流/名单/队标队色
        if (lid == "cba") {
            try {
                val cd = ProxyDetail.cbaDetail(b)
                if (cd != null) {
                    if (plays.length() == 0 && cd.optJSONArray("plays")!!.length() > 0) plays = cd.getJSONArray("plays")
                    if (roster.length() == 0 && cd.optJSONArray("roster")!!.length() > 0) roster = cd.getJSONArray("roster")
                    val bi = cd.optJSONObject("baseInfo")
                    if (bi != null) {
                        if (homeObj.optString("logo").isBlank() && !bi.optString("hostTeamLogo", "").isBlank())
                            homeObj.put("logo", ProxyCore.secureUrl(bi.optString("hostTeamLogo")))
                        if (awayObj.optString("logo").isBlank() && !bi.optString("guestTeamLogo", "").isBlank())
                            awayObj.put("logo", ProxyCore.secureUrl(bi.optString("guestTeamLogo")))
                        if (homeObj.optString("color").isBlank() && !bi.optString("hostColor", "").isBlank())
                            homeObj.put("color", bi.optString("hostColor"))
                        if (awayObj.optString("color").isBlank() && !bi.optString("guestColor", "").isBlank())
                            awayObj.put("color", bi.optString("guestColor"))
                    }
                }
            } catch (_: Throwable) { }
        }

        val homeR = JSONArray(); val awayR = JSONArray()
        for (i in 0 until roster.length()) {
            val r = roster.getJSONObject(i)
            when (r.optString("team")) { hName -> homeR.put(r); aName -> awayR.put(r) }
        }
        homeObj.put("roster", homeR)
        awayObj.put("roster", awayR)
        val out = JSONObject()
        b.keys().forEach { out.put(it, b.opt(it)) }
        out.put("roster", roster); out.put("plays", plays); out.put("shots", JSONArray())
        out.put("home", homeObj); out.put("away", awayObj)
        return out
    }
}
