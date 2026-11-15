// Device Lab Veyra: what it learns from the open project, without the Android plugin: the app's package names
// (applicationId) and every deep link it declares in AndroidManifest.xml and Navigation graphs.
package com.flightcoders.veyra.devicelab

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

private val SKIP = setOf("build", ".gradle", ".git", ".idea", "node_modules", ".cxx", ".kotlin", "intermediates", "generated")
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val APP_NS = "http://schemas.android.com/apk/res-auto"

/** Project files up to [depth] folders down, skipping build output. */
fun projectFiles(root: File, depth: Int = 6, accept: (File) -> Boolean): List<File> {
    val out = mutableListOf<File>()
    fun walk(dir: File, level: Int) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) { if (level < depth && child.name !in SKIP && !child.name.startsWith('.')) walk(child, level + 1) }
            else if (accept(child)) out += child
        }
    }
    walk(root, 0)
    return out
}

/** `applicationId = "com.shop"` (Kotlin DSL) or `applicationId "com.shop"` (Groovy), in each app module. */
fun applicationIds(root: File): List<String> = projectFiles(root, 3) { it.name == "build.gradle.kts" || it.name == "build.gradle" }
    .flatMap { applicationIdsIn(it.readText()) }.distinct()

fun applicationIdsIn(gradle: String): List<String> =
    Regex("""(?m)(?:^|[{;])\s*applicationId\s*(?:=\s*)?["']([A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+)["']""").findAll(gradle).map { it.groupValues[1] }.toList()

/** A deep link the app declares, with where it came from. */
data class DeepLink(val uri: String, val source: String)

fun deepLinks(root: File): List<DeepLink> {
    val files = projectFiles(root) { f ->
        f.name == "AndroidManifest.xml" || (f.extension == "xml" && f.parentFile?.name?.startsWith("navigation") == true)
    }
    return files.flatMap { file ->
        val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: file.name
        runCatching { if (file.name == "AndroidManifest.xml") manifestLinks(file.readText()) else navigationLinks(file.readText()) }
            .getOrDefault(emptyList()).map { DeepLink(it, rel) }
    }.distinctBy { it.uri }
}

private fun parse(xml: String): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Project files only, but never fetch anything while parsing.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().parse(xml.byteInputStream())
}

private fun Element.children(name: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.localName == name || it.tagName == name }

/**
 * Intent filters with the VIEW action and a scheme. Android combines every scheme, host and path in one filter,
 * so a filter with schemes http+https, host shop.com and path /item gives two links.
 */
fun manifestLinks(xml: String): List<String> {
    val doc = parse(xml)
    val filters = doc.getElementsByTagName("intent-filter")
    val links = mutableListOf<String>()
    for (i in 0 until filters.length) {
        val filter = filters.item(i) as Element
        if (filter.children("action").none { it.getAttributeNS(ANDROID_NS, "name") == "android.intent.action.VIEW" }) continue
        val data = filter.children("data")
        fun attr(name: String) = data.map { it.getAttributeNS(ANDROID_NS, name) }.filter { it.isNotEmpty() }.distinct()
        val schemes = attr("scheme").filterNot { it.contains("\${") }
        if (schemes.isEmpty()) continue
        val hosts = attr("host").ifEmpty { listOf("") }
        val ports = attr("port")
        val paths = (attr("path") + attr("pathPrefix") + attr("pathPattern").map(::examplePath) + attr("pathAdvancedPattern").map(::examplePath)).ifEmpty { listOf("") }
        for (scheme in schemes) for (host in hosts) for (path in paths) {
            val authority = host.replace("*.", "www.").let { if (it.isNotEmpty() && ports.isNotEmpty()) "$it:${ports.first()}" else it }
            links += if (authority.isEmpty()) "$scheme:${path.removePrefix("/")}" else "$scheme://$authority${if (path.isEmpty() || path.startsWith('/')) path else "/$path"}"
        }
    }
    return links.distinct()
}

/** `<deepLink app:uri="shop://item/{id}"/>` in a Navigation graph. Arguments become examples: {id} → 1. */
fun navigationLinks(xml: String): List<String> {
    val doc = parse(xml)
    val nodes = doc.getElementsByTagName("deepLink")
    return (0 until nodes.length).map { nodes.item(it) as Element }
        .mapNotNull { it.getAttributeNS(APP_NS, "uri").ifEmpty { it.getAttribute("app:uri") }.ifEmpty { null } }
        .map { uri -> uri.replace(Regex("\\{[^}]*}"), "1").replace(".*", "") }
        .map { if (Regex("^[a-zA-Z][\\w+.-]*:").containsMatchIn(it)) it else "https://$it" }
        .distinct()
}

/** A path that matches a simple pattern: /item/.* → /item/1. */
private fun examplePath(pattern: String): String =
    pattern.replace("\\\\", "\\").replace(".*", "1").replace(".+", "1").replace("\\.", ".").replace("*", "").replace("\\", "")
