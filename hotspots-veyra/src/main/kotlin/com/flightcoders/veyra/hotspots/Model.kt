// Hotspots Veyra: the engine. Plain Kotlin with no IntelliJ dependency, so it is unit-tested on its own.
//
// A hotspot is code that is both complicated and changed often: that is where bugs, slow reviews and surprises
// concentrate. We read the project's own git history (how often each file changed, who changed it, what changed
// with it) and each file's shape today (lines of code, how deeply it is indented), and rank every file.
package com.flightcoders.veyra.hotspots

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DAY = 86_400_000L

// ------------------------------------------------------------------------------------------------ git history

data class FileChange(val path: String, val added: Int, val deleted: Int)
data class Commit(val hash: String, val time: Long, val author: String, val email: String, val files: List<FileChange>)

/** The `git log` format [parseGitLog] reads: a record separator, then hash, unix time, author and email (as mapped by .mailmap). */
const val GIT_LOG_FORMAT = "%x1e%H%x1f%at%x1f%aN%x1f%aE"

/** One person, however they committed: "Ada", "ada" and "ADA" with different emails are the same author. */
fun identity(name: String, email: String): String = name.trim().lowercase().ifEmpty { email.trim().lowercase() }

private val NUMSTAT = Regex("""^(\d+|-)\t(\d+|-)\t(.+)$""")

/** Parses `git log --numstat --format=<GIT_LOG_FORMAT>`. Binary files ("-\t-") count as one changed line. */
fun parseGitLog(text: String): List<Commit> {
    val commits = ArrayList<Commit>()
    for (record in text.split('\u001e')) {
        if (record.isBlank()) continue
        val nl = record.indexOf('\n')
        val head = (if (nl < 0) record else record.substring(0, nl)).split('\u001f')
        if (head.size < 4) continue
        val files = ArrayList<FileChange>()
        if (nl >= 0) for (line in record.substring(nl + 1).split('\n')) {
            val m = NUMSTAT.matchEntire(line.trimEnd()) ?: continue
            val (a, d, p) = m.destructured
            files += FileChange(unquote(p), if (a == "-") 1 else a.toInt(), if (d == "-") 0 else d.toInt())
        }
        commits += Commit(head[0].trim(), (head[1].trim().toLongOrNull() ?: 0L) * 1000, head[2], head[3].trim().lowercase(), files)
    }
    return commits
}

/** git quotes unusual paths: "src/\303\251t\303\251.kt" → src/été.kt. */
internal fun unquote(p: String): String {
    if (!(p.length >= 2 && p.startsWith('"') && p.endsWith('"'))) return p
    val body = p.substring(1, p.length - 1)
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length) {
            val n = body[i + 1]
            if (n in '0'..'7') { bytes.write(body.substring(i + 1, min(i + 4, body.length)).toInt(8)); i += 4; continue }
            bytes.write(when (n) { 'n' -> 10; 't' -> 9; '"' -> 34; '\\' -> 92; else -> n.code }); i += 2; continue
        }
        bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++
    }
    return bytes.toString(Charsets.UTF_8)
}

// ------------------------------------------------------------------------------------------------ the shape of a file

data class Shape(val loc: Int, val complexity: Int, val maxDepth: Int)

private val COMMENT_LINE = Regex("""^\s*(//|#(?!include|define|if|pragma|region|endregion)|\*|/\*|<!--|--(?!-)|;|%|')""")

/**
 * Lines of code and "indentation complexity": the sum of every code line's indentation depth. Deeply nested code
 * (ifs inside loops inside callbacks) is harder to change safely, and indentation measures it the same way in any
 * language. The indentation unit is detected per file (2 spaces, 4 spaces or tabs).
 */
fun measure(text: String): Shape {
    val widths = ArrayList<Int>()
    var unit = 0
    for (line in text.split('\n')) {
        val l = line.trimEnd('\r')
        if (l.isBlank() || COMMENT_LINE.containsMatchIn(l)) { widths += -1; continue }
        var w = 0
        for (ch in l) { if (ch == ' ') w++ else if (ch == '\t') w += 4 else break }
        widths += w
        if (w > 0 && (unit == 0 || w < unit)) unit = w
    }
    unit = if (unit in 2..8) unit else if (unit == 1) 1 else 4
    var loc = 0; var complexity = 0; var maxDepth = 0
    for (w in widths) {
        if (w < 0) continue
        loc++
        val depth = (w.toDouble() / unit).roundToInt()
        complexity += depth
        maxDepth = max(maxDepth, depth)
    }
    return Shape(loc, complexity, maxDepth)
}

// ------------------------------------------------------------------------------------------------ statistics

data class Author(val name: String, val email: String, var changes: Int = 0, var commits: Int = 0, var last: Long = 0)

