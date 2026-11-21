package com.flightcoders.veyra.aura

import com.flightcoders.veyra.aura.ui.AuraWidget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.problems.ProblemListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/** The service and the IDE listeners, in a light IDE. */
class AuraServiceTest : BasePlatformTestCase() {
    private lateinit var service: AuraService
    // A fixed noon: no night-owl bonus when the tests run after midnight.
    private val noon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    override fun setUp() {
        super.setUp()
        service = AuraService.instance
        service.now = { noon }
        service.reset()
        service.errorAgeMs = 0
        service.settings.roast = true
        service.settings.enabled = true
    }

    private fun ids() = service.aura.events.map { it.id }

    fun `test clearing every error in an open file earns aura`() {
        val file = myFixture.configureByText("Cart.kt", "fun total() = 1").virtualFile
        val publisher = project.messageBus.syncPublisher(ProblemListener.TOPIC)
        publisher.problemsAppeared(file)
        publisher.problemsDisappeared(file)
        assertEquals(listOf(MoveId.CLEARED_ERRORS), ids())
        assertEquals("Cleared every error in Cart.kt", service.aura.events[0].text)
        assertEquals(15, service.aura.total)
    }

    fun `test saving clean code, a big delete and a debugger`() {
        val big = (1..80).joinToString("\n") { "const v$it = $it;" }
        val file = myFixture.addFileToProject("src/legacy.js", big).virtualFile
        // A real save: the listener compares the file on disk with what's being written.
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteAction.runAndWait<Throwable> { document.setText((1..20).joinToString("\n") { "const v$it = $it;" }) }
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveDocument(document) }
        assertEquals(listOf(MoveId.DELETED_LINES, MoveId.CLEAN_SAVE), ids())
        assertEquals("Deleted 60 lines from legacy.js. Less code, more aura.", service.aura.events[0].text)

        WriteAction.runAndWait<Throwable> { document.setText("function pay() {\n  debugger;\n}\n") }
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveDocument(document) }
        assertEquals(MoveId.DEBUGGER_LEFT, ids().last())
    }

    fun `test commits, tests and builds`() {
        service.onCommit("feat: add tax to the cart total")
        service.onCommit("wip")
        service.onTests(passed = true)
        service.onTests(passed = false)
        service.onBuild(passed = true)
        assertEquals(listOf(MoveId.COMMIT, MoveId.GOOD_COMMIT_MESSAGE, MoveId.COMMIT, MoveId.BAD_COMMIT_MESSAGE, MoveId.TESTS_PASSED, MoveId.TESTS_FAILED, MoveId.BUILD_PASSED), ids())
        assertEquals(20 + 5 + 20 - 5 + 20 - 5 + 10, service.aura.total)
    }

    fun `test roast mode off and turning aura off`() {
        service.settings.roast = false
        service.onTests(passed = false)
        assertEquals(0, service.aura.total)
        service.settings.roast = true
        service.settings.enabled = false
        service.onTests(passed = true)
        assertEquals(0, service.aura.total)
        service.settings.enabled = true
    }

    fun `test the aura survives a restart`() {
        service.onCommit("feat: add a cart page with totals")
        service.onTests(passed = true)
        service.give(MoveId.BAD_COMMIT_MESSAGE, mapOf("msg" to "🚀🚀"))
        val saved = XmlSerializer.serialize(service.state)
        val restored = AuraService.Stored()
        XmlSerializer.deserializeInto(restored, saved)
        val before = service.aura
        service.loadState(restored)
        assertEquals(before, service.aura)
        assertEquals(40, service.aura.total)
        assertEquals("📦", service.aura.events.first().emoji)
        assertEquals("Commit message “🚀🚀” is giving nothing", service.aura.events.last().text)
    }

    fun `test the status bar and the panel data`() {
        service.onCommit("feat: add a cart page with totals")
        val widget = AuraWidget(project)
        // A commit is two moves; the status bar shows the last one.
        assertEquals("+5 aura · That commit message ate", widget.text())
        service.now = { noon + 10_000 }
        assertEquals("25 aura", widget.text())
        service.now = { noon }
        val json = service.snapshotJson()
        assertTrue(json, json.startsWith("{\"total\":25,\"totalText\":\"25\",\"rank\":{\"name\":\"NPC\""))
        assertTrue(json, json.contains("\"caption\":\"My code aura: 25 (NPC 🧍)"))
        assertTrue(json, json.contains("\"text\":\"That commit message ate\""))
    }

    fun `test rank up past 100`() {
        repeat(5) { service.onTests(passed = true) }
        assertEquals("Side Character", rankFor(service.aura.total).rank.name)
    }
}
