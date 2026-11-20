package io.todobingo

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.*
import org.json.JSONObject

private val Context.settings by preferencesDataStore("settings")
data class Reminder(val enabled: Boolean, val hour: Int, val minute: Int, val days: Int = 127,
                    val sound: String = "default", val vibrate: Boolean = true, val skip: Boolean = false) {
    fun encode(): JSONObject = JSONObject().put("enabled", enabled).put("hour", hour).put("minute", minute)
        .put("days", days).put("sound", sound).put("vibrate", vibrate).put("skip", skip)
}
object Reminders {
    private fun key(id: Int) = stringPreferencesKey("alarm_$id")
    fun title(id: Int) = if (id == 1) "하루 시작" else "하루 마무리"
    fun decode(json: JSONObject) = Reminder(json.getBoolean("enabled"), json.getInt("hour"), json.getInt("minute"),
        json.getInt("days"), json.getString("sound"), json.getBoolean("vibrate"), json.getBoolean("skip")).also {
        require(it.hour in 0..23 && it.minute in 0..59 && it.days in 0..127)
        require(it.sound in listOf("default", "silent") || Uri.parse(it.sound).scheme == "content")
    }
    suspend fun read(context: Context, id: Int): Reminder {
        val raw = context.settings.data.first()[key(id)]
        return raw?.let { decode(JSONObject(it)) } ?: if (id == 1) Reminder(false, 8, 0) else Reminder(false, 23, 30)
    }
    suspend fun write(context: Context, id: Int, value: Reminder) {
        context.settings.edit { it[key(id)] = value.encode().toString() }
        schedule(context)
    }
    suspend fun restore(context: Context, values: List<Reminder>) {
        context.settings.edit { prefs -> values.forEachIndexed { index, value -> prefs[key(index + 1)] = value.encode().toString() } }
        schedule(context)
    }
    suspend fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now()
        for (id in 0..2) {
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, ScheduleReceiver::class.java)
                .setAction("io.todobingo.ALARM").putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.cancel(pending)
            val next = if (id == 0) now.toLocalDate().plusDays(1).atStartOfDay(now.zone) else {
                val config = read(context, id)
                if (!config.enabled) continue
                (0..7).asSequence().map { now.toLocalDate().plusDays(it.toLong()) }
                    .filter { (config.days and (1 shl (it.dayOfWeek.value - 1))) != 0 }
                    .map { it.atTime(config.hour, config.minute).atZone(now.zone) }
                    .firstOrNull { it.isAfter(now) } ?: continue
            }
            val instant = next.toInstant().toEpochMilli()
            try {
                if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, pending)
                } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, pending)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, pending)
            }
        }
    }
    suspend fun notify(context: Context, id: Int) {
        val config = read(context, id)
        if (!config.enabled || (config.days and (1 shl (LocalDate.now().dayOfWeek.value - 1))) == 0) return
        if (id == 1 && config.skip && Boards.get(context).today().filled > 0) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "reminder_${id}_${config.sound}_${config.vibrate}"
        val channel = NotificationChannel(channelId, title(id), NotificationManager.IMPORTANCE_DEFAULT)
        val sound = when (config.sound) {
            "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            "silent" -> null
            else -> Uri.parse(config.sound)
        }
        channel.setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        channel.enableVibration(config.vibrate)
        manager.createNotificationChannel(channel)
        val pending = PendingIntent.getActivity(context, 100 + id, Intent(context, MainActivity::class.java)
            .setAction(if (id == 1) MainActivity.EDIT else MainActivity.TODAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, Notification.Builder(context, channelId).setSmallIcon(R.drawable.bingo)
            .setContentTitle(title(id)).setContentText(if (id == 1) "오늘 빙고판을 작성해요." else "오늘 남은 할 일을 확인해요.")
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val boards = Boards.get(context)
                boards.today()
                val id = intent.getIntExtra("id", 0)
                if (id in 1..2) Reminders.notify(context, id)
                Reminders.schedule(context)
                boards.refresh()
                updateWidgets(context)
            } finally { result.finish() }
        }
    }
}
