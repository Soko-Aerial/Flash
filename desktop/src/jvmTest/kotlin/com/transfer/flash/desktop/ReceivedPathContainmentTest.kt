package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedPathContainmentTest {

    private val tmp = Files.createTempDirectory("flash-contain").toFile().canonicalFile

    @Test
    fun aFileInsideItsTransferFolderInsideTheRootIsContained() {
        val root = File(tmp, "root").apply { mkdirs() }.canonicalFile
        val destDir = File(root, "t1").apply { mkdirs() }.canonicalFile
        assertTrue(isContainedInReceivedRoot(root, destDir, File(destDir, "a.txt").canonicalFile))
    }

    @Test
    fun aTransferFolderOutsideTheRootIsRefusedEvenThoughTheFileIsInsideIt() {
        val root = File(tmp, "root").apply { mkdirs() }.canonicalFile
        val outside = File(tmp, "startup").apply { mkdirs() }.canonicalFile
        assertFalse(isContainedInReceivedRoot(root, outside, File(outside, "evil.exe").canonicalFile))
    }

    @Test
    fun aSiblingFolderWithTheSamePrefixIsNotTheRoot() {
        val root = File(tmp, "root").apply { mkdirs() }.canonicalFile
        val sibling = File(tmp, "root-evil").apply { mkdirs() }.canonicalFile
        assertFalse(isContainedInReceivedRoot(root, sibling, File(sibling, "x").canonicalFile))
    }

    @Test
    fun aDirectorySymlinkNamedLikeTheTransferIdIsRefused() {
        val root = File(tmp, "root").apply { mkdirs() }.canonicalFile
        val outside = File(tmp, "startup").apply { mkdirs() }.canonicalFile
        val link = File(root, "t1")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return // creating symlinks needs a privilege on Windows; the pure cases above still run
        val destDir = File(root, "t1").canonicalFile
        assertFalse(isContainedInReceivedRoot(root, destDir, File(destDir, "evil.exe").canonicalFile))
    }
}
