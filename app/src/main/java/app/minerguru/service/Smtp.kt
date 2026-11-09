package app.minerguru.service

import android.util.Base64
import app.minerguru.R
import app.minerguru.model.tr
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class SmtpSecurity(val title: String, val defaultPort: Int) {
    /** Plain connection upgraded with STARTTLS, usually port 587. */
    START_TLS("STARTTLS", 587),
    /** TLS from the first byte, usually port 465. */
    TLS("SSL/TLS", 465),
    /** No encryption, only for a relay in the own network. */
    NONE("-", 25)
}

class SmtpException(message: String) : IOException(message)

/** A plain-text mail, encoded for the DATA phase. [base64] is passed in so unit tests run without Android. */
data class SmtpMessage(
    val from: String,
    val to: List<String>,
    val subject: String,
    val body: String,
    val date: Date = Date(),
    val messageId: String = UUID.randomUUID().toString()
) {
    /** Headers and a base64 body, lines ending in CRLF, ready to go between DATA and the final dot. */
    fun encoded(base64: (ByteArray) -> String): String {
        val domain = from.substringAfterLast('@', "minerguru.local")
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val subjectLine = if (subject.all { it.code in 32..126 }) subject else "=?UTF-8?B?${base64(subject.toByteArray())}?="
        return listOf(
            "From: $from", "To: ${to.joinToString(", ")}", "Subject: $subjectLine", "Date: ${format.format(date)}",
            "Message-ID: <$messageId@$domain>", "MIME-Version: 1.0", "Content-Type: text/plain; charset=utf-8",
            "Content-Transfer-Encoding: base64", "", base64(body.toByteArray()).chunked(76).joinToString("\r\n")
        ).joinToString("\r\n")
    }
}

/** The SMTP dialogue without any networking: feed it the server's replies, it says what to send next. */
class SmtpDialogue(
    private val message: SmtpMessage,
    private val username: String,
    private val password: String,
    private val useStartTls: Boolean,
    private val base64: (ByteArray) -> String,
    private val clientName: String = "minerguru.local"
) {
    sealed interface Action {
        data class Send(val line: String) : Action
        data object StartTls : Action
        data object Done : Action
    }

    private enum class Step { GREETING, EHLO, START_TLS, EHLO_AFTER_TLS, AUTH, USER, PASSWORD, MAIL_FROM, RCPT_TO, DATA, BODY, QUIT }

    private var step = Step.GREETING
    private var recipient = 0

    val ehlo: String get() = "EHLO $clientName"

    /** Takes the code and text of a reply, returns the next thing to do. */
    fun next(code: Int, text: String): Action = when (step) {
        Step.GREETING -> {
            expect(code, 220, text)
            step = Step.EHLO
            Action.Send(ehlo)
        }
        Step.EHLO -> {
            expect(code, 250, text)
            if (useStartTls) {
                if ("STARTTLS" !in text.uppercase()) throw SmtpException(tr(R.string.the_mail_server_does_not))
                step = Step.START_TLS
                Action.Send("STARTTLS")
            } else authenticate()
        }
        Step.START_TLS -> {
            expect(code, 220, text)
            step = Step.EHLO_AFTER_TLS
            Action.StartTls
        }
        Step.EHLO_AFTER_TLS -> {
            expect(code, 250, text)
            authenticate()
        }
        Step.AUTH -> {
            expect(code, 334, text)
            step = Step.USER
            Action.Send(base64(username.toByteArray()))
        }
        Step.USER -> {
            expect(code, 334, text)
            step = Step.PASSWORD
            Action.Send(base64(password.toByteArray()))
        }
        Step.PASSWORD -> {
            expect(code, 235, text)
            step = Step.MAIL_FROM
            Action.Send("MAIL FROM:<${message.from}>")
        }
        Step.MAIL_FROM -> {
            expect(code, 250, text)
            step = Step.RCPT_TO
            Action.Send("RCPT TO:<${message.to[recipient++]}>")
        }
        Step.RCPT_TO -> {
            expect(code, 250, text)
            if (recipient < message.to.size) Action.Send("RCPT TO:<${message.to[recipient++]}>")
            else {
                step = Step.DATA
                Action.Send("DATA")
            }
        }
        Step.DATA -> {
            expect(code, 354, text)
            step = Step.BODY
            Action.Send(dotStuffed(message.encoded(base64)) + "\r\n.")
        }
        Step.BODY -> {
            expect(code, 250, text)
            step = Step.QUIT
            Action.Send("QUIT")
        }
        Step.QUIT -> Action.Done
    }

    private fun authenticate(): Action {
        if (username.isEmpty()) {
            step = Step.MAIL_FROM
            return Action.Send("MAIL FROM:<${message.from}>")
        }
        step = Step.AUTH
        return Action.Send("AUTH LOGIN")
    }

    private fun expect(code: Int, wanted: Int, text: String) {
        if (code != wanted) throw SmtpException(tr(R.string.mail_server, text))
    }

    companion object {
        /** Lines that start with a dot get a second one, so they do not end the message. */
        fun dotStuffed(text: String): String = text.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
    }
}

data class SmtpServer(val host: String, val port: Int, val security: SmtpSecurity, val username: String, val password: String)

/** Blocking SMTP over sockets; call it off the main thread. */
object SmtpClient {
    private const val TIMEOUT_MS = 20_000
    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun send(message: SmtpMessage, server: SmtpServer) {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        var socket: Socket = Socket().apply {
            soTimeout = TIMEOUT_MS
            connect(InetSocketAddress(server.host, server.port), TIMEOUT_MS)
        }
        try {
            if (server.security == SmtpSecurity.TLS) socket = upgrade(factory, socket, server.host)
            val dialogue = SmtpDialogue(message, server.username, server.password, server.security == SmtpSecurity.START_TLS, ::base64)
            var reader = socket.reader()
            var output = socket.getOutputStream()
            while (true) {
                val (code, text) = readReply(reader)
                when (val action = dialogue.next(code, text)) {
                    is SmtpDialogue.Action.Send -> output.line(action.line)
                    SmtpDialogue.Action.StartTls -> {
                        socket = upgrade(factory, socket, server.host)
                        reader = socket.reader()
                        output = socket.getOutputStream()
                        output.line(dialogue.ehlo)
                    }
                    SmtpDialogue.Action.Done -> return
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun Socket.reader() = BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

    private fun OutputStream.line(text: String) {
        write((text + "\r\n").toByteArray())
        flush()
    }

    private fun upgrade(factory: SSLSocketFactory, socket: Socket, host: String): Socket =
        (factory.createSocket(socket, host, socket.port, true) as SSLSocket).apply {
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }

    /** Reads one reply; lines with `-` after the code continue it. */
    private fun readReply(reader: BufferedReader): Pair<Int, String> {
        val text = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: throw SmtpException(tr(R.string.the_mail_server_closed_the))
            text.appendLine(line)
            val code = line.take(3).toIntOrNull() ?: throw SmtpException(tr(R.string.unexpected_reply_from_the_mail))
            if (line.length == 3 || line[3] != '-') return code to text.toString().trim()
        }
    }
}
