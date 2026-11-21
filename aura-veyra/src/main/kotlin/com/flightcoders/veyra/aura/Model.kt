// Aura Veyra: the rules. Pure Kotlin with no IDE classes, so it is unit-tested with plain JUnit.
// The same rules as Veyra Aura for VS Code.
package com.flightcoders.veyra.aura

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class MoveId { CLEARED_ERRORS, CLEAN_SAVE, COMMIT, GOOD_COMMIT_MESSAGE, BAD_COMMIT_MESSAGE, TESTS_PASSED, TESTS_FAILED,
    BUILD_PASSED, BUILD_FAILED, FORCE_PUSH, DELETED_LINES, LOCKED_IN, NIGHT_OWL, DEBUGGER_LEFT, STREAK }

/** A move: its aura, emoji and feed text ({file}, {n} and {msg} are filled in), and how many count per day. */
data class Move(val aura: Int, val emoji: String, val text: String, val daily: Int, val roast: Boolean = false)

val MOVES: Map<MoveId, Move> = mapOf(
    MoveId.CLEARED_ERRORS to Move(15, "🧹", "Cleared every error in {file}", 20),
    MoveId.CLEAN_SAVE to Move(1, "💾", "Clean save, zero errors", 25),
    MoveId.COMMIT to Move(20, "📦", "Shipped a commit", 15),
    MoveId.GOOD_COMMIT_MESSAGE to Move(5, "✍️", "That commit message ate", 15),
    MoveId.BAD_COMMIT_MESSAGE to Move(-5, "💀", "Commit message “{msg}” is giving nothing", 10, roast = true),
    MoveId.TESTS_PASSED to Move(20, "✅", "Tests passed. W.", 10),
    MoveId.TESTS_FAILED to Move(-5, "🫠", "Tests said nah", 10, roast = true),
    MoveId.BUILD_PASSED to Move(10, "🏗️", "Build passed", 8),
    MoveId.BUILD_FAILED to Move(-3, "🧱", "Build fumbled", 8, roast = true),
    MoveId.FORCE_PUSH to Move(-20, "☢️", "Force-pushed. Bold move.", 5, roast = true),
    MoveId.DELETED_LINES to Move(15, "✂️", "Deleted {n} lines from {file}. Less code, more aura.", 6),
    MoveId.LOCKED_IN to Move(10, "🔒", "25 minutes locked in", 8),
    MoveId.NIGHT_OWL to Move(5, "🦉", "Coding after midnight. Built different.", 1),
    MoveId.DEBUGGER_LEFT to Move(-5, "🐛", "Left a debugger statement in {file}", 1, roast = true),
    MoveId.STREAK to Move(0, "🔥", "{n}-day streak", 1),
)

data class Rank(val name: String, val emoji: String, val min: Int, val vibe: String)

val RANKS = listOf(
    Rank("NPC", "🧍", 0, "Just spawned in."),
    Rank("Side Character", "🎭", 100, "Getting some screen time."),
    Rank("Main Character", "✨", 300, "The plot revolves around you now."),
    Rank("Locked In", "🔒", 700, "Do not disturb. Seriously."),
    Rank("Sigma", "🗿", 1500, "Ships in silence."),
    Rank("Aura Farmer", "🌾", 3000, "Harvesting aura daily."),
    Rank("Goated", "🐐", 6000, "The GOAT conversation includes you."),
    Rank("Final Boss", "👑", 10000, "Other devs fear this energy."),
)
val NEGATIVE = Rank("Negative Aura", "☠️", Int.MIN_VALUE, "It can only go up from here.")

data class RankInfo(val rank: Rank, val next: Rank?, val progress: Double)

fun rankFor(total: Int): RankInfo {
    if (total < 0) return RankInfo(NEGATIVE, RANKS[0], 0.0)
    var i = 0
    while (i + 1 < RANKS.size && total >= RANKS[i + 1].min) i++
    val rank = RANKS[i]; val next = RANKS.getOrNull(i + 1)
    return RankInfo(rank, next, if (next == null) 1.0 else (total - rank.min).toDouble() / (next.min - rank.min))
}

