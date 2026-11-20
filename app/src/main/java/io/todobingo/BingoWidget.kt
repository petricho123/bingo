package io.todobingo

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val Mint = Color(0xFF77E5A7)
val Green = Color(0xFF226744)
val Tile = Color(0xFF303431)
val Back = Color(0xFF111411)
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))

class BingoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BingoWidget()
}
suspend fun updateWidgets(context: Context) = BingoWidget().updateAll(context)

class BingoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }
}
@Composable private fun Content(context: Context) {
    val store = Boards.get(context)
    val sequence by store.changes.collectAsState()
    val state by androidx.compose.runtime.produceState<Pair<LocalDate, Board>?>(null, sequence) {
        val date = LocalDate.now()
        value = date to store.today(date)
    }
    val (date, board) = state ?: return
    val size = LocalSize.current
    val font = (minOf(size.width.value, size.height.value) / board.size / 3.8f).coerceIn(8f, 16f).sp
    val highlighted = board.lines.flatten().toSet()
    val edit = Intent(context, MainActivity::class.java).setAction(MainActivity.EDIT)
    Column(GlanceModifier.fillMaxSize().background(Back).padding(6.dp).cornerRadius(16.dp)) {
        Text(dateLabel(date), GlanceModifier.padding(horizontal = 4.dp, vertical = 3.dp),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp), maxLines = 1)
        if (board.filled == 0) {
            Box(GlanceModifier.fillMaxWidth().defaultWeight().padding(4.dp).background(Green)
                .cornerRadius(10.dp).clickable(actionStartActivity(edit)), contentAlignment = Alignment.Center) {
                Text("오늘 빙고판 작성", style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp))
            }
        } else for (row in 0 until board.size) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (col in 0 until board.size) {
                    val index = row * board.size + col
                    val task = board.cells[index]
                    val color = when { index in highlighted -> Mint; task?.done == true -> Green; else -> Tile }
                    val action = if (task == null) actionStartActivity(edit) else actionRunCallback<ToggleTask>(
                        actionParametersOf(ToggleTask.date to date.toString(), ToggleTask.id to task.id))
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp)) {
                        Box(GlanceModifier.fillMaxSize().background(color).cornerRadius(7.dp).clickable(action).padding(2.dp),
                            contentAlignment = Alignment.Center) {
                            Text(task?.let { if (it.done) "✓ ${it.name}" else it.name } ?: "·",
                                style = TextStyle(color = ColorProvider(if (index in highlighted) Back else Color.White),
                                    fontSize = font, textAlign = TextAlign.Center), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
class ToggleTask : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val current = LocalDate.now()
        val store = Boards.get(context)
        store.today(current)
        if (parameters[date] == current.toString()) store.update(current) { board ->
            val index = board.cells.indexOfFirst { it != null && it.id == parameters[id] }
            if (index < 0) board else board.put(index, board.cells[index]?.let { it.copy(done = !it.done) })
        } else store.refresh()
        updateWidgets(context)
    }
    companion object {
        val date = ActionParameters.Key<String>("date")
        val id = ActionParameters.Key<String>("id")
    }
}
