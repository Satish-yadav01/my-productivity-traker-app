package com.myproductivity.tracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ACTION_ACTIVITY_REMINDER = "com.myproductivity.tracker.ACTIVITY_REMINDER"
private const val ACTION_DAILY_REMINDER = "com.myproductivity.tracker.DAILY_REMINDER"
private const val ACTION_UPDATE_ACTIVITY_STATUS = "com.myproductivity.tracker.UPDATE_ACTIVITY_STATUS"
private const val EXTRA_ACTIVITY_ID = "activityId"
private const val EXTRA_ACTIVITY_STATUS = "activityStatus"
private const val DAILY_REMINDER_ID = 71001
private const val ACTIVITY_CHANNEL_ID = "activity_reminders_v3"
private const val DAILY_CHANNEL_ID = "daily_reminders_v3"
private val SHEET_REMINDER_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

data class ActivityReminder(
    val activityId: String,
    val serverId: String?,
    val title: String,
    val status: String,
    val triggerAtMillis: Long,
    val followUp: Boolean = false,
    val date: String = ""
) {
    fun toJson() = JSONObject()
        .put("activityId", activityId)
        .put("serverId", serverId ?: JSONObject.NULL)
        .put("title", title)
        .put("status", status)
        .put("triggerAtMillis", triggerAtMillis)
        .put("followUp", followUp)
        .put("date", date)

    companion object {
        fun fromJson(json: JSONObject) = ActivityReminder(
            activityId = json.optString("activityId"),
            serverId = json.optString("serverId").takeIf { it.isNotBlank() && it != "null" },
            title = json.optString("title"),
            status = json.optString("status"),
            triggerAtMillis = json.optLong("triggerAtMillis"),
            followUp = json.optBoolean("followUp"),
            date = json.optString("date")
        )
    }
}

class ReminderStore(context: Context) {
    private val file = File(context.filesDir, "activity_reminders.json")
    private val lock = Any()

    fun all(): List<ActivityReminder> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { ActivityReminder.fromJson(array.getJSONObject(it)) }
    }

    fun find(id: String): ActivityReminder? = synchronized(lock) {
        all().firstOrNull { it.activityId == id || it.serverId == id }
    }

    fun save(reminder: ActivityReminder) = synchronized(lock) {
        write(all().filterNot { it.activityId == reminder.activityId } + reminder)
    }

    fun linkServerId(activityId: String, serverId: String) = synchronized(lock) {
        val current = all().firstOrNull { it.activityId == activityId } ?: return
        write(all().map {
            if (it.activityId == activityId) it.copy(serverId = serverId) else it
        })
    }

    fun remove(id: String): ActivityReminder? = synchronized(lock) {
        val items = all()
        val found = items.firstOrNull { it.activityId == id || it.serverId == id }
        if (found != null) write(items.filterNot { it.activityId == found.activityId })
        found
    }

    private fun write(items: List<ActivityReminder>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        val temporary = File(file.parentFile, "activity_reminders.json.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(array.toString())
            temporary.delete()
        }
    }
}

