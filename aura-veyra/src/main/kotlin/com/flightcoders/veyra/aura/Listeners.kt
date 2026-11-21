// Where aura comes from: the IDE's own events for errors, saves, test runs and builds.
package com.flightcoders.veyra.aura

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.ProblemListener
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager

/** Starts Aura with the first project, so typing counts from the start. */
class AuraStartup : ProjectActivity {
    override suspend fun execute(project: Project) { AuraService.instance }
}

/** Errors: the IDE's problem tracker says when a file's errors have all gone. */
class AuraProblemListener(private val project: Project) : ProblemListener {
    override fun problemsAppeared(file: VirtualFile) = AuraService.instance.problemsAppeared(file.path)
    override fun problemsDisappeared(file: VirtualFile) {
        if (project.isDisposed) return
        AuraService.instance.problemsDisappeared(file.path, file.name, FileEditorManager.getInstance(project).isFileOpen(file))
    }
}

/** Saves: clean saves, big deletes and leftover debugger statements. */
class AuraSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val inTests = com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode
        if (!(file.isInLocalFileSystem || inTests) || !AuraService.instance.settings.enabled) return
        // Before the save, the file on disk still has the old text: that's what "deleted 50 lines" compares against.
        val before = runCatching { String(file.contentsToByteArray(), file.charset).lines().size }.getOrNull()
        val hasErrors = ProjectManager.getInstance().openProjects.any { !it.isDisposed && WolfTheProblemSolver.getInstance(it).isProblemFile(file) }
        AuraService.instance.onSave(file.path, file.name, file.extension, document.text, before, hasErrors)
    }
}

/** Test runs in the IDE's test runner: JUnit, TestNG, pytest, Jest, Go, Kotest and the rest. */
class AuraTestListener : SMTRunnerEventsAdapter() {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        if (testsRoot.isInterrupted || testsRoot.isEmptySuite) return
        AuraService.instance.onTests(passed = !testsRoot.isDefect)
    }
}

/** Builds: Build Project, Rebuild and the build before a run. */
class AuraBuildListener : ProjectTaskListener {
    override fun finished(result: ProjectTaskManager.Result) {
        if (result.isAborted) return
        AuraService.instance.onBuild(passed = !result.hasErrors())
    }
}