enum class Knowledge { SHARED, SINGLE, LOST }

data class FileStat(
    val path: String,
    val commits: Int,
    val churn: Int,
    val loc: Int,
    val complexity: Int,
    val maxDepth: Int,
    val lastChange: Long?,
    val authors: List<Author>,
    val mainShare: Double,
    val score: Double,
    val knowledge: Knowledge,
    var rank: Int = 0,
) { val name: String get() = path.substringAfterLast('/') }

data class Coupling(val a: String, val b: String, val shared: Int, val degree: Double)

class Analysis(
    val files: List<FileStat>,
    val coupling: List<Coupling>,
    val commits: Int,
    val authors: Int,
    val since: Long?,
    val until: Long,
    val maxCommits: Int,
    val maxComplexity: Int,
) {
    val byPath: Map<String, FileStat> = files.associateBy { it.path }
    val ranked: List<FileStat> get() = files.filter { it.rank > 0 }
    fun coupledWith(path: String): List<Coupling> =
        coupling.filter { it.a == path || it.b == path }.map { if (it.a == path) it else it.copy(a = it.b, b = it.a) }
}

data class Options(
    val now: Long,
    val since: Long?,
    val maxFilesPerCommit: Int = 40,
    val minShared: Int = 4,
    val minDegree: Double = 0.4,
    val inactiveAfter: Long = 180 * DAY,
)

/**
 * Combines history and shapes into ranked files, coupling and knowledge. `shapes` has every current file;
 * `lastSeen` ([identity] → latest commit time) may cover more history than the period, to tell who has left.
 */
fun analyze(commits: List<Commit>, shapes: Map<String, Shape>, opt: Options, lastSeen: Map<String, Long> = emptyMap()): Analysis {
    val inPeriod = commits.filter { opt.since == null || it.time >= opt.since }
    val seen = HashMap(lastSeen)
    for (c in commits) { val who = identity(c.author, c.email); seen[who] = max(seen[who] ?: 0L, c.time) }

    class Acc { var commits = 0; var churn = 0; var last = 0L; val authors = LinkedHashMap<String, Author>() }
    val acc = HashMap<String, Acc>()
    val pairs = HashMap<Pair<String, String>, Int>()
    val people = HashSet<String>()
    for (c in inPeriod) {
        val who = identity(c.author, c.email)
        people += who
        val current = c.files.filter { it.path in shapes }
        for (f in current) {
            val a = acc.getOrPut(f.path) { Acc() }
            a.commits++; a.churn += f.added + f.deleted; a.last = max(a.last, c.time)
            val au = a.authors.getOrPut(who) { Author(c.author, c.email) }
            au.changes += f.added + f.deleted; au.commits++; au.last = max(au.last, c.time)
        }
        val paths = current.map { it.path }.distinct().sorted()
        if (paths.size in 2..opt.maxFilesPerCommit) {
            for (i in paths.indices) for (j in i + 1 until paths.size) pairs.merge(paths[i] to paths[j], 1, Int::plus)
        }
    }
    val maxCommits = acc.values.maxOfOrNull { it.commits } ?: 0
    val maxComplexity = shapes.values.maxOfOrNull { if (it.complexity > 0) it.complexity else it.loc } ?: 0

    val files = shapes.map { (path, shape) ->
        val a = acc[path]
        val authors = a?.authors?.values?.sortedWith(compareByDescending<Author> { it.changes }.thenByDescending { it.commits }) ?: emptyList()
        val total = authors.sumOf { it.changes }
        val mainShare = if (authors.isEmpty()) 0.0 else if (total == 0) 1.0 else authors[0].changes.toDouble() / total
        val main = authors.firstOrNull()
        val knowledge = when {
            main == null || (authors.size > 1 && mainShare < 0.8) -> Knowledge.SHARED
            opt.now - (seen[identity(main.name, main.email)] ?: 0L) > opt.inactiveAfter -> Knowledge.LOST
            else -> Knowledge.SINGLE
        }
        FileStat(path, a?.commits ?: 0, a?.churn ?: 0, shape.loc, shape.complexity, shape.maxDepth, a?.last, authors, mainShare,
            hotspotScore(a?.commits ?: 0, if (shape.complexity > 0) shape.complexity else shape.loc, maxCommits, maxComplexity), knowledge)
    }.sortedWith(compareByDescending<FileStat> { it.score }.thenByDescending { it.commits }.thenBy { it.path })
    var rank = 0
    for (f in files) f.rank = if (f.commits > 0 && f.score > 0) ++rank else 0

    val byPath = files.associateBy { it.path }
    val coupling = pairs.mapNotNull { (k, shared) ->
        if (shared < opt.minShared) return@mapNotNull null
        val degree = shared / ((byPath.getValue(k.first).commits + byPath.getValue(k.second).commits) / 2.0)
        if (degree >= opt.minDegree) Coupling(k.first, k.second, shared, min(1.0, degree)) else null
    }.sortedWith(compareByDescending<Coupling> { it.degree * it.shared }.thenByDescending { it.shared })

    return Analysis(files, coupling, inPeriod.size, people.size, opt.since, opt.now, maxCommits, maxComplexity)
}

