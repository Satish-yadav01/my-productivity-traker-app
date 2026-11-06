package com.myproductivity.tracker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * API URL + token, encrypted at rest with a key held in the Android Keystore.
 * Nothing is hard-coded in the source or the APK.
 */
class SecureSettings(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiUrl: String
        get() = prefs.getString("api_url", "") ?: ""
        set(v) = prefs.edit().putString("api_url", v.trim()).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    val isConfigured: Boolean get() = apiUrl.startsWith("https://") && token.isNotEmpty()
}

/**
 * Activities the user confirmed but that are not fully in the sheet yet.
 * Persisted to disk immediately, so nothing is lost if the network or the app dies.
 */
class PendingStore(context: Context) {
    private val file = File(context.filesDir, "pending.json")
    private val recentFile = File(context.filesDir, "recent_fingerprints.json")
    private val lock = Any()

    fun load(): List<PendingItem> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { PendingItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(items: List<PendingItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "pending.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {   // atomic-ish replace
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    fun addAll(items: List<PendingItem>) = synchronized(lock) {
        val current = load().toMutableList()
        items.forEach { n -> if (current.none { it.activity.clientId == n.activity.clientId }) current += n }
        write(current)
    }

    fun update(item: PendingItem) = synchronized(lock) {
        write(load().map { if (it.activity.clientId == item.activity.clientId) item else it })
    }

    fun findActivity(activityId: String): PendingItem? = synchronized(lock) {
        load().firstOrNull {
            it.activity.clientId == activityId || it.serverId == activityId
        }
    }

    fun updateActivity(activity: Activity): PendingItem? = synchronized(lock) {
        val items = load()
        val current = items.firstOrNull {
            it.activity.clientId == activity.clientId || it.serverId == activity.clientId
        } ?: return null
        val updated = current.copy(
            activity = activity.copy(clientId = current.activity.clientId),
            statusSet = false,
            activityUpdated = current.serverId == null
        )
        write(items.map { if (it.activity.clientId == current.activity.clientId) updated else it })
        updated
    }

    fun remove(clientId: String) = synchronized(lock) {
        write(load().filterNot { it.activity.clientId == clientId })
    }

    fun removeActivity(activityId: String, serverId: String) = synchronized(lock) {
        write(
            load().filterNot {
                it.activity.clientId == activityId ||
                    it.activity.clientId == serverId ||
                    it.serverId == serverId
            }
        )
    }

    // ---- duplicate detection (remembers what was saved in the last 24 hours)

    fun rememberSaved(a: Activity) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val obj = readRecent().also { it.put(a.fingerprint(), now) }
        recentFile.writeText(prune(obj, now).toString())
    }

    fun isLikelyDuplicate(a: Activity): Boolean = synchronized(lock) {
        val fp = a.fingerprint()
        val now = System.currentTimeMillis()
        val seen = readRecent()
        (seen.has(fp) && now - seen.optLong(fp) < DAY_MS) ||
            load().any { it.activity.clientId != a.clientId && it.activity.fingerprint() == fp }
    }

    private fun readRecent(): JSONObject =
        try { if (recentFile.exists()) JSONObject(recentFile.readText()) else JSONObject() }
        catch (e: Exception) { JSONObject() }

    private fun prune(o: JSONObject, now: Long): JSONObject {
        val out = JSONObject()
        o.keys().forEach { k -> if (now - o.optLong(k) < DAY_MS) out.put(k, o.optLong(k)) }
        return out
    }

    private companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}

/** Last successful "today" response, so the home screen still shows something offline. */
class TodayCache(context: Context) {
    private val file = File(context.filesDir, "today_cache.json")

    fun save(date: String, list: List<Activity>, syncedAtMillis: Long) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(
            JSONObject().put("date", date).put("syncedAt", syncedAtMillis).put("items", arr).toString()
        )
    }

    fun removeActivity(date: String, vararg activityIds: String?) {
        val cached = load(date) ?: return
        val ids = activityIds.filterNotNull().filter { it.isNotBlank() }.toSet()
        save(date, cached.first.filterNot { it.clientId in ids }, cached.second)
    }

    /** Returns null when there is no cache or it belongs to another day. */
    fun load(date: String): Pair<List<Activity>, Long>? = try {
        if (!file.exists()) null else {
            val o = JSONObject(file.readText())
            if (o.optString("date") != date) null else {
                val arr = o.getJSONArray("items")
                (0 until arr.length()).map { Activity.fromJson(arr.getJSONObject(it)) } to o.optLong("syncedAt")
            }
        }
    } catch (e: Exception) { null }
}

/** Persistent day-indexed report cache. Empty results are cached too, avoiding repeated day fetches. */
class ReportCache(context: Context) {
    private val file = File(context.filesDir, "report_cache.json")
    private val lock = Any()
    private val cacheLifetimeMillis = 10 * 60 * 1000L

    fun missingDates(start: java.time.LocalDate, end: java.time.LocalDate): List<java.time.LocalDate> =
        synchronized(lock) {
            val data = read()
            val now = System.currentTimeMillis()
            generateSequence(start) { day -> if (day < end) day.plusDays(1) else null }
                .filter { date ->
                    val entry = data.optJSONObject(date.toString())
                    entry == null || now - entry.optLong(KEY_CACHED_AT) >= cacheLifetimeMillis
                }
                .toList()
        }

    fun getRange(start: java.time.LocalDate, end: java.time.LocalDate): List<Activity> =
        synchronized(lock) {
            val data = read()
            generateSequence(start) { day -> if (day < end) day.plusDays(1) else null }
                .flatMap { date ->
                    val array = data.optJSONObject(date.toString())?.optJSONArray(KEY_ITEMS) ?: JSONArray()
                    (0 until array.length()).map { Activity.fromJson(array.getJSONObject(it)) }.asSequence()
                }
                .toList()
        }

    fun save(days: Map<String, List<Activity>>) = synchronized(lock) {
        val data = read()
        val now = System.currentTimeMillis()
        days.forEach { (date, activities) ->
            val array = JSONArray()
            activities.forEach { array.put(it.toJson()) }
            data.put(date, JSONObject().put(KEY_CACHED_AT, now).put(KEY_ITEMS, array))
        }
        val temporary = File(file.parentFile, "report_cache.json.tmp")
        temporary.writeText(data.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(data.toString())
            temporary.delete()
        }
    }

    private fun read(): JSONObject =
        try {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

    private companion object {
        const val KEY_CACHED_AT = "cachedAt"
        const val KEY_ITEMS = "items"
    }
}
