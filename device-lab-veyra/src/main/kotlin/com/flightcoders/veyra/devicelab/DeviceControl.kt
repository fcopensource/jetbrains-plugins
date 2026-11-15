// Device Lab Veyra: reading and changing a phone's or emulator's settings over adb, one shell command per change.
// Every command here was checked on Android 16 (API 36); older versions are handled where they differ.
package com.flightcoders.veyra.devicelab

import java.util.Locale

enum class Theme(val label: String, val arg: String) { LIGHT("Light", "no"), DARK("Dark", "yes"), AUTO("Auto", "auto") }

/** A device's current settings, as far as Device Lab controls them. */
data class DeviceState(
    val sdk: Int,
    val release: String,
    val theme: Theme,
    val fontScale: Float,
    val physicalDensity: Int,
    val density: Int,
    val animationScale: Float,
    val layoutBounds: Boolean,
    val showTaps: Boolean,
    val forceRtl: Boolean,
    val airplane: Boolean,
)

/** One runtime permission an app asks for, and whether it has it. */
data class Permission(val name: String, val granted: Boolean) {
    val shortName: String get() = name.substringAfterLast('.')
}

class DeviceControl(private val adb: Adb, val serial: String) {
    private fun adbRun(args: List<String>, timeoutMs: Long = 15_000) = adb.run(listOf("-s", serial) + args, timeoutMs)

    /** Runs a command in the device's shell. Fails with the device's own message when it reports an error. */
    fun shell(command: String, timeoutMs: Long = 15_000): String {
        val result = adbRun(listOf("shell", command), timeoutMs)
        val text = result.text
        if (!result.ok) throw DeviceException(result.error.ifBlank { text.trim() }.ifBlank { "adb exited with ${result.exit}" })
        text.lineSequence().firstOrNull { it.startsWith("Error:") || it.startsWith("Exception occurred") || it.startsWith("Failure") }
            ?.let { throw DeviceException(it.removePrefix("Error:").trim()) }
        return text
    }

    fun readState(): DeviceState {
        val keys = listOf(
            "sdk" to "getprop ro.build.version.sdk", "release" to "getprop ro.build.version.release",
            "night" to "cmd uimode night", "font" to "settings get system font_scale", "density" to "wm density",
            "anim" to "settings get global window_animation_scale", "layout" to "getprop debug.layout",
            "taps" to "settings get system show_touches", "rtl" to "settings get global debug.force_rtl",
            "airplane" to "settings get global airplane_mode_on",
        )
        val values = parseSections(shell(keys.joinToString("; ") { (key, cmd) -> "echo '@@$key'; $cmd" }))
        return stateFrom(values)
    }