data class AuraEvent(val t: Long, val id: MoveId, val aura: Int, val text: String, val emoji: String)

data class AuraState(
    val total: Int = 0,
    /** Aura gained (or lost) per local day, "2026-11-21" → n. */
    val days: Map<String, Int> = emptyMap(),
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val lastDay: String = "",
    /** Per-day counters for the daily limits: "2026-11-21|COMMIT" → n. */
    val counts: Map<String, Int> = emptyMap(),
    /** Newest last. */
    val events: List<AuraEvent> = emptyList(),
    val activeDay: String = "",
    val activeMinutes: Int = 0,
)

const val MAX_EVENTS = 200
private const val DAY_MS = 86_400_000L

fun dayKey(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()
private fun yesterday(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().minusDays(1).toString()

private fun fill(text: String, vars: Map<String, Any>) = Regex("""\{(\w+)}""").replace(text) { vars[it.groupValues[1]]?.toString() ?: "" }

data class Awarded(val state: AuraState, val events: List<AuraEvent>, val rankUp: Rank? = null)

/**
 * Gives (or takes) the aura for a move. Nothing happens when the daily limit for the move (per [key], e.g. per file)
 * is used up, or for a roast when roast mode is off. The first good move of a day keeps the streak going, with a
 * bonus of 5 aura per streak day (up to 50).
 */
fun award(prev: AuraState, id: MoveId, t: Long, vars: Map<String, Any> = emptyMap(), roast: Boolean = true, key: String? = null,
          zone: ZoneId = ZoneId.systemDefault()): Awarded {
    val move = MOVES.getValue(id)
    if (move.roast && !roast) return Awarded(prev, emptyList())
    val day = dayKey(t, zone)
    val countKey = "$day|$id" + (key?.let { "|$it" } ?: "")
    if ((prev.counts[countKey] ?: 0) >= move.daily) return Awarded(prev, emptyList())

    var total = prev.total
    val days = prev.days.toMutableMap()
    val events = prev.events.toMutableList()
    val added = mutableListOf<AuraEvent>()
    var streak = prev.streak; var best = prev.bestStreak; var lastDay = prev.lastDay
    fun add(mid: MoveId, aura: Int, text: String) {
        total += aura
        days[day] = (days[day] ?: 0) + aura
        val e = AuraEvent(t, mid, aura, text, MOVES.getValue(mid).emoji)
        events += e; added += e
    }
    if (move.aura > 0 && lastDay != day) {
        streak = if (lastDay == yesterday(t, zone)) streak + 1 else 1
        best = maxOf(best, streak)
        lastDay = day
        if (streak >= 2) add(MoveId.STREAK, min(50, streak * 5), fill(MOVES.getValue(MoveId.STREAK).text, mapOf("n" to streak)))
    }
    add(id, move.aura, fill(move.text, vars))
    val counts = prev.counts.filterKeys { it.substring(0, 10) >= day }.toMutableMap()
    counts[countKey] = (counts[countKey] ?: 0) + 1
    val cutoff = dayKey(t - 62 * DAY_MS, zone)
    val keptDays = days.filterKeys { it >= cutoff }
    val keptEvents = if (events.size > MAX_EVENTS) events.takeLast(MAX_EVENTS) else events
    val state = prev.copy(total = total, days = keptDays, streak = streak, bestStreak = best, lastDay = lastDay, counts = counts, events = keptEvents)
    val before = rankFor(prev.total).rank; val after = rankFor(total).rank
    return Awarded(state, added, if (after.min > before.min) after else null)
}

/** Counts a minute with edits. Every 25th of the day earns "locked in". */
fun activeMinute(prev: AuraState, t: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<AuraState, Boolean> {
    val day = dayKey(t, zone)
    val minutes = (if (prev.activeDay == day) prev.activeMinutes else 0) + 1
    return prev.copy(activeDay = day, activeMinutes = minutes) to (minutes % 25 == 0)
}

/** Midnight to 4 am. */
fun isNight(t: Long, zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(t).atZone(zone).hour < 4

private val BAD_MESSAGES = Regex("""^(wip|fix|fixes|fixed|update|updates|changes|change|stuff|asdf+|test|tests|commit|minor|idk|temp|tmp|misc|done|final|ok|oops|lol|\.+|-+|x+|a+|aaa+|f+)$""", RegexOption.IGNORE_CASE)
private val CONVENTIONAL = Regex("""^(feat|fix|docs|refactor|perf|test|tests|chore|build|ci|style|revert)(\([^)]+\))?!?: \S.{5,}""", RegexOption.IGNORE_CASE)

enum class Verdict { GOOD, BAD, OK }

fun judgeCommitMessage(message: String): Verdict {
    val first = message.lineSequence().firstOrNull()?.trim().orEmpty()
    if (first.length < 4 || BAD_MESSAGES.matches(first)) return Verdict.BAD
    if (CONVENTIONAL.containsMatchIn(first)) return Verdict.GOOD
    if (first.length >= 20 && first.split(Regex("""\s+""")).size >= 4) return Verdict.GOOD
    return Verdict.OK
}

/** A debugger statement on its own line (JavaScript / TypeScript). */
fun hasDebugger(text: String) = Regex("""^\s*debugger\s*;?\s*$""", RegexOption.MULTILINE).containsMatchIn(text)

data class WeekDay(val key: String, val day: String, val aura: Int)

fun lastWeek(state: AuraState, t: Long, zone: ZoneId = ZoneId.systemDefault()): List<WeekDay> {
    val today = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    return (6 downTo 0).map { i -> val d: LocalDate = today.minusDays(i.toLong()); WeekDay(d.toString(), d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), state.days[d.toString()] ?: 0) }
}

data class TopMove(val emoji: String, val label: String, val aura: Int, val times: Int)

private val LABELS = mapOf(
    MoveId.CLEARED_ERRORS to "Errors cleared", MoveId.CLEAN_SAVE to "Clean saves", MoveId.COMMIT to "Commits",
    MoveId.GOOD_COMMIT_MESSAGE to "Fire commit messages", MoveId.TESTS_PASSED to "Test runs passed", MoveId.BUILD_PASSED to "Builds passed",
    MoveId.DELETED_LINES to "Big deletes", MoveId.LOCKED_IN to "Locked-in sessions", MoveId.NIGHT_OWL to "Late nights",
)

/** The moves that earned the most in the last 7 days. */
fun topMoves(state: AuraState, t: Long, count: Int = 3): List<TopMove> {
    val since = t - 7 * DAY_MS
    return state.events.filter { it.t >= since && it.aura > 0 && it.id != MoveId.STREAK }.groupBy { it.id }
        .map { (id, es) -> TopMove(MOVES.getValue(id).emoji, LABELS[id] ?: id.name, es.sumOf { it.aura }, es.size) }
        .sortedByDescending { it.aura }.take(count)
}

fun fmt(n: Int): String = (if (n < 0) "−" else "") + String.format(Locale.ENGLISH, "%,d", abs(n))
fun signed(n: Int): String = (if (n > 0) "+" else if (n < 0) "−" else "±") + String.format(Locale.ENGLISH, "%,d", abs(n))

/** The streak still alive today or yesterday, else 0. */
fun liveStreak(state: AuraState, t: Long, zone: ZoneId = ZoneId.systemDefault()) =
    if (state.lastDay == dayKey(t, zone) || state.lastDay == yesterday(t, zone)) state.streak else 0

/** The text to post with the aura card. */
fun caption(state: AuraState, t: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val rank = rankFor(state.total).rank
    val week = lastWeek(state, t, zone).sumOf { it.aura }
    val streak = liveStreak(state, t, zone).let { if (it >= 2) " · 🔥 $it-day streak" else "" }
    return "My code aura: ${fmt(state.total)} (${rank.name} ${rank.emoji})$streak · ${signed(week)} this week. Built different. #VeyraAura #coding\nveyraeditor.com/aura"
}

fun ago(t: Long, now: Long): String {
    val s = ((now - t) / 1000.0).roundToInt().coerceAtLeast(0)
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
}
