// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.*
import com.sun.net.httpserver.HttpExchange
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Credentials stay in process memory; the native client persists them in macOS Keychain. */
class MacAccount(private val repository: ContentRepository) {
    private val client=CloudAccountClient(repository.http.newBuilder().followRedirects(false).followSslRedirects(false).build())
    private var credentials: AccountCredentials?=null
    private var profile: CloudProfile?=null
    private var login: WebLogin?=null
    private var loginID=""
    private var smsLogin: WebLogin?=null
    @Synchronized fun snapshot()=credentials
    @Synchronized fun handle(exchange: HttpExchange,action: String,query: Map<String,String>): JSONObject {
        val readOnly=action in setOf("status","poll","history","favorites","reservations")
        require(readOnly || exchange.requestMethod=="POST") { "请使用本机客户端操作" }
        val body=if(exchange.requestMethod=="POST") {
            val bytes=exchange.requestBody.readNBytes(131073)
            require(bytes.size<=131072)
            if(bytes.isEmpty()) JSONObject() else JSONObject(String(bytes,Charsets.UTF_8))
        } else JSONObject()
        if(action in setOf("favorite","reserve","upload")) require(body.optString("accountID")==profile?.id) { "账号已变化，请重试" }
        fun account()=checkNotNull(credentials) { "请先扫码登录红果账号" }
        fun user(export: Boolean=false)=JSONObject().put("loggedIn",profile!=null).apply {
            profile?.let { put("id",it.id);put("nickname",it.nickname) }
            if(export) put("credentials",account().toJson())
        }
        return when(action) {
            "status" -> user()
            "restore" -> {
                val next=AccountCredentials.fromJson(body.getString("credentials"))
                val verified=client.profile(next)
                credentials=next;profile=verified;user()
            }
            "qr" -> {
                login?.cancel();credentials=null;profile=null
                smsLogin?.cancel();smsLogin=null
                val next=WebLogin(repository.http);val qr=next.qr()
                login=next;loginID=UUID.randomUUID().toString()
                JSONObject().put("image",Base64.getEncoder().encodeToString(qr.image)).put("expiresIn",qr.expiresIn).put("loginID",loginID)
            }
            "poll" -> {
                require(query["loginID"]==loginID)
                val current=checkNotNull(login)
                if(!current.poll()) JSONObject().put("loggedIn",false)
                else {
                    val device=client.registerDevice() ?: error("账号设备初始化失败")
                    val next=current.credentials().copy(deviceId=device.deviceId,installId=device.installId)
                    val verified=client.profile(next)
                    credentials=next;profile=verified;login=null
                    user(export=true)
                }
            }
            "cancel" -> {
                if(body.optString("loginID")==loginID && loginID.isNotEmpty()) {
                    login?.cancel();login=null;loginID="";credentials=null;profile=null
                }
                smsLogin?.cancel();smsLogin=null
                JSONObject().put("ok",true)
            }
            "sms/send" -> {
                val raw=body.optString("mobile").trim()
                require(raw.isNotEmpty()) { "请输入手机号" }
                val mobile=PhoneLogin.parse(raw)
                smsLogin?.cancel()
                val web=WebLogin(repository.http)
                val sent=try { web.send(mobile) } catch(problem: Exception) { web.cancel();throw problem }
                smsLogin=web
                JSONObject().put("ok",true).put("masked",sent.maskedMobile).put("retrySeconds",sent.retrySeconds)
            }
            "sms/verify" -> {
                val current=checkNotNull(smsLogin) { "请先获取验证码" }
                val code=body.optString("code").trim()
                require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4—8 位验证码" }
                // A wrong code keeps the session alive so the user can retype it; the core
                // raises PhoneLogin.Failure(canRetryCode) exactly for that case.
                val next=current.verify(code)
                val device=client.registerDevice() ?: error("账号设备初始化失败")
                val verified=client.profile(next.copy(deviceId=device.deviceId,installId=device.installId))
                credentials=next;profile=verified;smsLogin=null
                user(export=true)
            }
            "logout" -> {
                login?.cancel();login=null;loginID=""
                smsLogin?.cancel();smsLogin=null
                credentials=null;profile=null;JSONObject().put("ok",true)
            }
            "history" -> JSONObject().put("items",JSONArray(client.history(account()).map {
                JSONObject().put("series",it.series.toJson()).put("episodeID",it.episodeId).put("episodeIndex",it.episodeIndex)
                    .put("position",it.position/1000.0).put("duration",it.duration/1000.0)
            }))
            "favorites" -> JSONObject().put("items",JSONArray(client.favorites(account()).map { JSONObject().put("series",it.series.toJson()) }))
            "favorite" -> {
                val id=body.getString("id");val add=body.getBoolean("add")
                client.setFavorite(account(),id,add)
                val confirmed=client.favorites(account()).any { it.series.id==id }
                check(confirmed==add) { "账户收藏尚未确认，请刷新列表核对" }
                JSONObject().put("ok",true)
            }
            "reservations" -> JSONObject().put("items",JSONArray(client.reservations(account(),query["online"]!="false").map {
                JSONObject().put("series",it.series.toJson()).put("online",it.online).put("publishText",it.publishText)
            }))
            "reserve" -> {
                val id=body.getString("id")
                client.reserve(account(),RelatedWork(Series(id,"预约作品"),"",true))
                val confirmed=client.reservations(account(),false).any { it.series.id==id }
                JSONObject().put("ok",true).put("confirmed",confirmed)
            }
            "upload" -> {
                val series=Series.fromJson(body.getJSONObject("series"))
                val progress=CloudProgress(series,body.getString("episodeID"),body.getInt("episodeIndex"),body.getLong("position"),body.getLong("duration"),System.currentTimeMillis())
                client.upload(account(),progress,body.getInt("episodeCount"))
                JSONObject().put("ok",true)
            }
            else -> error("账号操作不受支持")
        }
    }
    @Synchronized fun close() { login?.cancel();smsLogin?.cancel();credentials=null;profile=null }
}
