package com.myproductivity.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Matches the existing Productivity-tracker Apps Script (Code.gs):
 *   request : POST <exec url>  {"secret": "...", "action": "...", ...fields}
 *   reply   : {"success":true,"result":...}  or  {"success":false,"error":"..."}
 */
object ApiContract {
    const val ACTION_LIST_DAY = "list_day"
    const val ACTION_ADD = "add"
    const val ACTION_UPDATE = "update"
    const val ACTION_COMPLETE = "complete"
    const val ACTION_DELETE = "delete"
    const val ACTION_LOG_TIME = "log_time"
}

/**
 * [retryable] = network/server trouble: keep the item queued and try again later.
 * [auth] = wrong secret / URL: nothing will work until Settings are fixed.
 * Neither = the script rejected this one activity (e.g. invalid category).
 */
class ApiException(message: String, val retryable: Boolean, val auth: Boolean = false) : Exception(message)

class SheetsApi(private val settings: SecureSettings) {

    // ------------------------------------------------------------ public calls

    /** Connection test: a real, authenticated read, so it also proves the secret is right. */
    suspend fun ping(): String {
        val n = rawDay(Config.today().toString()).length()
        return "Connected. $n activities today."
    }

    suspend fun getToday(date: String): List<Activity> {
        val arr = rawDay(date)
        return (0 until arr.length()).map { toActivity(arr.getJSONObject(it)) }
    }

    /** Uses the existing day-list endpoint so historical ranges need no new script action. */
    suspend fun getRange(start: java.time.LocalDate, end: java.time.LocalDate): List<Activity> {
        require(!end.isBefore(start)) { "The end date must not be before the start date." }
        val dates = generateSequence(start) { day -> if (day < end) day.plusDays(1) else null }.toList()
        return getDates(dates).values.flatten()
    }

    suspend fun getDates(dates: List<java.time.LocalDate>): Map<String, List<Activity>> {
        val semaphore = Semaphore(4)
        return coroutineScope {
            dates.map { date ->
                async { date.toString() to semaphore.withPermit { getToday(date.toString()) } }
            }.awaitAll().toMap()
        }
    }

    /** Adds the activity (status "Planned" on the sheet) and returns the sheet's Activity_ID. */
    suspend fun addActivity(a: Activity): String {
        val f = JSONObject()
            .put("date", a.date)
            .put("activity", a.name)
            .put("category", a.category)
            .put("priority", a.priority)
            .put("planned_duration_min", a.plannedMin ?: 0)
            .put("notes", marker(a))
        val r = call(ApiContract.ACTION_ADD, f) as? JSONObject
        return r?.optString("activity_id").orEmpty().ifEmpty {
            throw ApiException("The sheet did not return an Activity_ID.", false)
        }
    }

