// Unit tests for the Hotspots engine and the treemap: plain JUnit, no IDE needed.
package com.flightcoders.veyra.hotspots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ModelTest {
    private val now = 1_795_000_000_000L
    private fun t(daysAgo: Int) = (now - daysAgo * DAY) / 1000

    @Test fun `parses git log with numstat, binary files and quoted paths`() {
        val r = "\u001e"; val u = "\u001f"
        val log = "${r}aaa${u}${t(1)}${u}Ada Lovelace${u}ADA@Example.com\n\n10\t2\tsrc/app.kt\n-\t-\tlogo.png\n" +
            "${r}bbb${u}${t(2)}${u}Bob${u}bob@example.com\n\n1\t0\t\"src/\\303\\251t\\303\\251.kt\"\n" +
            "${r}ccc${u}${t(3)}${u}Bob${u}bob@example.com\n"
        val commits = parseGitLog(log)
        assertEquals(3, commits.size)
        assertEquals(listOf(FileChange("src/app.kt", 10, 2), FileChange("logo.png", 1, 0)), commits[0].files)
        assertEquals("ada@example.com", commits[0].email)
        assertEquals(t(1) * 1000, commits[0].time)
        assertEquals("src/été.kt", commits[1].files[0].path)
        assertTrue(commits[2].files.isEmpty())
    }

    @Test fun `measures lines of code and indentation complexity, skipping blanks and comments`() {
        assertEquals(Shape(6, 5, 2), measure("fun a() {\n  if (x) {\n    return 1\n  }\n\n  // a comment\n  return 2\n}\n"))
        assertEquals(Shape(4, 4, 2), measure("def a():\n\tif x:\n\t\treturn 1\n\t# note\n\treturn 2\n"))
        assertEquals(Shape(2, 1, 1), measure("a {\r\n    b\r\n"))
        assertEquals(Shape(0, 0, 0), measure(""))
    }

    @Test fun `hotspot score grows with both change frequency and complexity`() {
        assertEquals(0.0, hotspotScore(0, 100, 10, 100), 0.0)
        assertEquals(1.0, hotspotScore(10, 100, 10, 100), 0.0)
        assertTrue(hotspotScore(10, 50, 10, 100) > hotspotScore(2, 50, 10, 100))
        assertTrue(hotspotScore(5, 100, 10, 100) > hotspotScore(5, 10, 10, 100))
        assertEquals(Level.CRITICAL, level(0.7)); assertEquals(Level.HIGH, level(0.45)); assertEquals(Level.MEDIUM, level(0.25)); assertEquals(Level.LOW, level(0.1)); assertEquals(Level.NONE, level(0.0))
    }

    // billing.kt is complex and changes all the time (a hotspot), with invoice.kt usually alongside.
    private fun history(): List<Commit> {
        val out = ArrayList<Commit>()
        fun c(days: Int, who: String, vararg files: String) { out += Commit("h${out.size}", now - days * DAY, who, "${who.lowercase()}@x.io", files.map { FileChange(it, 5, 1) }) }
        for (i in 0 until 12) c(10 + i * 5, if (i % 3 != 0) "Ada" else "Bob", "src/billing.kt", *(if (i % 4 != 0) arrayOf("src/invoice.kt") else emptyArray()))
        for (i in 0 until 3) c(20 + i, "Ada", "src/utils.kt")
        for (i in 0 until 5) c(400 + i, "Carol", "src/legacy.kt")
        c(30, "Ada", "README.md")
        c(5, "Ada", *(Array(60) { "gen/$it.kt" } + "src/billing.kt"))
        c(3, "Ada", "src/deleted.kt")
        return out
    }
    private val shapes = mapOf(
        "src/billing.kt" to Shape(400, 900, 6), "src/invoice.kt" to Shape(120, 150, 3), "src/utils.kt" to Shape(30, 20, 2),
        "src/legacy.kt" to Shape(300, 500, 5), "src/quiet.kt" to Shape(80, 60, 2), "README.md" to Shape(10, 0, 0),
    )

    @Test fun `ranks the complex, often-changed file first and ignores deleted files`() {
        val a = analyze(history(), shapes, Options(now, null))
        assertEquals("src/billing.kt", a.files[0].path)
        assertEquals(1, a.files[0].rank)
        assertEquals(13, a.files[0].commits)
        assertFalse("src/deleted.kt" in a.byPath)
        assertEquals(0, a.byPath.getValue("src/quiet.kt").rank)
        assertEquals(3, a.authors)
    }

    @Test fun `the period limits history`() {
        val a = analyze(history(), shapes, Options(now, now - 365 * DAY))
        assertEquals(0, a.byPath.getValue("src/legacy.kt").commits)
        assertEquals(13, a.byPath.getValue("src/billing.kt").commits)
    }

    @Test fun `authors and knowledge risk`() {
        val a = analyze(history(), shapes, Options(now, null))
        assertEquals(listOf("Ada", "Bob"), a.byPath.getValue("src/billing.kt").authors.map { it.name })
        assertEquals(Knowledge.SHARED, a.byPath.getValue("src/billing.kt").knowledge)
        assertEquals(Knowledge.SINGLE, a.byPath.getValue("src/utils.kt").knowledge)
        assertEquals(Knowledge.LOST, a.byPath.getValue("src/legacy.kt").knowledge)
        // Within a one-year period, lastSeen from older history still tells us Carol left.
        val year = analyze(history().filter { it.time >= now - 365 * DAY }, shapes, Options(now, now - 365 * DAY), mapOf("carol" to now - 400 * DAY))
        assertEquals(Knowledge.SHARED, year.byPath.getValue("src/legacy.kt").knowledge) // not changed in the period
    }

    @Test fun `one person with several names or emails counts once`() {
        val commits = listOf(
            Commit("a", now - DAY, "Ada", "ada@work.io", listOf(FileChange("src/x.kt", 10, 0))),
            Commit("b", now - 2 * DAY, "ada", "ada@home.io", listOf(FileChange("src/x.kt", 10, 0))),
            Commit("c", now - 3 * DAY, "ADA ", "ada@work.io", listOf(FileChange("src/x.kt", 10, 0))),
        )
        val a = analyze(commits, mapOf("src/x.kt" to Shape(10, 5, 1)), Options(now, null))
        assertEquals(1, a.authors)
        val f = a.byPath.getValue("src/x.kt")
        assertEquals(1, f.authors.size)
        assertEquals(3, f.authors[0].commits)
        assertEquals(Knowledge.SINGLE, f.knowledge)
    }

    @Test fun `change coupling - files that change together, ignoring huge commits`() {
        val a = analyze(history(), shapes, Options(now, null))
        assertEquals(1, a.coupling.size)
        val c = a.coupling[0]
        assertEquals(Triple("src/billing.kt", "src/invoice.kt", 9), Triple(c.a, c.b, c.shared))
        assertTrue(c.degree > 0.8)
        assertEquals("src/billing.kt", a.coupledWith("src/invoice.kt").single().b)
        assertTrue(explain(a.byPath.getValue("src/billing.kt"), a).matches(Regex("""Changed in 13 commits · 400 lines · nested up to 6 levels deep\. Changed by 2 people, mostly Ada .*Usually changes with invoice\.kt.*""")))
    }

    @Test fun `colour modes and the heat scale`() {
        val a = analyze(history(), shapes, Options(now, null))
        assertEquals(-1.0, modeValue(a.byPath.getValue("src/quiet.kt"), ColorMode.HOTSPOT, a), 0.0)
        assertEquals(1.0, modeValue(a.byPath.getValue("src/billing.kt"), ColorMode.CHURN, a), 1e-9)
        assertEquals(1.0, modeValue(a.byPath.getValue("src/legacy.kt"), ColorMode.KNOWLEDGE, a), 0.0)
        assertTrue(modeValue(a.byPath.getValue("src/legacy.kt"), ColorMode.AGE, a) > modeValue(a.byPath.getValue("src/billing.kt"), ColorMode.AGE, a))
        assertEquals(0x3a3d46, heatRgb(-1.0)); assertEquals(0x26344a, heatRgb(0.0)); assertEquals(0xff3d62, heatRgb(1.0))
    }

    @Test fun `treemap fills the space, without overlaps, with areas matching sizes`() {
        val files = (0 until 40).map { "${listOf("api", "ui", "lib")[it % 3]}/f$it.kt" to 5 + it * 7 }
        val leaves = layoutTreemap(buildTree(files), 1000.0, 600.0, 0.0, 0.0).filter { !it.dir }
        assertEquals(40, leaves.size)
        assertEquals(600_000.0, leaves.sumOf { it.w * it.h }, 1.0)
        for (r in leaves) assertTrue(r.x >= -1e-6 && r.y >= -1e-6 && r.x + r.w <= 1000 + 1e-6 && r.y + r.h <= 600 + 1e-6)
        for (i in leaves.indices) for (j in i + 1 until leaves.size) {
            val p = leaves[i]; val q = leaves[j]
            val overlap = min(p.x + p.w, q.x + q.w) - max(p.x, q.x) > 1e-6 && min(p.y + p.h, q.y + q.h) - max(p.y, q.y) > 1e-6
            assertFalse("${p.path} overlaps ${q.path}", overlap)
        }
        val big = leaves.first { it.path == "lib/f38.kt" }; val small = leaves.first { it.path == "api/f0.kt" }
        assertTrue(big.w * big.h > small.w * small.h * 20)
        assertTrue(leaves.maxOf { max(it.w / it.h, it.h / it.w) } < 8)
    }

    @Test fun `treemap merges single-child folders and leaves room for headers`() {
        val tree = buildTree(listOf("src/main/a.kt" to 10, "src/main/b.kt" to 30, "README.md" to 0))
        assertEquals(41L, tree.size)
        assertEquals(setOf("README.md", "src/main"), tree.children!!.map { it.name }.toSet())
        val rects = layoutTreemap(buildTree(listOf("src/a.kt" to 50, "docs/b.md" to 50)), 400.0, 300.0, 16.0, 2.0)
        val src = rects.first { it.dir && it.name == "src" }; val a = rects.first { it.path == "src/a.kt" }
        assertTrue(a.y >= src.y + 16 - 1e-6)
        assertTrue(abs(a.x - (src.x + 2)) < 1e-6)
    }

    @Test fun `words, globs and the report`() {
        assertEquals("today", ago(now - DAY / 2, now)); assertEquals("3 days ago", ago(now - 3 * DAY, now))
        assertEquals("2 months ago", ago(now - 70 * DAY, now)); assertEquals("2 years ago", ago(now - 800 * DAY, now)); assertEquals("not in this period", ago(null, now))
        assertEquals("the last year", periodLabel(12)); assertEquals("all history", periodLabel(0)); assertEquals("the last 6 months", periodLabel(6))
        val g = listOf("**/node_modules/**", "**/*.min.*", "**/package-lock.json", "**/{dist,build}/**", "docs/*.md").map(::globToRegex)
        for (p in listOf("node_modules/a/b.js", "web/node_modules/x.js", "a/jquery.min.js", "package-lock.json", "apps/web/package-lock.json", "dist/app.js", "pkg/build/x.js", "docs/intro.md")) assertTrue(p, isExcluded(p, g))
        for (p in listOf("src/app.kt", "src/minify.kt", "docs/guide/intro.md", "distance.kt")) assertFalse(p, isExcluded(p, g))
        val md = markdownReport(analyze(history(), shapes, Options(now, null)), 0, "shop")
        assertTrue(md.startsWith("## Code hotspots: shop"))
        assertTrue(md.contains("| 1 | `src/billing.kt` | 13 | 400 | 6 | Ada"))
        assertTrue(md.contains("`src/billing.kt` ↔ `src/invoice.kt`: 9 commits together"))
        assertTrue(md.contains("`src/legacy.kt`: written by Carol, who hasn't committed in over 6 months"))
    }
}
