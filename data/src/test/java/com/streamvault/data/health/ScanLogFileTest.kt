package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanLogFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun logIn(name: String = "scan.log", maxBytes: Long = 1_000L) =
        ScanLogFile(File(folder.root, name), nowMillis = { 0L }, maxBytes = maxBytes)

    @Test
    fun `a line is written with a timestamp`() {
        logIn().write("scan start scope=ALL")

        val lines = File(folder.root, "scan.log").readLines()
        assertThat(lines).containsExactly("1970-01-01T00:00:00Z scan start scope=ALL")
    }

    @Test
    fun `lines are appended in order`() {
        val log = logIn()
        log.write("one")
        log.write("two")

        assertThat(File(folder.root, "scan.log").readLines().map { it.substringAfter(' ') })
            .containsExactly("one", "two").inOrder()
    }

    @Test
    fun `an oversized file keeps its newest lines`() {
        val log = logIn(maxBytes = 200L)
        repeat(40) { log.write("line-$it") }

        val kept = File(folder.root, "scan.log").readLines().map { it.substringAfter(' ') }
        assertThat(kept.last()).isEqualTo("line-39")
        assertThat(kept).doesNotContain("line-0")
        assertThat(File(folder.root, "scan.log").length()).isAtMost(400L)
    }

    @Test
    fun `a file that cannot be written never throws`() {
        // The parent folder does not exist, so appending fails; the scan must not notice.
        ScanLogFile(File(File(folder.root, "missing"), "scan.log"), nowMillis = { 0L }).write("scan start")
    }
}
