package io.todobingo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Grid(board: Board, edit: Boolean, onTap: (Int) -> Unit, onEdit: (Int) -> Unit, onMove: (Int, Int) -> Unit) {
    val highlighted = board.lines.flatten().toSet()
    var target by remember(board.size, edit) { mutableIntStateOf(-1) }
    val move by rememberUpdatedState(onMove)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 5.dp
        val tileSize = (maxWidth - gap * (board.size - 1)) / board.size
        val stride = with(LocalDensity.current) { (tileSize + gap).toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until board.size) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until board.size) {
                        val index = row * board.size + col
                        val task = board.cells[index]
                        val drag = if (edit && task != null) Modifier.pointerInput(board.size, stride, task.id) {
                            var position = Offset.Zero
                            detectDragGesturesAfterLongPress(onDragStart = { start ->
                                target = index
                                position = Offset(col * stride, row * stride) + start
                            }, onDragCancel = {
                                target = -1
                            }, onDragEnd = {
                                val destination = target
                                target = -1
                                if (destination in board.cells.indices && destination != index) move(index, destination)
                            }) { change, amount ->
                                change.consume()
                                position += amount
                                val x = floor(position.x / stride).toInt().coerceIn(0, board.size - 1)
                                val y = floor(position.y / stride).toInt().coerceIn(0, board.size - 1)
                                target = y * board.size + x
                            }
                        } else Modifier
                        val bright = index in highlighted || index == target
                        val background = when {
                            bright -> Mint
                            task?.done == true -> Green
                            else -> Tile
                        }
                        Surface(Modifier.size(tileSize).then(drag).combinedClickable(
                            onClick = { onTap(index) }, onLongClick = if (edit) null else ({ onEdit(index) })),
                            shape = MaterialTheme.shapes.medium, color = background,
                            contentColor = if (bright) Back else Color.White) {
                            Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                                Text(task?.let { if (it.done) "✓ ${it.name}" else it.name } ?: "+",
                                    fontSize = if (board.size == 5) 11.sp else 13.sp,
                                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskDialog(task: Task?, close: () -> Unit, save: (String, Boolean) -> Unit, remove: () -> Unit) {
    var name by remember(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var repeat by remember(task?.id) { mutableStateOf(task?.repeat ?: false) }
    AlertDialog(onDismissRequest = close, title = { Text(if (task == null) "할 일 추가" else "할 일 편집") },
        text = { Column {
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("할 일 이름") })
            CheckRow("매일 반복", repeat) { repeat = it }
        } }, confirmButton = { TextButton({ save(name.trim(), repeat) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { if (task == null) TextButton(close) { Text("취소") } else TextButton(remove) { Text("삭제") } })
}

@Composable
fun CheckRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change)
        Text(label)
    }
}

@Composable
fun Confirm(title: String, details: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(details) },
        confirmButton = { TextButton(confirm) { Text("확인") } },
        dismissButton = { TextButton(close) { Text("취소") } })
}

@Composable
fun NameDialog(initial: String, close: () -> Unit, save: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text("프리셋 이름") },
        text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("이름") }) },
        confirmButton = { TextButton({ save(name.trim()) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(close) { Text("취소") } })
}

@Composable
fun CarryDialog(tasks: List<Task>, available: Int, close: () -> Unit, save: (List<Task>) -> Unit) {
    val chosen = remember(tasks) { mutableStateListOf<String>() }
    AlertDialog(onDismissRequest = close, title = { Text("미완료 가져오기") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("빈 칸 ${available}개")
            if (tasks.isEmpty()) Text("가져올 할 일이 없어요.")
            tasks.forEach { task ->
                CheckRow(task.name, task.id in chosen) { checked ->
                    if (!checked) chosen.remove(task.id)
                    else if (chosen.size < available) chosen.add(task.id)
                }
            }
        } }, confirmButton = { TextButton({ save(tasks.filter { it.id in chosen }) }, enabled = chosen.isNotEmpty()) { Text("가져오기") } },
        dismissButton = { TextButton(close) { Text("취소") } })
}
