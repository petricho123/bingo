package io.todobingo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class Task(val name: String, val repeat: Boolean = false, val done: Boolean = false, val id: String = UUID.randomUUID().toString())
data class Board(val size: Int = 3, val cells: List<Task?> = List(size * size) { null }) {
    val completed get() = cells.count { it?.done == true }
    val filled get() = cells.count { it != null }
    val lines: List<List<Int>> get() {
        val width = size
        return buildList {
            for (i in 0 until width) {
                add(List(width) { i * width + it })
                add(List(width) { it * width + i })
            }
            add(List(width) { it * (width + 1) })
            add(List(width) { (it + 1) * (width - 1) })
        }.filter { line -> line.all { cells[it]?.done == true } }
    }
    fun put(index: Int, task: Task?) = copy(cells = cells.toMutableList().apply { this[index] = task })
    fun reset() = copy(cells = cells.map { it?.copy(done = false) })
    fun resize(newSize: Int): Board? {
        val result = MutableList<Task?>(newSize * newSize) { null }
        val displaced = mutableListOf<Task>()
        cells.forEachIndexed { index, task ->
            if (task != null) {
                val row = index / size
                val col = index % size
                if (row < newSize && col < newSize) result[row * newSize + col] = task
                else displaced.add(task)
            }
        }
        if (displaced.size > result.count { it == null }) return null
        for (task in displaced) result[result.indexOf(null)] = task
        return Board(newSize, result)
    }
    fun encode(): JSONObject = JSONObject().put("size", size).put("cells", JSONArray().apply {
        cells.forEach { task ->
            put(task?.let { JSONObject().put("id", it.id).put("name", it.name).put("repeat", it.repeat).put("done", it.done) } ?: JSONObject.NULL)
        }
    })
    companion object {
        fun decode(json: JSONObject): Board {
            val size = json.getInt("size")
            val cells = json.getJSONArray("cells")
            require(size in 3..5 && cells.length() == size * size)
            val tasks = List(size * size) { i ->
                if (cells.isNull(i)) null else cells.getJSONObject(i).let {
                    val name = it.getString("name")
                    val id = it.getString("id")
                    require(name.isNotBlank() && name.length <= 80 && id.isNotBlank())
                    Task(name, it.getBoolean("repeat"), it.getBoolean("done"), id)
                }
            }
            require(tasks.filterNotNull().map { it.id }.distinct().size == tasks.count { it != null })
            return Board(size, tasks)
        }
    }
}

@Entity data class Day(@PrimaryKey val date: String, val board: String)
@Entity data class Preset(@PrimaryKey val name: String, val board: String)
@Dao interface BingoDao {
    @Query("SELECT * FROM Day WHERE date = :date") suspend fun day(date: String): Day?
    @Query("SELECT * FROM Day WHERE date < :date ORDER BY date DESC LIMIT 1") suspend fun previous(date: String): Day?
    @Query("SELECT * FROM Day ORDER BY date") suspend fun days(): List<Day>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(day: Day)
    @Query("DELETE FROM Day WHERE date < :date") suspend fun deletePast(date: String)
    @Query("DELETE FROM Day") suspend fun clearDays()
    @Query("SELECT * FROM Preset ORDER BY name") suspend fun presets(): List<Preset>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(preset: Preset)
    @Query("DELETE FROM Preset WHERE name = :name") suspend fun deletePreset(name: String)
    @Query("DELETE FROM Preset") suspend fun clearPresets()
}
@Database(entities = [Day::class, Preset::class], version = 1, exportSchema = false)
abstract class BingoDb : RoomDatabase() { abstract fun dao(): BingoDao }

class Boards private constructor(context: Context) {
    private val db = Room.databaseBuilder(context, BingoDb::class.java, "boards.db").build()
    private val dao = db.dao()
    val changes = MutableStateFlow(0L)
    private fun changed() { synchronized(changes) { changes.value++ } }
    private fun Day.decode() = Board.decode(JSONObject(board))
    private suspend fun ensure(date: LocalDate): Board {
        dao.day(date.toString())?.let { return it.decode() }
        val previous = dao.previous(date.toString())?.decode() ?: Board()
        val next = previous.reset().copy(cells = previous.cells.map { it?.takeIf(Task::repeat)?.copy(done = false) })
        dao.save(Day(date.toString(), next.encode().toString()))
        return next
    }
    suspend fun today(date: LocalDate = LocalDate.now()) = db.withTransaction { ensure(date) }
    suspend fun yesterday() = dao.day(LocalDate.now().minusDays(1).toString())?.decode()
    suspend fun update(date: LocalDate, change: (Board) -> Board) {
        db.withTransaction {
            if (date != LocalDate.now()) return@withTransaction
            val next = change(ensure(date))
            dao.save(Day(date.toString(), next.encode().toString()))
        }
        changed()
    }
    suspend fun deletePast() {
        db.withTransaction {
            val date = LocalDate.now()
            ensure(date)
            dao.deletePast(date.toString())
        }
        changed()
    }
    suspend fun presets() = dao.presets()
    suspend fun savePreset(name: String, board: Board, old: String? = null) {
        db.withTransaction {
            require(old == name || dao.presets().none { it.name == name }) { "이미 같은 이름이 있어요." }
            dao.save(Preset(name, board.reset().encode().toString()))
            if (old != null && old != name) dao.deletePreset(old)
        }
        changed()
    }
    suspend fun deletePreset(name: String) { dao.deletePreset(name); changed() }
    suspend fun backup(): JSONObject = db.withTransaction {
        JSONObject().put("version", 1)
            .put("days", JSONArray(dao.days().map { JSONObject().put("date", it.date).put("board", it.decode().encode()) }))
            .put("presets", JSONArray(dao.presets().map { JSONObject().put("name", it.name).put("board", JSONObject(it.board)) }))
    }
    suspend fun restore(json: JSONObject) {
        require(json.getInt("version") == 1)
        val days = json.getJSONArray("days").objects().map {
            Day(LocalDate.parse(it.getString("date")).toString(), Board.decode(it.getJSONObject("board")).encode().toString())
        }
        val presets = json.getJSONArray("presets").objects().map {
            val name = it.getString("name")
            require(name.isNotBlank() && name.length <= 40)
            Preset(name, Board.decode(it.getJSONObject("board")).reset().encode().toString())
        }
        require(days.map { it.date }.distinct().size == days.size && presets.map { it.name }.distinct().size == presets.size)
        db.withTransaction {
            dao.clearDays()
            dao.clearPresets()
            days.forEach { dao.save(it) }
            presets.forEach { dao.save(it) }
            ensure(LocalDate.now())
        }
        changed()
    }
    fun refresh() = changed()
    companion object {
        @Volatile private var instance: Boards? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Boards(context.applicationContext).also { instance = it }
        }
    }
}
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