    fun setTheme(theme: Theme) { shell("cmd uimode night ${theme.arg}") }
    fun setFontScale(scale: Float) { shell("settings put system font_scale ${format(scale)}") }
    /** null puts the display size back to the device's own. */
    fun setDensity(dpi: Int?) { shell(if (dpi == null) "wm density reset" else "wm density $dpi") }
    fun setAnimationScale(scale: Float) {
        val value = format(scale)
        shell(listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").joinToString("; ") { "settings put global $it $value" })
    }
    /** The developer option "Show layout bounds": set the property, then tell running apps to redraw. */
    fun setLayoutBounds(on: Boolean) { shell("setprop debug.layout $on; service call activity 1599295570 > /dev/null") }
    fun setShowTaps(on: Boolean) { shell("settings put system show_touches ${if (on) 1 else 0}") }
    /** "Force RTL layout direction". Apps pick it up when they restart. */
    fun setForceRtl(on: Boolean) {
        val v = if (on) 1 else 0
        shell("settings put global debug.force_rtl $v; setprop debug.force_rtl $v; service call activity 1599295570 > /dev/null")
    }
    fun setAirplane(on: Boolean, sdk: Int) {
        if (sdk >= 30) shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        else shell("settings put global airplane_mode_on ${if (on) 1 else 0}; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on > /dev/null")
    }

    /** A clean status bar for screenshots: 12:00, full battery, full signal, no notifications. */
    fun setDemoMode(on: Boolean) {
        val demo = "am broadcast -a com.android.systemui.demo -e command"
        shell(
            if (!on) "$demo exit > /dev/null"
            else listOf(
                "settings put global sysui_demo_allowed 1",
                "$demo enter", "$demo clock -e hhmm 1200", "$demo battery -e level 100 -e plugged false",
                "$demo network -e wifi show -e level 4 -e fully true", "$demo network -e mobile show -e datatype none -e level 4",
                "$demo notifications -e visible false",
            ).joinToString("; ") { "$it > /dev/null" },
        )
    }

    /* ---------- The app being developed ---------- */

    fun isInstalled(pkg: String): Boolean = runCatching { shell("pm path ${shellQuote(pkg)}").contains("package:") }.getOrDefault(false)

    /** Android 13+: the app's own language, like Settings → Apps → Language. An empty tag goes back to the system language. */
    fun setAppLanguage(pkg: String, tag: String) { shell("cmd locale set-app-locales ${shellQuote(pkg)} --locales ${shellQuote(tag)}") }
    fun appLanguage(pkg: String): String = parseAppLocales(shell("cmd locale get-app-locales ${shellQuote(pkg)}"))

    fun permissions(pkg: String): List<Permission> = parsePermissions(shell("dumpsys package ${shellQuote(pkg)}", 20_000))
    fun setPermission(pkg: String, permission: String, granted: Boolean) {
        shell("pm ${if (granted) "grant" else "revoke"} ${shellQuote(pkg)} ${shellQuote(permission)}")
    }

    fun restartApp(pkg: String) {
        val q = shellQuote(pkg)
        val out = shell("am force-stop $q; monkey -p $q -c android.intent.category.LAUNCHER 1")
        if (out.contains("No activities found")) throw DeviceException("$pkg has no launcher screen to open")
    }
    fun clearData(pkg: String) {
        if (!shell("pm clear ${shellQuote(pkg)}").contains("Success")) throw DeviceException("Couldn't clear $pkg's data")
    }

    /** Opens a link the way a tap in another app would, and says which screen opened and how fast. */
    fun openLink(uri: String, pkg: String?): LinkResult {
        val target = pkg?.takeIf { it.isNotBlank() }?.let { " ${shellQuote(it)}" }.orEmpty()
        val out = try {
            shell("am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d ${shellQuote(uri)}$target", 30_000)
        } catch (e: DeviceException) {
            if (e.message.orEmpty().contains("unable to resolve Intent")) throw DeviceException("No app opens $uri") else throw e
        }
        return parseLaunch(out)
    }

    /** A PNG of the screen. */
    fun screenshot(): ByteArray {
        val result = adbRun(listOf("exec-out", "screencap", "-p"), 30_000)
        if (!result.ok || result.bytes.size < 8 || result.bytes[1] != 'P'.code.toByte()) throw DeviceException(result.error.ifBlank { "The screenshot failed" })
        return result.bytes
    }

    /** Puts back everything Device Lab changes, as it was in [before]. */
    fun restore(before: DeviceState) {
        setTheme(before.theme)
        setFontScale(before.fontScale)
        setDensity(before.density.takeIf { it != before.physicalDensity })
        setAnimationScale(before.animationScale)
        setLayoutBounds(before.layoutBounds)
        setShowTaps(before.showTaps)
        setForceRtl(before.forceRtl)
        setAirplane(before.airplane, before.sdk)
        setDemoMode(false)
    }
}

class DeviceException(message: String) : Exception(message)

/** What `am start -W` reported: the screen that opened, COLD/WARM/HOT (or null when it was already open) and the time. */
data class LinkResult(val activity: String?, val launch: String?, val millis: Long?) {
    val summary: String get() {
        val screen = activity?.substringAfterLast('/')?.removePrefix(".")?.substringAfterLast('.') ?: "the app"
        val how = when (launch) { "COLD" -> "cold start"; "WARM" -> "warm start"; "HOT" -> "hot start"; else -> "already open" }
        return "$screen ($how${millis?.takeIf { launch != null && it > 0 }?.let { ", $it ms" }.orEmpty()})"
    }
}

fun parseLaunch(text: String): LinkResult = LinkResult(
    activity = Regex("(?m)^Activity: (\\S+)").find(text)?.groupValues?.get(1),
    launch = Regex("(?m)^LaunchState: (COLD|WARM|HOT)").find(text)?.groupValues?.get(1),
    millis = Regex("(?m)^TotalTime: (\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull(),
)

private fun format(value: Float): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.').let { if ('.' in it) it else "$it.0" }

/** Output of commands separated by `echo '@@key'` lines. */
fun parseSections(text: String): Map<String, String> {
    val out = mutableMapOf<String, StringBuilder>()
    var current: StringBuilder? = null
    for (line in text.replace("\r", "").lines()) {
        if (line.startsWith("@@")) current = StringBuilder().also { out[line.removePrefix("@@").trim()] = it }
        else current?.appendLine(line)
    }
    return out.mapValues { it.value.toString().trim() }
}

fun stateFrom(v: Map<String, String>): DeviceState {
    fun float(key: String, default: Float) = v[key]?.toFloatOrNull() ?: default
    fun flag(key: String) = v[key]?.trim()?.let { it == "1" || it == "true" } ?: false
    val density = v["density"].orEmpty()
    val physical = Regex("Physical density: (\\d+)").find(density)?.groupValues?.get(1)?.toInt() ?: 0
    val override = Regex("Override density: (\\d+)").find(density)?.groupValues?.get(1)?.toInt()
    val night = v["night"].orEmpty().substringAfter(':', "").trim()
    return DeviceState(
        sdk = v["sdk"]?.trim()?.toIntOrNull() ?: 0,
        release = v["release"]?.trim().orEmpty(),
        theme = when (night) { "yes" -> Theme.DARK; "auto", "custom" -> Theme.AUTO; else -> Theme.LIGHT },
        fontScale = float("font", 1f),
        physicalDensity = physical,
        density = override ?: physical,
        animationScale = float("anim", 1f),
        layoutBounds = flag("layout"),
        showTaps = flag("taps"),
        forceRtl = flag("rtl"),
        airplane = flag("airplane"),
    )
}

/** `dumpsys package`: the "runtime permissions:" lines of the app's user (the first block), without duplicates. */
fun parsePermissions(text: String): List<Permission> {
    val lines = text.replace("\r", "").lines()
    val start = lines.indexOfFirst { it.trim() == "runtime permissions:" }
    if (start < 0) return emptyList()
    val indent = lines[start].takeWhile { it == ' ' }.length
    val result = linkedMapOf<String, Permission>()
    for (line in lines.drop(start + 1)) {
        if (line.isBlank() || line.takeWhile { it == ' ' }.length <= indent) break
        val m = Regex("^\\s*([\\w.]+): granted=(true|false)").find(line) ?: continue
        result.putIfAbsent(m.groupValues[1], Permission(m.groupValues[1], m.groupValues[2] == "true"))
    }
    return result.values.sortedBy { it.shortName }
}

/** "Locales for com.x for user 0 are [ar,en-US]" → "ar". */
fun parseAppLocales(text: String): String = Regex("are \\[([^]]*)]").find(text)?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim().orEmpty()

/** Android's "Display size" steps: the device's own density times these. */
val DISPLAY_SIZES = listOf("Small" to 0.85f, "Default" to 1f, "Large" to 1.1f, "Larger" to 1.2f, "Largest" to 1.3f)
fun densityFor(physical: Int, factor: Float): Int = Math.round(physical * factor)

/** Font sizes. Android 14 (API 34) and newer go up to 200 %. */
fun fontScales(sdk: Int): List<Pair<String, Float>> = buildList {
    add("Small (85 %)" to 0.85f); add("Default" to 1f); add("Large (115 %)" to 1.15f); add("Largest (130 %)" to 1.3f)
    if (sdk >= 34) { add("150 %" to 1.5f); add("180 %" to 1.8f); add("200 % (maximum)" to 2f) }
}
fun largestFontScale(sdk: Int): Float = if (sdk >= 34) 2f else 1.3f

val ANIMATION_SCALES = listOf("Off" to 0f, "0.5×" to 0.5f, "Normal" to 1f, "2× slower" to 2f, "5× slower" to 5f, "10× slower" to 10f)

/** Languages that find real bugs: right-to-left, long words, tall scripts, CJK, and Android's pseudolocales. */
val LANGUAGES = listOf(
    "" to "System language",
    "ar" to "Arabic (right-to-left)", "he" to "Hebrew (right-to-left)", "de" to "German (long words)",
    "fi" to "Finnish (long words)", "ru" to "Russian", "hi" to "Hindi", "th" to "Thai (tall script)",
    "ja" to "Japanese", "zh-CN" to "Chinese (Simplified)", "ko" to "Korean", "es" to "Spanish", "fr" to "French",
    "pt-BR" to "Portuguese (Brazil)", "en-XA" to "Pseudo: [Ŀőńğ ãçç€ñţš]", "ar-XB" to "Pseudo: mirrored right-to-left",
)
