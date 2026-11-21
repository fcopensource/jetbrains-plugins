// Commits, from the IDE's Git plugin (loaded only when Git is installed: see META-INF/aura-git.xml).
package com.flightcoders.veyra.aura.git

import com.flightcoders.veyra.aura.AuraService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Notices every new commit, however it was made (the Commit window, the terminal or another tool): when a repository's
 * HEAD moves, git's reflog says why. "commit: …" is a new commit; checkouts, pulls, resets and amends are not.
 */
class AuraGitListener(private val project: Project) : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        val head = repository.currentRevision ?: return
        val root = repository.root.path
        val previous = heads.put(root, head)
        if (previous == head) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val entry = lastReflog(root) ?: return@executeOnPooledThread
            if (entry.hash != head || !seen.add(head)) return@executeOnPooledThread
            // The first change we see in a session has no "previous": only count it if it just happened.
            if (previous == null && System.currentTimeMillis() - entry.time * 1000 > 120_000) return@executeOnPooledThread
            if (isNewCommit(entry.action)) AuraService.instance.onCommit(entry.message)
        }
    }

    private data class Reflog(val hash: String, val action: String, val time: Long, val message: String)

    private fun lastReflog(root: String): Reflog? = runCatching {
        val git = GitExecutableManager.getInstance().getPathToGit(project)
        val cmd = GeneralCommandLine(git, "log", "-g", "-1", "--format=%H%x00%gs%x00%ct%x00%B", "HEAD")
            .withWorkDirectory(root).withCharset(Charsets.UTF_8)
        val out = ExecUtil.execAndGetOutput(cmd, 10_000)
        if (out.exitCode != 0) return null
        val p = out.stdout.split('\u0000')
        if (p.size < 4) null else Reflog(p[0].trim(), p[1], p[2].trim().toLongOrNull() ?: 0, p[3].trim())
    }.getOrNull()

    companion object {
        private val heads = ConcurrentHashMap<String, String>()
        private val seen = ConcurrentHashMap.newKeySet<String>()

        /** A reflog subject like "commit: add tax" or "commit (initial): …"; not amends, merges, pulls or checkouts. */
        fun isNewCommit(action: String) = action.startsWith("commit:") || action.startsWith("commit (initial):")
    }
}
