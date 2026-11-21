package com.sportshub.app

import org.json.JSONArray
import org.json.JSONObject

/** CBA 官网数据站（data-server，AES-128-ECB 解密）：单场动作流 / 名单 / 基本信息兜底。 */
object ProxyDetail {

    private fun normName(n: String?): String = (n ?: "")
        .replace(Regex("\\s+"), "")
        .replace(Regex("(俱乐部|篮球队|男篮|队)$"), "")

    private fun nameMatch(a: String?, b: String?): Boolean {
        val x = normName(a); val y = normName(b)
        if (x.isBlank() || y.isBlank()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun cbaDay(day: String): JSONArray {
        val j = ProxyCore.cached("cba:day:$day", ProxyCore.CACHE_TTL) {
            ProxyCore.cbaRequest("GET", "/api/schedules/day?day=$day&auto=0")
        }
        return when (j) {
            is JSONArray -> j
            is JSONObject -> j.optJSONArray("records") ?: JSONArray()
            else -> JSONArray()
        }
    }

    private fun cbaFindSchedule(dateKey: String, homeName: String, awayName: String): JSONObject? {
        if (dateKey.isBlank()) return null
        val rows = cbaDay(dateKey)
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (nameMatch(homeName, r.optString("homeTeamName")) && nameMatch(awayName, r.optString("visitingTeamName"))) return r
        }
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (nameMatch(homeName, r.optString("visitingTeamName")) && nameMatch(awayName, r.optString("homeTeamName"))) return r
        }
        return null
    }

    private fun playResult(text: String): String = when {
        text.contains("暂停") -> "timeout"
        text.contains("换人") || text.contains("换下") || text.contains("换上") -> "sub"
        text.contains("犯规") -> "foul"
        text.contains("失误") -> "turnover"
        text.contains("断下") || text.contains("抢断") -> "steal"
        Regex("走步|三秒|24秒|8秒|5秒|出界|违例").containsMatchIn(text) -> "violation"
        text.contains("助攻") -> "assist"
        text.contains("篮板") -> "rebound"
        text.contains("不中") || text.contains("未进") || text.contains("打铁") -> "miss"
        text.contains("命中") || text.contains("得分") || text.contains("罚中") || text.contains("进球") || text.contains("入网") -> "made"
        else -> "other"
    }

    private fun clock(rec: JSONObject): String {
        val m = rec.opt("minutes")?.toString()?.padStart(2, '0') ?: ""
        val s = rec.opt("seconds")?.toString()?.padStart(2, '0') ?: ""
        return if (m.isNotBlank() && s != "NaN") "$m:$s" else ""
    }