/** 0..1: how often it changes times how complex it is, each on a log scale so one monster file doesn't flatten the rest. */
fun hotspotScore(commits: Int, complexity: Int, maxCommits: Int, maxComplexity: Int): Double {
    if (commits <= 0 || maxCommits <= 0 || maxComplexity <= 0) return 0.0
    val c = ln(1.0 + commits) / ln(1.0 + maxCommits)
    val x = ln(1.0 + max(0, complexity)) / ln(1.0 + maxComplexity)
    return (c * x * 1000).roundToInt() / 1000.0
}

enum class Level(val label: String) { CRITICAL("Critical hotspot"), HIGH("Hotspot"), MEDIUM("Warm"), LOW("Cool"), NONE("Not changed") }

fun level(score: Double) = when {
    score >= 0.6 -> Level.CRITICAL
    score >= 0.4 -> Level.HIGH
    score >= 0.2 -> Level.MEDIUM
    score > 0 -> Level.LOW
    else -> Level.NONE
}

// ------------------------------------------------------------------------------------------------ colours

enum class ColorMode(val label: String, val hint: String, val low: String, val high: String) {
    HOTSPOT("Hotspots", "Changed often and complex: where bugs live", "calm", "hot"),
    CHURN("Change frequency", "How many commits touched the file", "rarely", "constantly"),
    COMPLEXITY("Complexity", "How deeply nested the code is", "simple", "deeply nested"),
    AGE("Code age", "How long since it last changed", "fresh", "untouched"),
    KNOWLEDGE("Knowledge risk", "Files only one person knows", "shared", "one person"),
}

/** The value a colour mode shows, 0..1 (higher = more attention needed), or -1 for "no data". */
fun modeValue(f: FileStat, mode: ColorMode, a: Analysis): Double = when (mode) {
    ColorMode.HOTSPOT -> if (f.commits > 0) f.score else -1.0
    ColorMode.CHURN -> if (f.commits > 0 && a.maxCommits > 0) ln(1.0 + f.commits) / ln(1.0 + a.maxCommits) else -1.0
    ColorMode.COMPLEXITY -> if (a.maxComplexity > 0) ln(1.0 + (if (f.complexity > 0) f.complexity else f.loc)) / ln(1.0 + a.maxComplexity) else 0.0
    ColorMode.AGE -> f.lastChange?.let { min(1.0, (a.until - it).toDouble() / max(DAY, a.until - (a.since ?: (a.until - 730 * DAY)))) } ?: 1.0
    ColorMode.KNOWLEDGE -> when {
        f.commits == 0 -> -1.0
        f.knowledge == Knowledge.LOST -> 1.0
        f.knowledge == Knowledge.SINGLE -> 0.6
        else -> max(0.0, (f.mainShare - 0.4) * 0.8)
    }
}

private val STOPS = listOf(0.0 to intArrayOf(38, 52, 74), 0.3 to intArrayOf(47, 90, 122), 0.52 to intArrayOf(122, 90, 168), 0.76 to intArrayOf(255, 122, 61), 1.0 to intArrayOf(255, 61, 98))

/** Cool slate → blue → violet → orange → hot coral, as 0xRRGGBB. -1 (no data) is a quiet grey. */
fun heatRgb(v: Double): Int {
    if (v < 0) return 0x3a3d46
    val t = v.coerceIn(0.0, 1.0)
    for (i in 1 until STOPS.size) {
        val (t1, c1) = STOPS[i]; val (t0, c0) = STOPS[i - 1]
        if (t <= t1) {
            val k = (t - t0) / (t1 - t0)
            val c = IntArray(3) { j -> (c0[j] + (c1[j] - c0[j]) * k).roundToInt() }
            return (c[0] shl 16) or (c[1] shl 8) or c[2]
        }
    }
    return 0xff3d62
}

// ------------------------------------------------------------------------------------------------ words

fun ago(ms: Long?, now: Long): String {
    if (ms == null) return "not in this period"
    val d = (now - ms) / DAY
    return when {
        d < 1 -> "today"
        d == 1L -> "yesterday"
        d < 30 -> "$d days ago"
        d < 365 -> (d / 30.4).roundToInt().let { "$it month${if (it == 1) "" else "s"} ago" }
        else -> (d / 365.0).roundToInt().let { "$it year${if (it == 1) "" else "s"} ago" }
    }
}