object ReminderScheduler {
    private const val PREFS = "reminder_preferences"
    private const val KEY_BEDTIME_MINUTES = "bedtime_minutes"
    private const val KEY_DAILY_ENABLED = "daily_enabled"
    private val clockFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun bedtimeMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BEDTIME_MINUTES, 0)

    fun dailyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DAILY_ENABLED, true)

    fun saveDailySettings(context: Context, enabled: Boolean, bedtimeMinutes: Int) {
        require(bedtimeMinutes in 0 until 24 * 60)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DAILY_ENABLED, enabled)
            .putInt(KEY_BEDTIME_MINUTES, bedtimeMinutes)
            .apply()
        scheduleDaily(context)
    }

    fun defaultDelayMinutes(): Int = 120

    fun activityReminderTriggerAt(
        nowMillis: Long,
        activityDate: String,
        delayMinutes: Int
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(Config.ZONE)
        val requested = Instant.ofEpochMilli(nowMillis + delayMinutes * 60_000L).atZone(Config.ZONE)
        val date = runCatching { LocalDate.parse(activityDate) }.getOrNull()
        if (date == null || !date.isAfter(now.toLocalDate())) return requested.toInstant().toEpochMilli()
        val trigger = if (requested.toLocalDate() == date) {
            requested
        } else {
            date.atTime(requested.toLocalTime()).atZone(Config.ZONE)
        }
        return trigger.toInstant().toEpochMilli()
    }

    fun reminderPreview(activityDate: String, delayMinutes: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val trigger = Instant.ofEpochMilli(activityReminderTriggerAt(nowMillis, activityDate, delayMinutes))
        return DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)
            .format(trigger.atZone(Config.ZONE))
    }

    fun reminderAt(activityDate: String, delayMinutes: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val triggerAt = activityReminderTriggerAt(nowMillis, activityDate, delayMinutes)
        return formatReminderTimestamp(triggerAt)
    }

    private fun formatReminderTimestamp(triggerAtMillis: Long): String =
        Instant.ofEpochMilli(triggerAtMillis).atZone(Config.ZONE).format(SHEET_REMINDER_FORMAT)

    fun delayForSeekProgress(progress: Int): Int = progress.coerceIn(0, 719) + 1

    fun formatDelay(minutes: Int): String {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return when {
            hours == 0 -> "$remainingMinutes min"
            remainingMinutes == 0 -> "$hours hr"
            else -> "$hours hr $remainingMinutes min"
        }
    }

    fun formatReminderAt(value: String): String? =
        runCatching {
            LocalDateTime.parse(value, SHEET_REMINDER_FORMAT)
                .atZone(Config.ZONE)
                .format(clockFormatter)
        }.getOrNull()

    fun minutesUntilReminder(value: String): Int? =
        runCatching {
            val reminderAt = LocalDateTime.parse(value, SHEET_REMINDER_FORMAT).atZone(Config.ZONE)
            val remainingMillis = Duration.between(java.time.Instant.now(), reminderAt.toInstant()).toMillis()
            if (remainingMillis <= 0) null
            else kotlin.math.ceil(remainingMillis / 60_000.0).toInt().coerceIn(1, 720)
        }.getOrNull()

    fun scheduleActivity(context: Context, activity: Activity, delayMinutes: Int): Activity {
        return scheduleActivity(context, activity, delayMinutes, scheduleLocally = true)
    }

    fun scheduleActivity(
        context: Context,
        activity: Activity,
        delayMinutes: Int,
        scheduleLocally: Boolean
    ): Activity {
        cancelActivity(context, activity.clientId)
        if (activity.status == Config.STATUS_COMPLETED || delayMinutes <= 0) {
            return activity.copy(reminderAt = null)
        }
        val triggerAt = activityReminderTriggerAt(System.currentTimeMillis(), activity.date, delayMinutes)
        val reminderAt = formatReminderTimestamp(triggerAt)
        if (scheduleLocally) {
            val reminder = ActivityReminder(
                activityId = activity.clientId,
                serverId = null,
                title = activity.name,
                status = activity.status,
                triggerAtMillis = triggerAt,
                date = activity.date
            )
            ReminderStore(context).save(reminder)
            scheduleActivityAlarm(context, reminder)
        }
        return activity.copy(reminderAt = reminderAt)
    }

    fun updateActivity(
        context: Context,
        id: String,
        activity: Activity,
        delayMinutes: Int?,
        scheduleLocally: Boolean = true
    ): Activity {
        val store = ReminderStore(context)
        val existing = store.find(id)
        cancelActivity(context, id)
        if (activity.status == Config.STATUS_COMPLETED || delayMinutes == null || delayMinutes <= 0) {
            return activity.copy(reminderAt = null)
        }
        val triggerAt = activityReminderTriggerAt(System.currentTimeMillis(), activity.date, delayMinutes)
        val reminderAt = formatReminderTimestamp(triggerAt)
        if (scheduleLocally) {
            val reminder = ActivityReminder(
                activityId = existing?.activityId ?: activity.clientId,
                serverId = existing?.serverId,
                title = activity.name,
                status = activity.status,
                triggerAtMillis = triggerAt,
                date = activity.date
            )
            store.save(reminder)
            scheduleActivityAlarm(context, reminder)
        }
        return activity.copy(reminderAt = reminderAt)
    }

    fun cancelActivity(context: Context, id: String) {
        val reminder = ReminderStore(context).remove(id) ?: return
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = activityPendingIntent(
            context,
            reminder.activityId,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.cancel(pendingIntent)
        NotificationManagerCompat.from(context).apply {
            cancel(reminder.activityId.hashCode())
            cancel(reminder.activityId.hashCode() xor 0x20000000)
        }
    }

    fun linkServerId(context: Context, activityId: String, serverId: String) {
        ReminderStore(context).linkServerId(activityId, serverId)
    }

    fun updateReminderStatus(context: Context, id: String, status: String) {
        val store = ReminderStore(context)
        store.find(id)?.let { store.save(it.copy(status = status)) }
    }

    fun scheduleAllActivities(context: Context) {
        val store = ReminderStore(context)
        val now = System.currentTimeMillis()
        store.all().forEach { reminder ->
            if (reminder.triggerAtMillis > now) scheduleActivityAlarm(context, reminder)
            else cancelActivity(context, reminder.activityId)
        }
    }

    fun scheduleDaily(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = dailyPendingIntent(context)
        manager.cancel(pendingIntent)
        if (!dailyEnabled(context)) return

        val zone = Config.ZONE
        val bedtime = LocalTime.of(bedtimeMinutes(context) / 60, bedtimeMinutes(context) % 60)
        val reminderTime = bedtime.minusHours(1)
        val now = LocalDateTime.now(zone)
        var next = now.toLocalDate().atTime(reminderTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(zone).toInstant().toEpochMilli()
        if (canScheduleExactAlarms(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, dailyPendingIntent(context))
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, dailyPendingIntent(context))
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, dailyPendingIntent(context))
        }
    }

    fun postActivityNotification(context: Context, reminder: ActivityReminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannels(context)
        val openApp = PendingIntent.getActivity(
            context,
            reminder.activityId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextStatus = if (reminder.status == Config.STATUS_IN_PROGRESS) {
            Config.STATUS_PLANNED
        } else {
            Config.STATUS_IN_PROGRESS
        }
        val statusAction = statusAction(context, reminder, nextStatus)
        val completedAction = statusAction(context, reminder, Config.STATUS_COMPLETED)
        NotificationManagerCompat.from(context).notify(
            activityNotificationId(reminder),
            NotificationCompat.Builder(context, ACTIVITY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_today)
                .setContentTitle("Time to work on ${reminder.title}")
                .setContentText("This activity is ${reminder.status.lowercase(Locale.ENGLISH)}.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Your reminder for “${reminder.title}” is here."))
                .setContentIntent(openApp)
                .addAction(R.drawable.ic_today, nextStatus, statusAction)
                .addAction(R.drawable.ic_today, "Complete", completedAction)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
        )
    }

    fun postDailyNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val tomorrow = Config.today().plusDays(1).toString()
        val pendingItems = PendingStore(context).load().filter { it.activity.date == tomorrow }
        val replacedIds = pendingItems.flatMap { listOfNotNull(it.activity.clientId, it.serverId) }.toSet()
        val date = LocalDate.parse(tomorrow)
        val cached = ReportCache(context).getRange(date, date)
            .filterNot { it.clientId in replacedIds }
        val pending = pendingItems.map { it.activity }
        val activities = (cached + pending)
            .filter { it.status == Config.STATUS_PLANNED || it.status == Config.STATUS_IN_PROGRESS }
            .distinctBy { it.clientId }

        createChannels(context)
        val names = activities.take(5).joinToString("\n") { "• ${it.name}" }
        val extra = activities.size - 5
        val details = when {
            activities.isEmpty() -> "Take a moment to plan what you want to do tomorrow."
            extra > 0 -> "$names\nand $extra more"
            else -> names
        }
        val bedtime = LocalTime.of(bedtimeMinutes(context) / 60, bedtimeMinutes(context) % 60)
        val openApp = PendingIntent.getActivity(
            context,
            DAILY_REMINDER_ID,
            Intent(context, ConfirmActivity::class.java)
                .putExtra(ConfirmActivity.EXTRA_DEFAULT_DATE, tomorrow)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            DAILY_REMINDER_ID,
            NotificationCompat.Builder(context, DAILY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_today)
                .setContentTitle("Plan for tomorrow")
                .setContentText("${activities.size} activities planned · bedtime ${bedtime.format(clockFormatter)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$details\nBedtime: ${bedtime.format(clockFormatter)}"))
                .setContentIntent(openApp)
                .addAction(R.drawable.ic_today, "Plan tomorrow", openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
        )
    }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postStatusUpdateFailure(context: Context, reminder: ActivityReminder, message: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannels(context)
        NotificationManagerCompat.from(context).notify(
            reminder.activityId.hashCode() xor 0x40000000,
            NotificationCompat.Builder(context, ACTIVITY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_today)
                .setContentTitle("Could not update activity status")
                .setContentText(message ?: "Open the app and try again.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("The status for “${reminder.title}” was not confirmed in Google Sheets. ${message.orEmpty()}"))
                .setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
        )
    }

    fun postActivityCompleted(context: Context, reminder: ActivityReminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannels(context)
        NotificationManagerCompat.from(context).notify(
            activityNotificationId(reminder),
            NotificationCompat.Builder(context, ACTIVITY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_today)
                .setContentTitle("Congratulations!")
                .setContentText("“${reminder.title}” is complete.")
                .setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    internal fun scheduleActivityAlarm(context: Context, reminder: ActivityReminder) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = activityPendingIntent(context, reminder.activityId, PendingIntent.FLAG_UPDATE_CURRENT)
        if (canScheduleExactAlarms(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
        }
    }

    private fun activityPendingIntent(context: Context, id: String, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ActivityReminderReceiver::class.java)
                .setAction(ACTION_ACTIVITY_REMINDER)
                .setData(android.net.Uri.parse("tracker://activity-reminder/$id"))
                .putExtra(EXTRA_ACTIVITY_ID, id),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    private fun dailyPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            DAILY_REMINDER_ID,
            Intent(context, DailyReminderReceiver::class.java)
                .setAction(ACTION_DAILY_REMINDER)
                .setData(android.net.Uri.parse("tracker://daily-reminder")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(ACTIVITY_CHANNEL_ID, "Activity reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    setSound(sound, audioAttributes)
                    enableVibration(true)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(DAILY_CHANNEL_ID, "Planning reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    setSound(sound, audioAttributes)
                    enableVibration(true)
                }
        )
    }

    internal fun activityNotificationId(reminder: ActivityReminder): Int =
        reminder.activityId.hashCode() xor if (reminder.followUp) 0x20000000 else 0

    private fun statusAction(context: Context, reminder: ActivityReminder, status: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.activityId.hashCode() xor status.hashCode(),
            Intent(context, ActivityStatusActionReceiver::class.java)
                .setAction(ACTION_UPDATE_ACTIVITY_STATUS)
                .setData(android.net.Uri.parse("tracker://activity-status/${reminder.activityId}/$status"))
                .putExtra(EXTRA_ACTIVITY_ID, reminder.activityId)
                .putExtra(EXTRA_ACTIVITY_STATUS, status),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ActivityReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        val store = ReminderStore(context)
        val reminder = store.find(id) ?: return
        if (reminder.status == Config.STATUS_COMPLETED) {
            store.remove(id)
            return
        }
        if (reminder.followUp) {
            store.remove(id)
            if (reminder.status != Config.STATUS_IN_PROGRESS) return
        } else {
            val followUp = reminder.copy(
                followUp = true,
                triggerAtMillis = System.currentTimeMillis() + 60 * 60_000L
            )
            store.save(followUp)
            ReminderScheduler.scheduleActivityAlarm(context, followUp)
        }
        ReminderScheduler.postActivityNotification(context, reminder)
    }
}

class ActivityStatusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        val status = intent.getStringExtra(EXTRA_ACTIVITY_STATUS) ?: return
        if (status !in setOf(Config.STATUS_PLANNED, Config.STATUS_IN_PROGRESS, Config.STATUS_COMPLETED)) return
        val reminder = ReminderStore(context).find(id) ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val pendingStore = PendingStore(context)
                if (pendingStore.updateStatus(id, status)) {
                    if (status == Config.STATUS_COMPLETED) ReminderScheduler.cancelActivity(context, id)
                    else ReminderScheduler.updateReminderStatus(context, id, status)
                    val flush = SaveCoordinator(SheetsApi(SecureSettings(context)), pendingStore, context).flush()
                    if (flush.stillPending > 0) {
                        ReminderScheduler.postStatusUpdateFailure(context, reminder, flush.firstError)
                        return@launch
                    }
                } else {
                    val api = SheetsApi(SecureSettings(context))
                    val serverId = reminder.serverId ?: api.resolveActivityId(
                        clientId = reminder.activityId,
                        title = reminder.title,
                        date = reminder.date,
                        triggerAtMillis = reminder.triggerAtMillis
                    ).also { ReminderScheduler.linkServerId(context, reminder.activityId, it) }
                    if (status == Config.STATUS_COMPLETED) api.markCompleted(serverId)
                    else api.updateStatus(serverId, status)
                }

                if (status == Config.STATUS_COMPLETED) {
                    ReminderScheduler.cancelActivity(context, id)
                    ReminderScheduler.postActivityCompleted(context, reminder)
                } else {
                    ReminderScheduler.updateReminderStatus(context, id, status)
                    NotificationManagerCompat.from(context).apply {
                        cancel(id.hashCode())
                        cancel(id.hashCode() xor 0x20000000)
                    }
                }
            } catch (e: Exception) {
                ReminderScheduler.postStatusUpdateFailure(context, reminder, e.message)
            } finally {
                result.finish()
            }
        }
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.scheduleDaily(context)
        ReminderScheduler.postDailyNotification(context)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED
            )
        ) return
        ReminderScheduler.scheduleAllActivities(context)
        ReminderScheduler.scheduleDaily(context)
    }
}
