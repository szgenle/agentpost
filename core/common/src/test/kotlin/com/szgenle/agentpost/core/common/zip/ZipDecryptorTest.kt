package com.szgenle.agentpost.core.common.zip

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ZipDecryptor] 行为回归测试。
 *
 * 覆盖：
 * - 非 zip / 不存在 → isEncryptedZip=false
 * - 明文 zip（无密码）→ isEncryptedZip=false
 * - AES 加密 zip → isEncryptedZip=true
 * - 正确密码 → Success，解压产物内容与原始一致
 * - 错误密码 → WrongPassword
 * - 空密码 → WrongPassword
 * - 源文件不存在 → Failure
 */
class ZipDecryptorTest {

    private lateinit var work: File

    @Before
    fun setUp() {
        work = Files.createTempDirectory("zipdecryptor-test-").toFile()
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    // ---- isEncryptedZip 识别 ----

    @Test
    fun `non-existent file is not encrypted zip`() {
        val phantom = File(work, "not-here.zip")
        assertFalse(ZipDecryptor.isEncryptedZip(phantom))
    }

    @Test
    fun `plain text file is not encrypted zip`() {
        val txt = File(work, "readme.txt").apply { writeText("hello") }
        assertFalse(ZipDecryptor.isEncryptedZip(txt))
    }

    @Test
    fun `unencrypted zip is not detected as encrypted`() {
        val zip = File(work, "plain.zip")
        val payload = File(work, "plain-payload.txt").apply { writeText("plain-body") }
        ZipFile(zip).addFile(payload)

        assertTrue(zip.exists())
        assertFalse(ZipDecryptor.isEncryptedZip(zip))
    }

    @Test
    fun `encrypted zip is detected`() {
        val zip = makeEncryptedZip(name = "secret.zip", body = "secret-body", password = "opensesame")
        assertTrue(ZipDecryptor.isEncryptedZip(zip))
    }

    // ---- decrypt 行为 ----

    @Test
    fun `decrypt with correct password yields success and original content`() {
        val password = "opensesame"
        val zip = makeEncryptedZip(name = "secret.zip", body = "hello world", password = password)
        val out = File(work, "out-success")

        val result = runBlocking { ZipDecryptor.decrypt(src = zip, outputDir = out, password = password) }

        assertTrue("expected Success but was $result", result is DecryptResult.Success)
        result as DecryptResult.Success
        assertEquals(1, result.files.size)
        assertEquals("hello world", result.files.first().readText())
    }

    @Test
    fun `decrypt with wrong password returns WrongPassword`() {
        val zip = makeEncryptedZip(name = "secret.zip", body = "hello world", password = "opensesame")
        val out = File(work, "out-wrong")

        val result = runBlocking { ZipDecryptor.decrypt(src = zip, outputDir = out, password = "nope") }

        assertEquals(DecryptResult.WrongPassword, result)
    }

    @Test
    fun `decrypt with empty password returns WrongPassword short-circuit`() {
        val zip = makeEncryptedZip(name = "secret.zip", body = "hello world", password = "opensesame")
        val out = File(work, "out-empty")

        val result = runBlocking { ZipDecryptor.decrypt(src = zip, outputDir = out, password = "") }

        assertEquals(DecryptResult.WrongPassword, result)
    }

    @Test
    fun `decrypt missing source returns Failure`() {
        val phantom = File(work, "missing.zip")
        val out = File(work, "out-missing")

        val result = runBlocking { ZipDecryptor.decrypt(src = phantom, outputDir = out, password = "any") }

        assertTrue("expected Failure but was $result", result is DecryptResult.Failure)
    }

    // ---- 辅助：构造一份带密码保护的 zip（AES-256，zip4j 默认推荐）----

    private fun makeEncryptedZip(name: String, body: String, password: String): File {
        val zipFile = File(work, name)
        val payload = File(work, "src-$name.txt").apply { writeText(body) }
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        }
        ZipFile(zipFile, password.toCharArray()).addFile(payload, params)
        return zipFile
    }
}
