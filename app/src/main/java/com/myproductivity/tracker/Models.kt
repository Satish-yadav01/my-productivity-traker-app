package com.myproductivity.tracker

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** App-wide constants. Edit the lists here if your sheet uses different labels. */
object Config {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    val CATEGORIES = listOf(
        "Career & Learning",
        "Fitness & Health",
        "Personal Development",
        "Work",
        "Personal Tasks"
    )
    val STATUSES = listOf("Planned", "In Progress", "Completed")
    val PRIORITIES = listOf("High", "Medium", "Low")

    const val STATUS_PLANNED = "Planned"
    const val STATUS_IN_PROGRESS = "In Progress"
    const val STATUS_COMPLETED = "Completed"

    fun today(): LocalDate = LocalDate.now(ZONE)
}

/** A problem found by the parser. Blocking issues must be fixed before saving. */
data class Issue(val field: String, val message: String, val blocking: Boolean)

/** Output of the rule-based parser, before the user confirms anything. */
data class ParsedActivity(
    val name: String,
    val category: String?,          // null = could not decide
    val date: LocalDate,
    val plannedMin: Int?,
    val actualMin: Int?,
    val priority: String,
    val status: String,
    val issues: List<Issue>
)

/** A confirmed activity, ready to be sent to Google Sheets. */
data class Activity(
    val clientId: String,           // idempotency key — the server ignores repeats
    val name: String,
    val category: String,
    val date: String,               // yyyy-MM-dd (Asia/Kolkata)
    val plannedMin: Int?,
    val actualMin: Int?,
    val priority: String,
    val status: String,
    val reminderAt: String? = null
) {
    /** Used to warn about accidental duplicates. */
    fun fingerprint(): String =
        listOf(date, name.trim().lowercase(), plannedMin ?: "", actualMin ?: "").joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("clientId", clientId)
        put("name", name)
        put("category", category)
        put("date", date)
        put("plannedMin", plannedMin ?: JSONObject.NULL)
        put("actualMin", actualMin ?: JSONObject.NULL)
        put("priority", priority)
        put("status", status)
        put("reminderAt", reminderAt ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = Activity(
            clientId = o.optString("clientId", ""),
            name = o.optString("name", ""),
            category = o.optString("category", ""),
            date = o.optString("date", ""),
            plannedMin = if (o.isNull("plannedMin")) null else o.optInt("plannedMin"),
            actualMin = if (o.isNull("actualMin")) null else o.optInt("actualMin"),
            priority = o.optString("priority", "Medium"),
            status = o.optString("status", "Planned"),
            reminderAt = if (o.isNull("reminderAt")) null else o.optString("reminderAt").takeIf { it.isNotBlank() }
        )
    }
}

/** An activity that has been confirmed but not fully written to the sheet yet. */
data class PendingItem(
    val activity: Activity,
    val serverId: String? = null,      // Activity_ID returned by the sheet once "add" succeeded
    val timeLogged: Boolean = false,
    val statusSet: Boolean = false,
    val activityUpdated: Boolean = true,
    val lastError: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("activity", activity.toJson())
        put("serverId", serverId ?: JSONObject.NULL)
        put("timeLogged", timeLogged)
        put("statusSet", statusSet)
        put("activityUpdated", activityUpdated)
        put("lastError", lastError ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingItem(
            activity = Activity.fromJson(o.getJSONObject("activity")),
            serverId = if (o.isNull("serverId")) null else o.optString("serverId"),
            timeLogged = o.optBoolean("timeLogged"),
            statusSet = o.optBoolean("statusSet"),
            activityUpdated = o.optBoolean("activityUpdated", true),
            lastError = if (o.isNull("lastError")) null else o.optString("lastError")
        )
    }
}

fun formatMinutes(min: Int?): String {
    if (min == null || min <= 0) return "—"
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
}
