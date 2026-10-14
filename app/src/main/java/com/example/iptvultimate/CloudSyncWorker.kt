package com.example.iptvultimate

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** User-configured WebDAV sync. It intentionally excludes Xtream/WebDAV passwords and server credentials. */
class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("iptv_ultimate", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("webdav_url", "").orEmpty()
        val username = prefs.getString("webdav_user", "").orEmpty()
        val password = prefs.getString("webdav_pass", "").orEmpty()
        if (!endpoint.startsWith("https://") || username.isBlank() || password.isBlank()) return Result.failure()
        return try {
            val local = snapshot(prefs)
            val remote = request(endpoint, username, password, "GET", null)
            val remoteJson = remote?.let { runCatching { JSONObject(it) }.getOrNull() }
            val localTime = prefs.getLong("cloud_last_local_edit", 0L)
            val remoteTime = remoteJson?.optLong("updatedAt", 0L) ?: 0L
            if (remoteJson != null && remoteTime > localTime) {
                applySnapshot(prefs, remoteJson.optJSONObject("settings") ?: JSONObject())
                prefs.edit().putLong("cloud_last_local_edit", remoteTime).putLong("cloud_last_sync", System.currentTimeMillis()).apply()
            } else {
                val envelope = JSONObject().put("schema", 1).put("updatedAt", maxOf(System.currentTimeMillis(), localTime + 1)).put("settings", local)
                request(endpoint, username, password, "PUT", envelope.toString())
                prefs.edit().putLong("cloud_last_local_edit", envelope.optLong("updatedAt")).putLong("cloud_last_sync", System.currentTimeMillis()).apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun snapshot(prefs: android.content.SharedPreferences): JSONObject {
        val out = JSONObject()
        val all = prefs.all
        listOf("theme", "profile", "favorites", "parental_pin").forEach { key ->
            val value = all[key] ?: return@forEach
            when (value) {
                is String -> out.put(key, value)
                is Set<*> -> out.put(key, JSONArray(value.filterIsInstance<String>()))
            }
        }
        all.filterKeys { it.startsWith("position_") }.forEach { (key, value) -> if (value is Long || value is Int) out.put(key, value) }
        return out
    }

    private fun applySnapshot(prefs: android.content.SharedPreferences, json: JSONObject) {
        val editor = prefs.edit()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in setOf("theme", "profile", "favorites", "parental_pin") && !key.startsWith("position_")) continue
            when (val value = json.opt(key)) {
                is String -> editor.putString(key, value)
                is Number -> editor.putLong(key, value.toLong())
                is JSONArray -> {
                    val values = buildSet { for (i in 0 until value.length()) value.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
                    editor.putStringSet(key, values)
                }
            }
        }
        editor.apply()
    }

    private fun request(endpoint: String, username: String, password: String, method: String, body: String?): String? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP))
            setRequestProperty("User-Agent", "IPTVUltimate/0.3 Android")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (method == "GET" && code == 200) connection.inputStream.bufferedReader().use { it.readText() }
            else if (method == "GET" && code == 404) null
            else if (code in 200..299 || (method == "PUT" && code == 201)) "OK"
            else throw IllegalStateException("WebDAV HTTP $code")
        } finally { connection.disconnect() }
    }
}

object CloudSyncScheduler {
    private const val WORK_NAME = "iptv-ultimate-webdav-sync"
    fun enable(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun disable(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
}
