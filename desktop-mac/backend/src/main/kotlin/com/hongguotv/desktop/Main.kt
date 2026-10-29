// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors

/** Private loopback bridge. The native player receives decrypted ranges, never account data. */
fun main() {
    val repository=ContentRepository()
    var feedCursor=DramaFeedState().recommendationCursor
    val catalogLock=Any()
    val account=MacAccount(repository)
    val publicComments=PublicComments(repository.http)
    val timedComments=TimedComments()
    val appCommentPages=AppCommentPages()
    val token=UUID.randomUUID().toString()
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),16)
    val executor=Executors.newFixedThreadPool(6)
    server.executor=executor
    val lock=Any()
    var current: Pair<String,RemoteVideo>?=null
    val warmLock=Any()
    data class WarmClip(val episode: String,val quality: Int,val actual: String,val video: RemoteVideo)
    var warmed: WarmClip?=null
    fun json(exchange: HttpExchange, value: JSONObject, status: Int=200) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type","application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control","no-store")
        exchange.sendResponseHeaders(status,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/") { exchange ->
        try {
            val path=exchange.requestURI.path
            if(!path.startsWith("/$token/") || exchange.requestMethod !in setOf("GET","HEAD","POST") || exchange.requestHeaders.getFirst("Origin")!=null) {
                exchange.sendResponseHeaders(403,-1);return@createContext
            }
            val query=exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                val parts=it.split('=',limit=2)
                URLDecoder.decode(parts[0],"UTF-8") to URLDecoder.decode(parts[1],"UTF-8")
            }
            val operation=path.removePrefix("/$token/")
            if(operation.startsWith("account/")) {
                json(exchange,account.handle(exchange,operation.removePrefix("account/"),query));return@createContext
            }
            when(operation) {
                "comment-page" -> json(exchange,appCommentPages.read(query["series"].orEmpty(),query["episode"].orEmpty(),query["cursor"].orEmpty()))
                "danmaku" -> json(exchange,timedComments.read(query["series"].orEmpty(),query["episode"].orEmpty(),query["start"]?.toLong() ?: -1,query["duration"]?.toLong() ?: -1))
                "comments" -> json(exchange,publicComments.read(query["series"].orEmpty(),query["episode"].orEmpty()))
                "search" -> {
                    val result=repository.searchAll(query["q"].orEmpty(),query["page"]?.toInt() ?: 1)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore))
                }
                "catalog" -> {
                    val category=DramaCategory.valueOf(query["category"] ?: "COMIC")
                    val page=query["page"]?.toInt() ?: 1
                    val result=repository.discover(category,query["topic"].orEmpty(),page)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore)
                        .put("topics",JSONArray(result.discovery?.topics.orEmpty().map { JSONObject().put("id",it.id).put("label",it.label) })))
                }
                "rank" -> {
                    val snapshot=repository.rankSnapshot(RankBoard.valueOf(query["board"] ?: "COMIC"))
                    val positions=JSONObject().apply { snapshot.ranking.positions.forEach { (id,value) -> put(id,JSONObject().put("rank",value.rank ?: JSONObject.NULL).put("heat",value.heat)) } }
                    json(exchange,JSONObject().put("items",JSONArray(snapshot.filtered(query["topic"].orEmpty()).map { it.toJson() })).put("hasMore",false).put("positions",positions)
                        .put("topics",JSONArray(snapshot.topics.map { JSONObject().put("id",it).put("label",it) })))
                }
                "feed" -> synchronized(catalogLock) {
                    if(query["refresh"]=="true") feedCursor=feedCursor.nextBatch()
                    val category=query["category"]?.takeIf { it!="ALL" }?.let { DramaCategory.valueOf(it) }
                    val page=query["page"]?.toInt() ?: 1
                    if(page==1 && query["refresh"]!="true") feedCursor=DramaFeedState().recommendationCursor
                    val result=RecommendationFeed(repository.http,account=account.snapshot(),cursor=feedCursor,category=category).page(page)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore).put("topics",JSONArray()))
                }
                "related" -> {
                    val items=repository.related(query["id"].orEmpty())
                    json(exchange,JSONObject().put("items",JSONArray(items.map { JSONObject().put("series",it.series.toJson()).put("season",it.season).put("upcoming",it.upcoming) })))
                }
                "detail" -> {
                    val detail=repository.detail(query["id"].orEmpty())
                    json(exchange,JSONObject().put("series",detail.series.toJson()).put("episodes",JSONArray(detail.episodes)))
                }
                "warm" -> synchronized(warmLock) {
                    val detail=repository.detail(query["id"].orEmpty())
                    val episode=detail.episodes.firstOrNull() ?: error("No episodes")
                    val quality=query["quality"]?.toInt() ?: 1080
                    require(quality in setOf(480,720,1080))
                    val stream=repository.stream(episode,quality)
                    val clip=RemoteVideo(repository.http,stream)
                    try { clip.warm() } catch(problem: Exception) { clip.close();throw problem }
                    synchronized(lock) { warmed?.video?.close();warmed=WarmClip(episode,quality,stream.quality,clip) }
                    json(exchange,JSONObject().put("series",detail.series.toJson()).put("episodes",JSONArray(detail.episodes)))
                }
                "play" -> {
                    val quality=query["quality"]?.toInt() ?: 1080
                    require(quality in setOf(480,720,1080))
                    val cached=synchronized(lock) {
                        warmed?.takeIf { it.episode==query["id"] && it.quality==quality }?.also { warmed=null }
                    }
                    val prepared=cached ?: run {
                        val stream=repository.stream(query["id"].orEmpty(),quality)
                        val clip=RemoteVideo(repository.http,stream)
                        try { clip.prepare() } catch(problem: Exception) { clip.close();throw problem }
                        WarmClip(query["id"].orEmpty(),quality,stream.quality,clip)
                    }
                    val clip=prepared.video
                    val id=UUID.randomUUID().toString()
                    synchronized(lock) { current?.second?.close();current=id to clip }
                    json(exchange,JSONObject().put("url","http://127.0.0.1:${server.address.port}/$token/video?id=$id").put("quality",prepared.actual))
                }
                "stop" -> { synchronized(lock) { current?.second?.close();current=null;warmed?.video?.close();warmed=null };json(exchange,JSONObject().put("ok",true)) }
                "video" -> {
                    val selected=synchronized(lock) { current?.takeIf { it.first==query["id"] }?.second }
                    if(selected==null) { exchange.sendResponseHeaders(404,-1);return@createContext }
                    val range=exchange.requestHeaders.getFirst("Range")
                    val match=range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start=match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val end=match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(selected.total-1) ?: (selected.total-1)
                    if((range!=null && match==null) || start>end || start>=selected.total) {
                        exchange.responseHeaders.set("Content-Range","bytes */${selected.total}")
                        exchange.sendResponseHeaders(416,-1);return@createContext
                    }
                    exchange.responseHeaders.set("Content-Type","video/mp4")
                    exchange.responseHeaders.set("Accept-Ranges","bytes")
                    exchange.responseHeaders.set("Cache-Control","no-store")
                    val count=end-start+1
                    if(range!=null) exchange.responseHeaders.set("Content-Range","bytes $start-$end/${selected.total}")
                    exchange.responseHeaders.set("Content-Length",count.toString())
                    if(exchange.requestMethod=="HEAD") exchange.sendResponseHeaders(if(range!=null) 206 else 200,-1)
                    else {
                        exchange.sendResponseHeaders(if(range!=null) 206 else 200,count)
                        exchange.responseBody.use { out ->
                            var offset=start
                            while(offset<=end) {
                                val bytes=selected.read(offset,minOf(1024*1024L,end-offset+1).toInt())
                                if(bytes.isEmpty()) break
                                out.write(bytes);offset+=bytes.size
                            }
                        }
                    }
                }
                else -> exchange.sendResponseHeaders(404,-1)
            }
        } catch(problem: Exception) {
            // The bridge only listens on loopback behind a per-launch token, so the already
            // user-facing messages (bad phone number, rejected verification code, expired QR)
            // are safe — and necessary — to show instead of a blanket failure.
            val detail=when(problem) {
                is IllegalArgumentException, is IllegalStateException,
                is PhoneLogin.Failure, is java.io.IOException ->
                    problem.message?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
            runCatching { json(exchange,JSONObject().put("error",detail ?: "请求失败，请重试；若持续失败，请检查网络或更新客户端。"),502) }
        } finally { exchange.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        synchronized(lock) { current?.second?.close();current=null;warmed?.video?.close();warmed=null }
        repository.http.dispatcher.cancelAll()
        account.close();server.stop(0);executor.shutdownNow()
        repository.http.dispatcher.cancelAll();repository.http.connectionPool.evictAll()
        repository.http.dispatcher.executorService.shutdownNow()
    })
    server.start()
    println("http://127.0.0.1:${server.address.port}/$token/")
    System.out.flush()
    // Closing the native app's stdin tears down the helper and its playback requests.
    while(System.`in`.read()!=-1) { }
    kotlin.system.exitProcess(0)
}
