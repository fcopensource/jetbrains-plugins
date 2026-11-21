package com.flightcoders.veyra.aura

import com.flightcoders.veyra.aura.git.AuraGitListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private val day1 = at(2026, 11, 20); private val day2 = at(2026, 11, 21); private val day3 = at(2026, 11, 22); private val day5 = at(2026, 11, 24)
    private fun give(s: AuraState, id: MoveId, t: Long, vars: Map<String, Any> = emptyMap(), roast: Boolean = true, key: String? = null) =
        award(s, id, t, vars, roast, key, zone)

    @Test fun `moves give and take aura with the text filled in`() {
        var r = give(AuraState(), MoveId.CLEARED_ERRORS, day1, mapOf("file" to "Cart.kt"))
        assertEquals(15, r.state.total)
        assertEquals("Cleared every error in Cart.kt", r.events[0].text)
        assertEquals("🧹", r.events[0].emoji)
        r = give(r.state, MoveId.BAD_COMMIT_MESSAGE, day1, mapOf("msg" to "wip"))
        assertEquals(10, r.state.total)
        assertEquals("Commit message “wip” is giving nothing", r.events[0].text)
        assertEquals(10, r.state.days[dayKey(day1, zone)])
    }

    @Test fun `roast mode off skips penalties`() {
        assertTrue(give(AuraState(), MoveId.TESTS_FAILED, day1, roast = false).events.isEmpty())
        assertEquals(-5, give(AuraState(), MoveId.TESTS_FAILED, day1).state.total)
    }

    @Test fun `daily limits per move and per key, reset the next day`() {
        var s = AuraState()
        repeat(40) { s = give(s, MoveId.CLEAN_SAVE, day1).state }
        assertEquals(25, s.total)
        s = give(s, MoveId.DEBUGGER_LEFT, day1, key = "/a.js").state
        s = give(s, MoveId.DEBUGGER_LEFT, day1, key = "/a.js").state
        s = give(s, MoveId.DEBUGGER_LEFT, day1, key = "/b.js").state
        assertEquals(15, s.total)
        val next = give(s, MoveId.CLEAN_SAVE, day2)
        assertEquals(MoveId.CLEAN_SAVE, next.events.last().id)
        assertTrue(next.state.counts.keys.all { it.startsWith(dayKey(day2, zone)) })
    }

    @Test fun `streaks grow with a bonus and reset after a missed day`() {
        var s = give(AuraState(), MoveId.COMMIT, day1).state
        assertEquals(1, s.streak)
        val r = give(s, MoveId.COMMIT, day2)
        assertEquals(2, r.state.streak)
        assertEquals(listOf(MoveId.STREAK to 10, MoveId.COMMIT to 20), r.events.map { it.id to it.aura })
        assertEquals("2-day streak", r.events[0].text)
        assertEquals(1, give(r.state, MoveId.COMMIT, day2).events.size)
        s = give(r.state, MoveId.CLEAN_SAVE, day3).state
        assertEquals(3, s.streak)
        assertEquals(3, liveStreak(s, day3, zone))
        assertEquals(0, liveStreak(s, day5, zone))
        s = give(s, MoveId.COMMIT, day5).state
        assertEquals(1, s.streak)
        assertEquals(3, s.bestStreak)
        assertEquals(0, give(AuraState(), MoveId.TESTS_FAILED, day1).state.streak)
        assertEquals(50, give(AuraState(streak = 20, lastDay = dayKey(day1, zone)), MoveId.COMMIT, day2).events[0].aura)
    }

    @Test fun `ranks, progress and rank ups`() {
        assertEquals("NPC", rankFor(0).rank.name)
        assertEquals("Side Character", rankFor(100).rank.name)
        assertEquals(0.5, rankFor(200).progress, 1e-9)
        assertEquals("Main Character", rankFor(300).rank.name)
        assertEquals("Negative Aura", rankFor(-5).rank.name)
        assertEquals("Final Boss", rankFor(50_000).rank.name)
        assertNull(rankFor(50_000).next)
        assertEquals(listOf(0, 100, 300, 700, 1500, 3000, 6000, 10000), RANKS.map { it.min })
        assertEquals("Side Character", give(AuraState(total = 95), MoveId.COMMIT, day1).rankUp?.name)
        assertNull(give(AuraState(total = 10), MoveId.COMMIT, day1).rankUp)
    }

    @Test fun `events are capped`() {
        var s = AuraState()
        for (d in 1..30) repeat(10) { s = give(s, MoveId.TESTS_PASSED, at(2026, 10, d)).state }
        assertEquals(MAX_EVENTS, s.events.size)
    }

    @Test fun `locked in every 25 active minutes, night owl after midnight`() {
        var s = AuraState(); var locked = 0
        for (i in 0 until 60) { val (n, l) = activeMinute(s, day1 + i * 60_000L, zone); s = n; if (l) locked++ }
        assertEquals(2, locked)
        assertEquals(1, activeMinute(s, day2, zone).first.activeMinutes)
        assertTrue(isNight(at(2026, 11, 21, 1, 30), zone))
        assertFalse(isNight(at(2026, 11, 21, 9), zone))
    }

    @Test fun `commit messages are judged`() {
        for (m in listOf("wip", "fix", "asdf", ".", "update", "stuff", "x", "ok", "final")) assertEquals(m, Verdict.BAD, judgeCommitMessage(m))
        for (m in listOf("feat: add dark mode toggle", "fix(api): retry on 503 errors", "Handle empty carts in the checkout total")) assertEquals(m, Verdict.GOOD, judgeCommitMessage(m))
        assertEquals(Verdict.OK, judgeCommitMessage("Bump version"))
        assertEquals(Verdict.OK, judgeCommitMessage("feat: x"))
    }

    @Test fun `reflog entries that are new commits`() {
        assertTrue(AuraGitListener.isNewCommit("commit: add tax to the cart"))
        assertTrue(AuraGitListener.isNewCommit("commit (initial): first"))
        for (a in listOf("commit (amend): add tax", "checkout: moving from main to dev", "pull: Fast-forward", "reset: moving to HEAD~1", "commit (merge): Merge branch 'x'")) assertFalse(a, AuraGitListener.isNewCommit(a))
    }

    @Test fun `debugger statements`() {
        assertTrue(hasDebugger("function a() {\n  debugger;\n}"))
        assertFalse(hasDebugger("const debuggerName = 1"))
    }

    @Test fun `the week, best moves, caption and numbers`() {
        var s = give(AuraState(), MoveId.COMMIT, day1).state
        s = give(s, MoveId.COMMIT, day2).state
        s = give(s, MoveId.CLEARED_ERRORS, day2, mapOf("file" to "a")).state
        s = give(s, MoveId.TESTS_FAILED, day2).state
        val week = lastWeek(s, day2, zone)
        assertEquals(7, week.size)
        assertEquals(10 + 20 + 15 - 5, week[6].aura)
        assertEquals(listOf(Triple("Commits", 40, 2), Triple("Errors cleared", 15, 1)), topMoves(s, day2).map { Triple(it.label, it.aura, it.times) })
        assertEquals("My code aura: 60 (NPC 🧍) · 🔥 2-day streak · +60 this week. Built different. #VeyraAura #coding\nveyraeditor.com/aura", caption(s, day2, zone))
        assertEquals("12,345", fmt(12345)); assertEquals("−20", fmt(-20))
        assertEquals("+5", signed(5)); assertEquals("−5", signed(-5)); assertEquals("±0", signed(0))
        assertEquals("\"a\\\"b\\n\\u003c/script>\"", js("a\"b\n</script>"))
    }
}