    private fun mapPlay(rec: JSONObject, homeColor: String, awayColor: String): JSONObject {
        val names = rec.optJSONArray("playerCNNames")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } ?: emptyList()
        val text = rec.optString("textualFormat", rec.optString("btTextualFormat", ""))
        val side = when (rec.optString("color")) {
            homeColor -> "home"
            awayColor -> "away"
            else -> ""
        }
        val score = if (rec.has("homeScore") && !rec.isNull("homeScore") && rec.has("visitorScore") && !rec.isNull("visitorScore"))
            "${rec.opt("homeScore")}:${rec.opt("visitorScore")}" else ""
        return JSONObject().apply {
            put("period", ProxyCore.toNum(rec.opt("quarterNum")) ?: 1)
            put("clock", clock(rec))
            put("desc", text); put("descZh", text)
            put("type", "cba"); put("result", playResult(text)); put("side", side)
            put("scoreValue", 0)
            put("scoringPlay", Regex("命中|得分|罚中|入网").containsMatchIn(text))
            put("shootingPlay", Regex("跳投|上篮|扣篮|三分|两分|罚球|投篮").containsMatchIn(text))
            put("score", score)
            put("actor", if (names.isNotEmpty()) JSONObject().apply { put("name", names[0]); put("num", ""); put("pos", "") } else JSONObject.NULL)
        }
    }

    private fun cbaEvents(sid: String): JSONArray {
        val plays = JSONArray()
        for (q in 1..4) {
            val j = try {
                ProxyCore.cached("cba:ev:$sid:$q", ProxyCore.CACHE_TTL) {
                    ProxyCore.cbaRequest("POST", "/api/schedule-eventses/filterPage?startNum=0",
                        JSONObject().apply { put("scheduleId", sid); put("quarterNum", q) })
                }
            } catch (_: Throwable) { continue }
            val records = when (j) {
                is JSONObject -> j.optJSONArray("records") ?: JSONArray()
                is JSONArray -> j
                else -> JSONArray()
            }
            for (i in 0 until records.length()) records.optJSONObject(i)?.let { plays.put(it) }
        }
        return plays
    }

    private fun mapPlayer(p: JSONObject, teamName: String, teamId: String): JSONObject = JSONObject().apply {
        put("id", p.optString("playerId", p.optString("id", "")))
        put("num", p.optString("number", ""))
        put("name", p.optString("cnAlias", p.optString("cnName", "—")))
        put("pos", p.optString("positionDescription", p.optString("position", "")))
        put("starter", null); put("didNotPlay", false)
        put("team", teamName); put("teamId", teamId)
        put("min", "—"); put("pts", "—"); put("reb", "—"); put("ast", "—")
        put("stl", "—"); put("blk", "—"); put("tov", "—")
        put("logo", ProxyCore.secureUrl(p.optString("playerPic", "")))
    }

    private fun cbaRoster(sid: String, homeName: String, awayName: String): JSONArray {
        val j = ProxyCore.cached("cba:roster:$sid", ProxyCore.CACHE_TTL) {
            ProxyCore.cbaRequest("GET", "/api/schedules/getPlayersByScheduleId/$sid")
        } as? JSONObject ?: return JSONArray()
        val rows = JSONArray()
        j.optJSONArray("hostPlayers")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { p -> rows.put(mapPlayer(p, homeName, p.optString("teamCbaId", ""))) } }
        j.optJSONArray("guestPlayers")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { p -> rows.put(mapPlayer(p, awayName, p.optString("teamCbaId", ""))) } }
        return rows
    }

    /** 聚合官网单场：动作流 + 兜底名单 + 基本信息校准；无投篮坐标。 */
    fun cbaDetail(base: JSONObject): JSONObject? {
        val homeObj = base.optJSONObject("home") ?: JSONObject()
        val awayObj = base.optJSONObject("away") ?: JSONObject()
        val sched = cbaFindSchedule(base.optString("dateKey", ""), homeObj.optString("name", ""), awayObj.optString("name", ""))
            ?: return null
        val sid = sched.optString("scheduleId", "")
        val baseInfo = try {
            ProxyCore.cbaRequest("GET", "/api/team-match-datas/getMatchDataReport/baseInfo/$sid") as? JSONObject
        } catch (_: Throwable) { null }

        val rawEvents = cbaEvents(sid)
        val homeColor = baseInfo?.optString("hostColor", "")?.ifBlank { sched.optString("homeColor", homeObj.optString("color", "")) }
            ?: sched.optString("homeColor", homeObj.optString("color", ""))
        val awayColor = baseInfo?.optString("guestColor", "")?.ifBlank { sched.optString("visitingColor", awayObj.optString("color", "")) }
            ?: sched.optString("visitingColor", awayObj.optString("color", ""))

        val plays = JSONArray()
        for (i in 0 until rawEvents.length()) {
            rawEvents.optJSONObject(i)?.let { plays.put(mapPlay(it, homeColor, awayColor)) }
        }
        val roster = try { cbaRoster(sid, homeObj.optString("name", ""), awayObj.optString("name", "")) } catch (_: Throwable) { JSONArray() }

        return JSONObject().apply {
            put("scheduleId", sid)
            put("baseInfo", baseInfo ?: JSONObject.NULL)
            put("plays", plays)
            put("roster", roster)
            put("homeColor", homeColor)
            put("awayColor", awayColor)
        }
    }
}
