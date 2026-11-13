package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 落盘目标（应用私有目录实现）行为测试。
 *
 * 覆盖：相对路径建目录、commit 转正、abort 清理残包、同名文件不覆盖。
 */
class SaveTargetTest {

    private fun tmpDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "heinshare-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun writesToRelativeSubdirectory() {
        val root = tmpDir()
        val target = FileSaveTarget(root)

        val handle = target.open("folder/a.txt")
        handle.output.write("hello".toByteArray())
        val path = handle.commit().target

        val saved = File(path)
        assertTrue("文件应存在", saved.exists())
        assertEquals("hello", saved.readText())
        assertEquals("folder", saved.parentFile!!.name)
        assertTrue(saved.parentFile!!.parentFile == root)

        root.deleteRecursively()
    }

    @Test
    fun commitRenamesPartAway() {
        val root = tmpDir()
        val target = FileSaveTarget(root)

        val handle = target.open("b.bin")
        handle.output.write(byteArrayOf(1, 2, 3))
        handle.commit()

        assertFalse("不应残留 .part", File(root, "b.bin.part").exists())
        assertTrue(File(root, "b.bin").exists())

        root.deleteRecursively()
    }

    @Test
    fun abortRemovesPartialFile() {
        val root = tmpDir()
        val target = FileSaveTarget(root)

        val handle = target.open("c.bin")
        handle.output.write(byteArrayOf(9))
        handle.abort()

        assertFalse("残包应被清理", File(root, "c.bin.part").exists())
        assertFalse("目标不应存在", File(root, "c.bin").exists())

        root.deleteRecursively()
    }

    @Test
    fun duplicateNameGetsSuffix() {
        val root = tmpDir()
        val target = FileSaveTarget(root)

        val h1 = target.open("same.txt")
        h1.output.write("1".toByteArray())
        h1.commit()

        val h2 = target.open("same.txt")
        h2.output.write("2".toByteArray())
        val second = File(h2.commit().target)

        assertEquals("1", File(root, "same.txt").readText())
        assertEquals("2", second.readText())
        assertTrue("重名应加后缀", second.name == "same (1).txt")

        root.deleteRecursively()
    }

    @Test
    fun sanitizesTraversalNames() {
        val segments = Receiver.safeRelSegments("../../etc/passwd")
        assertTrue("不得包含 ..", segments.none { it == ".." })
        assertTrue(segments.isNotEmpty())
    }
}
