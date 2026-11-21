package com.sportshub.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 路由结果：交由 WebView 拦截器包装成 WebResourceResponse。 */
data class RouterResponse(
    val status: Int,
    val contentType: String,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

object ProxyRouter {

    private const val ESPN_HOST = "site.api.espn.com"

    private fun jsonOk(data: Any?): RouterResponse {
        val obj = JSONObject().apply { put("ok", true); put("data", data ?: JSONObject.NULL) }
        return RouterResponse(200, "application/json; charset=utf-8", obj.toString(), corsHeaders())
    }

    private fun jsonErr(code: Int, message: String): RouterResponse {
        val obj = JSONObject().apply { put("ok", false); put("error", message) }
        return RouterResponse(code, "application/json; charset=utf-8", obj.toString(), corsHeaders())
    }

    private fun corsHeaders() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type"
    )

    fun route(uri: Uri): RouterResponse {
        val path = uri.path ?: "/"
        return try {
            when {
                path == "/" -> RouterResponse(200, "application/json; charset=utf-8",
                    JSONObject().apply {
                        put("ok", true); put("service", "sports-hub-proxy")
                        put("time", java.time.Instant.now().toString())
                    }.toString(), corsHeaders())

                path == "/proxy/raw" -> handleRaw(uri)
                path == "/proxy/espn" -> handleEspn(uri)
                path == "/proxy/games/all" -> jsonOk(allGames())

                path == "/proxy/tencent/games" -> jsonOk(ProxyTencent.txGames(uri.getQueryParameter("league") ?: ""))
                path == "/proxy/tencent/league" -> jsonOk(ProxyTencent.txLeague(uri.getQueryParameter("league") ?: ""))
                path == "/proxy/tencent/game" -> {
                    val g = ProxyTencent.txGame(uri.getQueryParameter("league") ?: "", uri.getQueryParameter("mid") ?: "")
                    if (g == null) jsonErr(404, "game not found") else jsonOk(g)
                }

                path == "/proxy/dongqiudi/league" -> jsonOk(ProxyOther.dqLeague(uri.getQueryParameter("league") ?: ""))
                path == "/proxy/zhibo8/league" -> jsonOk(ProxyOther.zbLeague(uri.getQueryParameter("league") ?: ""))

                path == "/proxy/cba/games" -> jsonOk(ProxyAdapters.cbaGames())
                path == "/proxy/cba/league" -> jsonOk(ProxyAdapters.cbaLeague())
                path == "/proxy/wcba/games" -> jsonOk(ProxyAdapters.wcbaGames())
                path == "/proxy/wcba/league" -> jsonOk(ProxyAdapters.wcbaLeague())
                path == "/proxy/snooker/games" -> jsonOk(ProxyOther.wstGames())
                path == "/proxy/snooker/league" -> jsonOk(ProxyOther.wstLeague())

                else -> jsonErr(404, "not found: $path")
            }
        } catch (e: Throwable) {
            jsonErr(502, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleRaw(uri: Uri): RouterResponse {
        val target = uri.getQueryParameter("url") ?: return jsonErr(400, "missing url")
        return try {
            val r = ProxyCore.httpGet(target, accept = "*/*", timeoutMs = 30000)
            RouterResponse(r.status, r.contentType ?: "text/plain; charset=utf-8", r.body,
                mapOf("Access-Control-Allow-Origin" to "*"))
        } catch (e: Throwable) {
            jsonErr(502, e.message ?: "raw error")
        }
    }

    private fun handleEspn(uri: Uri): RouterResponse {
        val target = uri.getQueryParameter("url") ?: return jsonErr(400, "missing url")
        val u = try { Uri.parse(target) } catch (_: Throwable) { return jsonErr(400, "bad url") }
        if (u.host != ESPN_HOST) return jsonErr(403, "espn host only: ${u.host}")
        return try {
            val r = ProxyCore.httpGet(target, "https://www.espn.com/", "application/json, */*", 12000)
            RouterResponse(r.status, r.contentType ?: "application/json; charset=utf-8", r.body,
                mapOf("Access-Control-Allow-Origin" to "*"))
        } catch (e: Throwable) {
            jsonErr(502, e.message ?: "espn error")
        }
    }

    /** 聚合全量赛程：腾讯各联赛 + WCBA + 斯诺克，单源 3s 超时降级，按 id 去重。 */
    private fun allGames(): JSONArray {
        val txLids = ProxyCore.TENCENT_LEAGUES.keys.filter { it != "nba" }
        val sources = mutableListOf<Callable<JSONArray>>()
        txLids.forEach { lid ->
            sources.add(Callable { runCatching { ProxyTencent.txGames(lid) }.getOrDefault(JSONArray()) })
        }
        sources.add(Callable { runCatching { ProxyAdapters.wcbaGames() }.getOrDefault(JSONArray()) })
        sources.add(Callable { runCatching { ProxyOther.wstGames() }.getOrDefault(JSONArray()) })

        val pool = Executors.newFixedThreadPool(sources.size.coerceAtMost(6))
        val futures = sources.map { pool.submit(it) }
        val out = JSONArray()
        val seen = HashSet<String>()
        futures.forEach { f ->
            val arr = try { f.get(3, TimeUnit.SECONDS) } catch (_: Throwable) { JSONArray() }
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val id = g.optString("id")
                if (id.isNotBlank() && seen.add(id)) out.put(g)
            }
        }
        pool.shutdown()
        return out
    }
}
