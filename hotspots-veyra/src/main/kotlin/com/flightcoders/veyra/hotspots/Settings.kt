// Per-project settings, under Settings → Tools → Hotspots Veyra.
package com.flightcoders.veyra.hotspots

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

val PERIODS = listOf(1 to "Last month", 3 to "Last 3 months", 6 to "Last 6 months", 12 to "Last year", 24 to "Last 2 years", 0 to "All history")

@Service(Service.Level.PROJECT)
@State(name = "HotspotsVeyra", storages = [Storage("hotspots-veyra.xml")])
class HotspotsSettings : SimplePersistentStateComponent<HotspotsSettings.Options>(Options()) {
    class Options : BaseState() {
        var months by property(12)
        var projectViewFlames by property(true)
        var flameCount by property(10)
        var statusBar by property(true)
        var exclude by string(DEFAULT_EXCLUDES.joinToString("\n"))
        var maxCommits by property(20000)
    }

    val excludeGlobs: List<Regex> get() = (state.exclude ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::globToRegex)

    companion object { fun of(project: Project): HotspotsSettings = project.service() }
}

class HotspotsConfigurable(private val project: Project) : BoundConfigurable("Hotspots Veyra") {
    private val s get() = HotspotsSettings.of(project).state

    override fun createPanel() = panel {
        group("History") {
            row("Read:") {
                comboBox(PERIODS.map { it.first }, com.intellij.ui.SimpleListCellRenderer.create("") { m -> PERIODS.first { it.first == m }.second })
                    .bindItem({ s.months }, { s.months = it ?: 12 })
                comment("How much git history to read. A year is a good default.")
            }
            row("Most commits:") { intTextField(100..1_000_000).bindIntText({ s.maxCommits }, { v -> s.maxCommits = v }) }
        }
        group("Where hotspots show") {
            row { checkBox("Flames on the hottest files in the Project view").bindSelected({ s.projectViewFlames }, { s.projectViewFlames = it }) }
            row("How many:") { intTextField(0..100).bindIntText({ s.flameCount }, { v -> s.flameCount = v }) }
            row { checkBox("The open file's rank in the status bar").bindSelected({ s.statusBar }, { s.statusBar = it }) }
        }
        group("Leave out") {
            row {
                textArea().rows(8).align(AlignX.FILL).bindText({ s.exclude ?: "" }, { s.exclude = it })
            }
            row { comment("One glob per line, like **/generated/** or **/*.pb.go. Lock files, build output and minified files are left out by default.") }
        }
    }

    override fun apply() {
        super.apply()
        HotspotsService.of(project).refresh()
    }
}
