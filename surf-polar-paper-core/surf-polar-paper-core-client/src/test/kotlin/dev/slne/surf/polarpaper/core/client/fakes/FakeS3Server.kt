package dev.slne.surf.polarpaper.core.client.fakes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal path-style S3 endpoint for a single bucket: HEAD bucket, PUT/GET/DELETE object and paginated ListObjectsV2.
 */
class FakeS3Server(
    private val bucket: String,
    private val pageSize: Int = 2
) : AutoCloseable {
    val objects = ConcurrentHashMap<String, ByteArray>()
    val contentTypes = ConcurrentHashMap<String, String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use { handle(it) }
        }
        start()
    }

    val endpoint get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        val bucketName = path.substringBefore('/')
        val key = path.substringAfter('/', missingDelimiterValue = "")

        if (bucketName != bucket) {
            return exchange.respond(404, error("NoSuchBucket", "The specified bucket does not exist"))
        }

        when {
            exchange.requestMethod == "HEAD" && key.isEmpty() -> exchange.respond(200)
            exchange.requestMethod == "GET" && key.isEmpty() -> exchange.respond(200, listObjects(exchange))
            exchange.requestMethod == "PUT" -> {
                objects[key] = readBody(exchange)
                exchange.requestHeaders.getFirst("Content-Type")?.let { contentTypes[key] = it }
                exchange.responseHeaders.add("ETag", "\"etag\"")
                exchange.respond(200)
            }

            exchange.requestMethod == "GET" -> {
                val bytes = objects[key]
                    ?: return exchange.respond(404, error("NoSuchKey", "The specified key does not exist."))
                exchange.respond(200, bytes)
            }

            exchange.requestMethod == "DELETE" -> {
                objects.remove(key)
                exchange.respond(204)
            }

            else -> exchange.respond(405)
        }
    }

    private fun listObjects(exchange: HttpExchange): ByteArray {
        val query = (exchange.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val prefix = query["prefix"] ?: ""
        val start = query["continuation-token"]?.toInt() ?: 0
        val keys = objects.keys.filter { it.startsWith(prefix) }.sorted()
        val page = keys.drop(start).take(pageSize)
        val nextStart = start + page.size
        val truncated = nextStart < keys.size

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""")
            append("<Name>$bucket</Name><Prefix>$prefix</Prefix><KeyCount>${page.size}</KeyCount>")
            append("<MaxKeys>$pageSize</MaxKeys><IsTruncated>$truncated</IsTruncated>")
            page.forEach { append("<Contents><Key>$it</Key><Size>${objects[it]?.size ?: 0}</Size></Contents>") }
            if (truncated) append("<NextContinuationToken>$nextStart</NextContinuationToken>")
            append("</ListBucketResult>")
        }.toByteArray()
    }

    private fun readBody(exchange: HttpExchange): ByteArray {
        val body = exchange.requestBody.readBytes()
        val streaming = exchange.requestHeaders.getFirst("x-amz-content-sha256")?.startsWith("STREAMING-") == true ||
                exchange.requestHeaders.getFirst("Content-Encoding")?.contains("aws-chunked") == true

        return if (streaming) decodeAwsChunked(body) else body
    }

    /** Decodes `size[;extensions]\r\n data \r\n ... 0\r\n trailers` bodies. */
    private fun decodeAwsChunked(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var position = 0

        while (true) {
            val lineEnd = indexOfCrlf(body, position)
            val size = String(body, position, lineEnd - position).substringBefore(';').trim().toInt(16)
            position = lineEnd + 2

            if (size == 0) return output.toByteArray()

            output.write(body, position, size)
            position += size + 2
        }
    }

    private fun indexOfCrlf(bytes: ByteArray, from: Int): Int {
        for (index in from until bytes.size - 1) {
            if (bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) return index
        }
        error("Malformed aws-chunked body")
    }

    private fun error(code: String, message: String) =
        """<?xml version="1.0" encoding="UTF-8"?><Error><Code>$code</Code><Message>$message</Message></Error>""".toByteArray()

    private fun HttpExchange.respond(status: Int, body: ByteArray? = null) {
        if (body == null || requestMethod == "HEAD") {
            sendResponseHeaders(status, -1)
        } else {
            responseHeaders.add("Content-Type", "application/xml")
            sendResponseHeaders(status, body.size.toLong())
            responseBody.write(body)
        }
    }

    override fun close() {
        server.stop(0)
    }
}