fun periodLabel(months: Int) = when {
    months <= 0 -> "all history"
    months % 12 == 0 -> if (months == 12) "the last year" else "the last ${months / 12} years"
    else -> "the last $months months"
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** Why a file is (or isn't) a hotspot, in one or two sentences. */
fun explain(f: FileStat, a: Analysis): String {
    if (f.commits == 0) return "Not changed in this period · ${plural(f.loc, "line")} of code."
    val who = if (f.authors.size == 1) "only ${f.authors[0].name}" else "${f.authors.size} people, mostly ${f.authors[0].name} (${(f.mainShare * 100).roundToInt()}%)"
    val with = a.coupledWith(f.path).firstOrNull()
    return "Changed in ${plural(f.commits, "commit")} · ${plural(f.loc, "line")} · nested up to ${plural(f.maxDepth, "level")} deep. Changed by $who" +
        (with?.let { ". Usually changes with ${it.b.substringAfterLast('/')} (${(it.degree * 100).roundToInt()}% of the time)" } ?: "") + "."
}

/** A Markdown report for a pull request, an issue or a team meeting. */
fun markdownReport(a: Analysis, months: Int, project: String): String = buildString {
    appendLine("## Code hotspots: $project").appendLine()
    appendLine("${a.commits} commits by ${a.authors} people in ${periodLabel(months)}, across ${a.files.size} files.").appendLine()
    val top = a.ranked.take(10)
    if (top.isNotEmpty()) {
        appendLine("### Top hotspots").appendLine()
        appendLine("| # | File | Commits | Lines | Depth | Main author |").appendLine("|---|---|---:|---:|---:|---|")
        for (f in top) appendLine("| ${f.rank} | `${f.path}` | ${f.commits} | ${f.loc} | ${f.maxDepth} | ${f.authors.firstOrNull()?.name ?: "—"} (${(f.mainShare * 100).roundToInt()}%) |")
        appendLine()
    }
    if (a.coupling.isNotEmpty()) {
        appendLine("### Files that change together").appendLine()
        for (c in a.coupling.take(8)) appendLine("- `${c.a}` ↔ `${c.b}`: ${c.shared} commits together (${(c.degree * 100).roundToInt()}%)")
        appendLine()
    }
    val risky = a.files.filter { it.knowledge != Knowledge.SHARED && it.commits >= 3 }
        .sortedWith(compareBy<FileStat> { if (it.knowledge == Knowledge.LOST) 0 else 1 }.thenByDescending { it.score }).take(8)
    if (risky.isNotEmpty()) {
        appendLine("### Knowledge risks").appendLine()
        for (f in risky) appendLine("- `${f.path}`: " + if (f.knowledge == Knowledge.LOST) "written by ${f.authors[0].name}, who hasn't committed in over 6 months" else "only ${f.authors[0].name} has changed it")
        appendLine()
    }
    append("_Made with [Hotspots Veyra](https://veyraeditor.com/jetbrains/hotspots)._")
}

// ------------------------------------------------------------------------------------------------ globs

private val SPECIAL = Regex("""[.+^$()|\[\]\\]""")

// A glob such as node_modules or *.min.* patterns (with ** for any folders), as a Regex over project-relative POSIX paths.
fun globToRegex(glob: String): Regex {
    val re = StringBuilder()
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && glob.getOrNull(i + 1) == '*' -> { val slash = glob.getOrNull(i + 2) == '/'; re.append(if (slash) "(?:.*/)?" else ".*"); i += if (slash) 3 else 2 }
            c == '*' -> { re.append("[^/]*"); i++ }
            c == '?' -> { re.append("[^/]"); i++ }
            c == '{' && glob.indexOf('}', i) > i -> {
                val end = glob.indexOf('}', i)
                re.append("(?:").append(glob.substring(i + 1, end).split(',').joinToString("|") { it.replace(SPECIAL) { m -> "\\" + m.value }.replace("*", "[^/]*") }).append(')')
                i = end + 1
            }
            else -> { re.append(if (SPECIAL.matches(c.toString())) "\\$c" else c.toString()); i++ }
        }
    }
    return Regex("^$re$")
}

fun isExcluded(path: String, globs: List<Regex>) = globs.any { it.matches(path) || it.matches("$path/") }

val DEFAULT_EXCLUDES = listOf(
    "**/node_modules/**", "**/dist/**", "**/build/**", "**/out/**", "**/.next/**", "**/vendor/**", "**/target/**", "**/.gradle/**",
    "**/*.min.*", "**/*.map", "**/*.lock", "**/package-lock.json", "**/pnpm-lock.yaml", "**/yarn.lock", "**/*.snap", "**/*.svg",
    "**/__snapshots__/**", "**/migrations/**", "**/*.generated.*", "**/gradlew", "**/gradlew.bat",
)
