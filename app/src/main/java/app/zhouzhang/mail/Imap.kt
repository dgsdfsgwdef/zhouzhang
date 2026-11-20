package app.zhouzhang.mail

import android.content.Context
import app.zhouzhang.data.SecretBox
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

data class MailAccount(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

data class MailAttachment(
    val fileName: String,
    val bytes: ByteArray,
    val subject: String,
    val receivedAt: Long,
)

object MailPresets {
    val list = listOf(
        Triple("QQ 邮箱", "imap.qq.com", 993),
        Triple("网易 163", "imap.163.com", 993),
        Triple("Gmail", "imap.gmail.com", 993),
        Triple("Outlook", "outlook.office365.com", 993),
    )
}

/**
 * 只做一件事：从用户自己的 IMAP 服务器上，把微信/支付宝发来的账单附件取回来。
 *
 * 强制 TLS 并校验服务器证书主机名，不提供明文回退。
 * 不经过任何第三方服务端——连接直连用户填写的地址。
 */
object ImapClient {

    private const val MAX_BYTES = 20 * 1024 * 1024

    private fun props(account: MailAccount) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", account.host)
        put("mail.imaps.port", account.port.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "20000")
        put("mail.imaps.writetimeout", "15000")
        put("mail.mime.decodefilename", "true")
        put("mail.mime.encodefilename", "true")
        put("mail.mime.charset", "UTF-8")
    }

    fun test(account: MailAccount) {
        val session = Session.getInstance(props(account))
        val store = session.getStore("imaps")
        try {
            store.connect(account.host, account.port, account.user, account.password)
        } finally {
            runCatching { store.close() }
        }
    }

    fun fetchBills(
        account: MailAccount,
        sinceDays: Int = 45,
        maxAttachments: Int = 12,
    ): List<MailAttachment> {
        val session = Session.getInstance(props(account))
        val store = session.getStore("imaps")
        store.connect(account.host, account.port, account.user, account.password)

        val out = ArrayList<MailAttachment>()
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val since = Date(System.currentTimeMillis() - sinceDays * 86_400_000L)
                val found = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, since))
                val ordered = found.sortedByDescending { it.receivedDate?.time ?: 0L }

                for (msg in ordered) {
                    if (out.size >= maxAttachments) break
                    val subject = msg.subject ?: ""
                    val from = (msg.from?.firstOrNull() as? InternetAddress)?.address ?: ""
                    if (!looksLikeBill(from, subject)) continue
                    collect(msg, subject, msg.receivedDate?.time ?: 0L, out, maxAttachments)
                }
            } finally {
                runCatching { inbox.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
        return out
    }

    private fun looksLikeBill(from: String, subject: String): Boolean {
        val text = "$from $subject".lowercase()
        return listOf(
            "wechat", "tenpay", "alipay", "微信", "支付宝", "账单", "交易明细"
        ).any { text.contains(it) }
    }

    private fun collect(
        part: Part,
        subject: String,
        at: Long,
        out: MutableList<MailAttachment>,
        max: Int,
    ) {
        if (out.size >= max) return

        if (part.isMimeType("multipart/*")) {
            val mp = part.content as? Multipart ?: return
            for (i in 0 until mp.count) {
                val body = runCatching { mp.getBodyPart(i) }.getOrNull() ?: continue
                collect(body, subject, at, out, max)
            }
            return
        }

        val name = runCatching { part.fileName }.getOrNull() ?: return
        val isBill = name.endsWith(".zip", true) ||
                name.endsWith(".csv", true) ||
                name.endsWith(".xlsx", true)
        if (!isBill) return

        val size = runCatching { part.size }.getOrDefault(-1)
        if (size > MAX_BYTES) return

        val bytes = runCatching { part.inputStream.use { it.readBytes() } }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return

        out.add(MailAttachment(name, bytes, subject, at))
    }
}

/** 账号配置：服务器信息明文存，授权码走 Keystore 加密 */
object MailConfig {

    private const val PREFS = "zz_mail"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"
    private const val SECRET_PASSWORD = "mail_password"

    fun save(context: Context, account: MailAccount) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, account.host)
            .putInt(KEY_PORT, account.port)
            .putString(KEY_USER, account.user)
            .apply()
        SecretBox.put(context, SECRET_PASSWORD, account.password)
    }

    fun load(context: Context): MailAccount? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(KEY_HOST, null) ?: return null
        val user = p.getString(KEY_USER, null) ?: return null
        val password = SecretBox.get(context, SECRET_PASSWORD) ?: return null
        return MailAccount(host, p.getInt(KEY_PORT, 993), user, password)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        SecretBox.remove(context, SECRET_PASSWORD)
    }
}