    /**
     * After a reply was lost we cannot know whether "add" worked. Look for our own marker
     * in the day's rows so a retry does not create a second row.
     */
    suspend fun findExisting(a: Activity): String? {
        val arr = rawDay(a.date)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("notes").contains(a.clientId)) return o.optString("activity_id")
        }
        return null
    }

    /** Appends to Time_Log and adds the minutes to the activity's Actual_Duration_Min. */
    suspend fun logTime(serverId: String, a: Activity) {
        val f = JSONObject()
            .put("activity_id", serverId)
            .put("date", a.date)
            .put("duration_min", a.actualMin ?: 0)
            .put("notes", marker(a))
        call(ApiContract.ACTION_LOG_TIME, f)
    }

    suspend fun markCompleted(serverId: String) {
        call(ApiContract.ACTION_COMPLETE, JSONObject().put("activity_id", serverId))
    }

    suspend fun deleteActivity(serverId: String) {
        call(ApiContract.ACTION_DELETE, JSONObject().put("activity_id", serverId))
    }

    suspend fun markInProgress(serverId: String) {
        call(
            ApiContract.ACTION_UPDATE,
            JSONObject().put("activity_id", serverId).put("status", Config.STATUS_IN_PROGRESS)
        )
    }

    /** Updates an existing sheet row. The Apps Script update action must accept these fields. */
    suspend fun updateActivity(serverId: String, activity: Activity, includeActual: Boolean = true) {
        val fields = JSONObject()
            .put("activity_id", serverId)
            .put("date", activity.date)
            .put("activity", activity.name)
            .put("category", activity.category)
            .put("priority", activity.priority)
            .put("status", activity.status)
            .put("planned_duration_min", activity.plannedMin ?: 0)
        if (includeActual) fields.put("actual_duration_min", activity.actualMin ?: 0)
        call(ApiContract.ACTION_UPDATE, fields)
    }

    // ------------------------------------------------------------ helpers

    private fun marker(a: Activity) = "via app ${a.clientId}"

    private suspend fun rawDay(date: String): JSONArray =
        (call(ApiContract.ACTION_LIST_DAY, JSONObject().put("date", date)) as? JSONArray) ?: JSONArray()

    private fun toActivity(o: JSONObject): Activity {
        fun mins(key: String): Int? {
            val v = o.optDouble(key, 0.0)
            return if (v > 0) Math.round(v).toInt() else null
        }
        return Activity(
            clientId = o.optString("activity_id"),
            name = o.optString("activity"),
            category = o.optString("category"),
            date = normalizeDate(o.opt("date")),
            plannedMin = mins("planned_duration_min"),
            actualMin = mins("actual_duration_min"),
            priority = o.optString("priority").ifEmpty { "Medium" },
            status = o.optString("status").ifEmpty { "Planned" }
        )
    }

    /** Sheets may hand back a real date (ISO timestamp) or plain text. Normalise to yyyy-MM-dd in IST. */
    private fun normalizeDate(v: Any?): String {
        val s = v?.toString().orEmpty()
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return s
        return try {
            Instant.parse(s).atZone(Config.ZONE).toLocalDate().toString()
        } catch (e: Exception) {
            s.take(10)
        }
    }

    // ------------------------------------------------------------ transport

    private suspend fun call(action: String, fields: JSONObject): Any =
        withContext(Dispatchers.IO) {
            val url = settings.apiUrl
            if (!url.startsWith("https://")) {
                throw ApiException("Open Settings and enter your Apps Script URL (must start with https://).", false, auth = true)
            }
            if (settings.token.isEmpty()) {
                throw ApiException("Open Settings and enter your secret.", false, auth = true)
            }

            val body = JSONObject(fields.toString())
                .put("secret", settings.token)   // your script reads body.secret
                .put("action", action)

            val raw = try {
                post(url, body.toString())
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                throw ApiException("No internet or the server did not answer. Your data is safe on this phone.", true)
            }

            val json = try { JSONObject(raw) } catch (e: Exception) {
                throw ApiException(
                    "The URL did not return the expected reply. Check the web app URL and that access is set to \"Anyone\".",
                    false, auth = true
                )
            }
            if (!json.optBoolean("success", false)) {
                val msg = json.optString("error", "The script reported an error.")
                if (action == ApiContract.ACTION_DELETE && msg.contains("unsupported action", true)) {
                    throw ApiException(
                        "Deletion is not enabled in the Apps Script yet. Add the delete action to handleAction and deploy a new version.",
                        false
                    )
                }
                val authProblem = msg.contains("Unauthorized", true)
                val transient = msg.contains("too many", true) || msg.contains("timed out", true) ||
                    msg.contains("try again", true) || msg.contains("lock", true)
                throw ApiException(
                    if (authProblem) "Unauthorized: the secret in Settings does not match API_SECRET in your script." else msg,
                    retryable = transient,
                    auth = authProblem
                )
            }
            json.opt("result") ?: JSONObject()
        }

    /**
     * Apps Script answers a POST with a 302 redirect to a one-time result URL that must be
     * fetched with GET. HttpURLConnection would resend the POST, so redirects are done by hand.
     */
    private fun post(startUrl: String, body: String): String {
        var conn = open(startUrl, "POST")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        var hops = 0
        while (conn.responseCode in intArrayOf(301, 302, 303, 307, 308)) {
            if (++hops > 5) throw ApiException("Too many redirects from the server.", false)
            val next = conn.getHeaderField("Location")
                ?: throw ApiException("Redirect without a location.", false)
            conn.disconnect()
            if (!next.startsWith("https://")) throw ApiException("Refusing a non-HTTPS redirect.", false)
            conn = open(next, "GET")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code >= 500) throw ApiException("Server error ($code). Will retry.", true)
        if (code == 401 || code == 403) {
            throw ApiException(
                "Google refused access ($code). In Deploy → Manage deployments set \"Who has access\" to Anyone, with a New version.",
                false, auth = true
            )
        }
        if (code !in 200..299) throw ApiException("Server replied $code. Check the web app URL and access.", false, auth = true)
        return text
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        return c
    }
}

/**
 * Saves confirmed activities safely:
 *  1. write to the on-disk queue first (nothing can be lost),
 *  2. step by step: add row -> log time -> set status, remembering each finished step,
 *  3. remove from the queue only after every step succeeded.
 * If a reply is lost after "add", the retry first looks for its own marker in the sheet
 * so it cannot create a second row. Your dashboards are formulas, so they update by themselves.
 */
class SaveCoordinator(private val api: SheetsApi, private val store: PendingStore) {

    data class Result(val saved: Int, val stillPending: Int, val firstError: String?, val dashboardOk: Boolean)

    suspend fun enqueueAndFlush(activities: List<Activity>): Result {
        store.addAll(activities.map { PendingItem(it) })
        return flush()
    }

    suspend fun flush(): Result {
        var saved = 0
        var firstError: String? = null
        for (item in store.load()) {
            var cur = item
            try {
                if (cur.serverId == null) {
                    // lastError != null means an earlier attempt may have succeeded unnoticed
                    val id = (if (cur.lastError != null) api.findExisting(cur.activity) else null)
                        ?: api.addActivity(cur.activity)
                    cur = cur.copy(serverId = id, lastError = null)
                    store.update(cur)
                }
                val id = cur.serverId!!
                if (!cur.activityUpdated) {
                    api.updateActivity(
                        id,
                        cur.activity,
                        includeActual = cur.timeLogged || (cur.activity.actualMin ?: 0) <= 0
                    )
                    cur = cur.copy(activityUpdated = true, statusSet = true, lastError = null)
                    store.update(cur)
                }
                if ((cur.activity.actualMin ?: 0) > 0 && !cur.timeLogged) {
                    api.logTime(id, cur.activity)
                    cur = cur.copy(timeLogged = true, lastError = null)
                    store.update(cur)
                }
                if (!cur.statusSet) {
                    when (cur.activity.status) {
                        Config.STATUS_COMPLETED -> api.markCompleted(id)
                        Config.STATUS_IN_PROGRESS -> api.markInProgress(id)
                    }
                    cur = cur.copy(statusSet = true, lastError = null)
                    store.update(cur)
                }
                store.rememberSaved(cur.activity)
                store.remove(cur.activity.clientId)
                saved++
            } catch (e: ApiException) {
                val msg = e.message ?: "Unknown error"
                store.update(cur.copy(lastError = msg))
                if (firstError == null) firstError = msg
                if (e.retryable || e.auth) break     // network or settings problem: stop, keep everything queued
            }
        }
        return Result(saved, store.load().size, firstError, dashboardOk = true)
    }
}
