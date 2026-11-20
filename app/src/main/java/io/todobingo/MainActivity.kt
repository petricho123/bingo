package io.todobingo

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val page = mutableStateOf("today")
    private val resumeCount = mutableIntStateOf(0)
    private val store by lazy { Boards.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Back, surface = Back, background = Back)) {
                App()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }
    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
        lifecycleScope.launch {
            store.today()
            store.refresh()
            Reminders.schedule(this@MainActivity)
            updateWidgets(this@MainActivity)
        }
    }
    private fun handle(intent: Intent) { page.value = if (intent.action == EDIT) "edit" else "today" }

    @Composable private fun App() {
        val sequence by store.changes.collectAsState()
        val state by produceState<Pair<LocalDate, Board>?>(null, sequence) {
            val date = LocalDate.now()
            value = date to store.today(date)
        }
        val (date, board) = state ?: return
        val scope = rememberCoroutineScope()
        var editing by remember(date) { mutableStateOf<Int?>(null) }
        var dialog by remember(date) { mutableStateOf("") }
        var rename by remember { mutableStateOf<Preset?>(null) }
        var reminderVersion by remember { mutableIntStateOf(0) }
        var pickedReminder by remember { mutableIntStateOf(0) }
        var restoreUri by remember { mutableStateOf<Uri?>(null) }
        val presets by produceState(emptyList<Preset>(), sequence) { value = store.presets() }
        val yesterday by produceState<Board?>(null, date, sequence) { value = store.yesterday() }
        val reminders by produceState(emptyList<Reminder>(), reminderVersion, resumeCount.intValue) {
            value = (1..2).map { Reminders.read(this@MainActivity, it) }
        }
        fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        fun run(block: suspend () -> Unit) {
            scope.launch {
                try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { message(e.message?.takeIf { it == "이미 같은 이름이 있어요." } ?: "처리하지 못했어요.") }
            }
        }
        fun change(block: (Board) -> Board) = run {
            store.update(date, block)
            updateWidgets(this@MainActivity)
        }
        fun saveReminder(id: Int, value: Reminder) = run {
            Reminders.write(this@MainActivity, id, value)
            reminderVersion++
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reminderVersion++ }
        val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                val id = pickedReminder
                if (id in 1..2) reminders.getOrNull(id - 1)?.let {
                    val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    saveReminder(id, it.copy(sound = when (uri) { null -> "silent"; default -> "default"; else -> uri.toString() }))
                }
            }
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) run {
                val json = store.backup().put("reminders", JSONArray((1..2).map { Reminders.read(this@MainActivity, it).encode() }))
                withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json.toString(2)) }
                }
                message("백업했어요.")
            }
        }
        val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { restoreUri = uri; dialog = "restore" }
        }
        Scaffold(bottomBar = {
            NavigationBar(containerColor = Color(0xFF1B201C)) {
                NavigationBarItem(page.value != "settings", { page.value = "today" }, { Text("▦") }, label = { Text("오늘") })
                NavigationBarItem(page.value == "settings", { page.value = "settings" }, { Text("⚙") }, label = { Text("설정") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (page.value == "settings") "설정" else "TODO BINGO", fontSize = 31.sp, fontWeight = FontWeight.Bold)
                if (page.value != "settings") {
                    Text(dateLabel(date), color = Color.LightGray)
                    if (page.value == "edit") {
                        Text("빙고판 편집", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in 3..5) FilterChip(board.size == n, {
                                if (board.resize(n) == null) message("할 일을 몇 개 지운 뒤 크기를 줄여 주세요.")
                                else change { it.resize(n) ?: it }
                            }, label = { Text("$n × $n") })
                        }
                    } else {
                        val rate = if (board.filled == 0) 0f else board.completed.toFloat() / board.filled
                        Text("오늘의 달성률 ${(rate * 100).roundToInt()}%  ·  ${board.completed}/${board.filled}")
                        LinearProgressIndicator(progress = { rate }, modifier = Modifier.fillMaxWidth())
                        Text(if (board.completed == board.cells.size) "올빙고 · ${board.lines.size}줄" else "빙고 ${board.lines.size}줄", color = Mint)
                    }
                    val editMode = page.value == "edit"
                    Grid(board, editMode, onTap = { index ->
                        val task = board.cells[index]
                        if (editMode || task == null) editing = index
                        else change { current ->
                            val actual = current.cells.indexOfFirst { it?.id == task.id }
                            if (actual == -1) current else current.put(actual, current.cells[actual]?.let { it.copy(done = !it.done) })
                        }
                    }, onEdit = { editing = it }, onMove = { from, to ->
                        change { current ->
                            if (current.size != board.size || current.cells[from]?.id != board.cells[from]?.id) current
                            else current.put(from, current.cells[to]).put(to, current.cells[from])
                        }
                    })
                    if (!editMode) Button({ page.value = "edit" }, Modifier.fillMaxWidth()) { Text("빙고판 편집") }
                    else {
                        Text("칸을 길게 눌러 끌면 위치를 바꿀 수 있어요.", color = Color.LightGray)
                        OutlinedButton({ dialog = "copy" }, Modifier.fillMaxWidth(), enabled = yesterday != null) { Text("전날 복사") }
                        OutlinedButton({ dialog = "carry" }, Modifier.fillMaxWidth(), enabled = yesterday?.cells?.any { it != null && !it.done } == true) { Text("미완료 가져오기") }
                        OutlinedButton({ dialog = "load" }, Modifier.fillMaxWidth(), enabled = presets.isNotEmpty()) { Text("프리셋 불러오기") }
                        OutlinedButton({ rename = null; dialog = "name" }, Modifier.fillMaxWidth(), enabled = board.filled > 0) { Text("프리셋으로 저장") }
                        Button({ page.value = "today" }, Modifier.fillMaxWidth()) { Text("편집 완료") }
                    }
                } else {
                    Text("알림", style = MaterialTheme.typography.titleLarge)
                    val manager = getSystemService(NotificationManager::class.java)
                    if (!manager.areNotificationsEnabled()) OutlinedButton({
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            !getPreferences(MODE_PRIVATE).getBoolean("asked", false)) {
                            getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply()
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }) { Text("알림 권한 설정") }
                    if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                        OutlinedButton({ startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }) {
                            Text("정확한 알람 허용")
                        }
                    }
                    reminders.forEachIndexed { index, config ->
                        val id = index + 1
                        ReminderCard(id, config, { saveReminder(id, it) }, onSound = {
                            pickedReminder = id
                            ringtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, when (config.sound) {
                                    "silent" -> null; "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                    else -> Uri.parse(config.sound)
                                }))
                        })
                    }
                    HorizontalDivider()
                    Text("프리셋 관리", style = MaterialTheme.typography.titleLarge)
                    if (presets.isEmpty()) Text("저장된 프리셋이 없어요.")
                    presets.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(preset.name, Modifier.weight(1f))
                            TextButton({ rename = preset; dialog = "name" }) { Text("이름 변경") }
                            TextButton({ rename = preset; dialog = "deletePreset" }) { Text("삭제") }
                        }
                    }
                    HorizontalDivider()
                    Text("데이터 관리", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton({ dialog = "past" }, Modifier.fillMaxWidth()) { Text("지난 빙고판 삭제") }
                    OutlinedButton({ export.launch("todo-bingo-$date.json") }, Modifier.fillMaxWidth()) { Text("JSON 백업") }
                    OutlinedButton({ importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Text("JSON 복원") }
                }
            }
        }
        editing?.takeIf { it in board.cells.indices }?.let { index ->
            val task = board.cells[index]
            TaskDialog(task, { editing = null }, { name, repeat ->
                change { current ->
                    if (current.cells.getOrNull(index)?.id != task?.id || index !in current.cells.indices) current
                    else current.put(index, current.cells[index]?.copy(name = name, repeat = repeat) ?: Task(name, repeat))
                }
                editing = null
            }, {
                change { current -> if (current.cells.getOrNull(index)?.id == task?.id && index in current.cells.indices) current.put(index, null) else current }
                editing = null
            })
        }
        when (dialog) {
            "name" -> NameDialog(rename?.name.orEmpty(), { dialog = "" }) { name ->
                val selected = rename
                run { store.savePreset(name, selected?.let { Board.decode(JSONObject(it.board)) } ?: board, selected?.name) }
                dialog = ""
            }
            "copy" -> Confirm("전날 복사", "오늘 빙고판을 어제의 할 일로 바꾸고 완료 체크를 초기화해요.", { dialog = "" }) {
                yesterday?.let { previous -> change { previous.reset() } }
                dialog = ""
            }
            "carry" -> {
                val existing = board.cells.filterNotNull().map { it.id }.toSet()
                val candidates = yesterday?.cells.orEmpty().filterNotNull().filter { !it.done && it.id !in existing }
                CarryDialog(candidates, board.cells.size - board.filled, { dialog = "" }) { tasks ->
                    change { current ->
                        val cells = current.cells.toMutableList()
                        for (task in tasks) {
                            val available = cells.indexOf(null)
                            if (available < 0) break
                            if (cells.none { it?.id == task.id }) cells[available] = task.copy(done = false)
                        }
                        current.copy(cells = cells)
                    }
                    dialog = ""
                }
            }
            "load" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("프리셋 불러오기") },
                text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("선택한 프리셋으로 오늘 빙고판을 바꿔요.")
                    presets.forEach { preset -> TextButton({ rename = preset; dialog = "apply" }) { Text(preset.name) } }
                } }, confirmButton = { TextButton({ dialog = "" }) { Text("닫기") } })
            "apply" -> Confirm("프리셋 불러오기", "오늘 빙고판을 '${rename?.name}'으로 바꿔요.", { dialog = "" }) {
                rename?.let { preset -> change { Board.decode(JSONObject(preset.board)).reset() } }
                dialog = ""
            }
            "deletePreset" -> Confirm("프리셋 삭제", "'${rename?.name}' 프리셋을 삭제할까요?", { dialog = "" }) {
                rename?.let { run { store.deletePreset(it.name) } }
                dialog = ""
            }
            "past" -> Confirm("지난 빙고판 삭제", "오늘 이전의 빙고판을 모두 삭제해요. 전날 복사와 미완료 가져오기는 내일부터 다시 쓸 수 있어요.", { dialog = "" }) {
                run { store.deletePast(); message("지난 빙고판을 삭제했어요.") }
                dialog = ""
            }
            "restore" -> Confirm("JSON 복원", "현재 빙고판과 프리셋, 알림 설정을 백업 내용으로 바꿔요.", { dialog = "" }) {
                val uri = restoreUri
                if (uri != null) run {
                    val data = withContext(Dispatchers.IO) {
                        requireNotNull(contentResolver.openInputStream(uri)).bufferedReader().use { JSONObject(it.readText()) }
                    }
                    val values = data.getJSONArray("reminders")
                    require(values.length() == 2)
                    val configured = (0..1).map { Reminders.decode(values.getJSONObject(it)) }
                    store.restore(data)
                    Reminders.restore(this@MainActivity, configured)
                    updateWidgets(this@MainActivity)
                    reminderVersion++
                    message("복원했어요.")
                }
                dialog = ""
            }
        }
    }
    @Composable private fun ReminderCard(id: Int, config: Reminder, change: (Reminder) -> Unit, onSound: () -> Unit) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Reminders.title(id), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(config.enabled, { change(config.copy(enabled = it)) })
                }
                OutlinedButton({
                    TimePickerDialog(this@MainActivity, { _, hour, minute -> change(config.copy(hour = hour, minute = minute)) },
                        config.hour, config.minute, true).show()
                }) { Text("시간  %02d:%02d".format(config.hour, config.minute)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    "월화수목금토일".forEachIndexed { index, day ->
                        val bit = 1 shl index
                        FilterChip((config.days and bit) != 0, { change(config.copy(days = config.days xor bit)) },
                            label = { Text(day.toString(), fontSize = 11.sp) })
                    }
                }
                if (config.days == 0) Text("반복 요일을 하나 이상 선택해 주세요.")
                OutlinedButton(onSound) { Text("알림음  " + when (config.sound) { "default" -> "기본"; "silent" -> "무음"; else -> "선택한 알림음" }) }
                CheckRow("진동", config.vibrate) { change(config.copy(vibrate = it)) }
                if (id == 1) CheckRow("이미 작성했으면 생략", config.skip) { change(config.copy(skip = it)) }
            }
        }
    }
    companion object {
        const val EDIT = "io.todobingo.EDIT"
        const val TODAY = "io.todobingo.TODAY"
    }
}
