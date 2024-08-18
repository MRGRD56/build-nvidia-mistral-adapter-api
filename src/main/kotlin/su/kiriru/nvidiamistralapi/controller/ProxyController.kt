package su.kiriru.nvidiamistralapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.HttpEntityContainer
import org.apache.hc.core5.http.io.entity.InputStreamEntity
import org.apache.hc.core5.net.URIBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture

import su.kiriru.nvidiamistralapi.util.removeEach
import java.util.ArrayDeque

private const val UPSTREAM_HOST = "integrate.api.nvidia.com"
private const val UPSTREAM_URI = "https://${UPSTREAM_HOST}"

@RestController
class ProxyController {
    private val httpClient: CloseableHttpClient = HttpClients.createDefault()
    private val objectMapper = ObjectMapper()

    @Async
    @RequestMapping(value = ["/**"])
    fun request(request: HttpServletRequest): CompletableFuture<ResponseEntity<StreamingResponseBody>> {
        val uri = request.requestURI
        val method = request.method.uppercase()

        // Build the upstream URI
        val upstreamUri = URIBuilder(UPSTREAM_URI).setPath(uri).setCustomQuery(request.queryString).build()

        // Create the request for the upstream server
        val upstreamRequest = HttpUriRequestBase(method, upstreamUri)

        // Copy headers from the incoming request
        request.headerNames.asIterator().forEach { headerName ->
            upstreamRequest.setHeader(headerName, request.getHeader(headerName))
        }

        upstreamRequest.setHeader("host", UPSTREAM_HOST);
        upstreamRequest.removeHeaders("content-length")

        // Copy the request body if it's a POST or PUT request
        if (method == "POST" || method == "PUT") {
            val entity = if (uri.equals("/v1/chat/completions", ignoreCase = true)) {
                adaptChatCompletionsRequest(request)
            } else {
                InputStreamEntity(
                    request.inputStream,
                    request.contentLengthLong,
                    ContentType.parse(request.contentType)
                )
            }

            (upstreamRequest as HttpEntityContainer).entity = entity
        }

        val responseFuture = CompletableFuture<ResponseEntity<StreamingResponseBody>>()

        // Execute the request to the upstream server
        val upstreamResponse = httpClient.execute(upstreamRequest)

        val responseHeaders = HttpHeaders()

//        var hasCheckedTransferEncoding = false
//        var isChunked = false

        upstreamResponse.headers.forEach { header ->
//            if (!hasCheckedTransferEncoding && header.name.equals("Transfer-Encoding", ignoreCase = true)) {
//                isChunked = header.value == "chunked"
//                hasCheckedTransferEncoding = true
//            }

            responseHeaders.add(header.name, header.value)
        }

        responseHeaders.remove("transfer-encoding")

//        if (isChunked) {
//            responseHeaders.set("Content-Length", null)
//        }

        responseFuture.complete(
            ResponseEntity
                .status(upstreamResponse.code)
                .headers(responseHeaders)
                .body(StreamingResponseBody { clientResponseBody ->
                    upstreamResponse.use { upstreamResponse ->
                        upstreamResponse.entity?.content?.transferTo(clientResponseBody)
                    }
                })
        )

        return responseFuture
    }

    private fun adaptChatCompletionsRequest(request: HttpServletRequest): HttpEntity {
        val jsonBody = objectMapper.readTree(request.inputStream) as ObjectNode

        val modelName = jsonBody["model"].asText()

        if (modelName.contains("mistral", ignoreCase = true)) {
            // apply changes: After the optional system message, conversation roles must alternate user/assistant/user/assistant/...

            val messages = jsonBody["messages"] as ArrayNode

            val adaptedMessages = ArrayNode(objectMapper.nodeFactory)
            val adaptedMessagesBuffer = ArrayDeque<ObjectNode>()

            var adaptedMessagesBufferRole: String? = null
            var lastRole = "system"

            var hasSystem = false
            var hasNormalMessages = false

            fun flushAdaptedMessagesBuffer() {
                if (adaptedMessagesBuffer.isEmpty()) return

                if (adaptedMessagesBuffer.size == 1) {
                    val messageToAdd = adaptedMessagesBuffer.removeFirst()
                    messageToAdd.put("role", adaptedMessagesBufferRole)

                    adaptedMessages.add(messageToAdd)
                    if (adaptedMessagesBufferRole == "system") hasSystem = true
                    adaptedMessagesBufferRole = null
                    return
                }

                val mergedMessage = adaptedMessagesBuffer.removeFirst().deepCopy()
                mergedMessage.put("role", adaptedMessagesBufferRole)

                val mergedMessageContent = StringBuilder(mergedMessage["content"].asText())
                adaptedMessagesBuffer.removeEach {
                    mergedMessageContent.append("\n\n")
                    mergedMessageContent.append(it["content"].asText())
                }
                mergedMessage.put("content", mergedMessageContent.toString())

                adaptedMessages.add(mergedMessage)
                if (adaptedMessagesBufferRole == "system") hasSystem = true
                adaptedMessagesBufferRole = null
            }

            messages.forEach { messageRaw ->
                val message = messageRaw as ObjectNode

                var messageRole = message["role"].asText()

//                if (messageRole == "system" && index == 0) {
////                    adaptedMessagesBuffer.add(message)
//                    hasSystem = true
//                    lastRole = messageRole
////                    adaptedMessagesBufferRole = messageRole
////                    flushAdaptedMessagesBuffer()
//                    adaptedMessages.add(message)
//
//                    return@forEachIndexed
//                }

                if ((hasSystem || hasNormalMessages) && messageRole == "system") {
                    messageRole = "user"
                }

                if (lastRole != messageRole) {
                    flushAdaptedMessagesBuffer()
                }

                if (!hasNormalMessages && messageRole == "assistant") {
                    adaptedMessages.add(
                        ObjectNode(objectMapper.nodeFactory).apply {
                            put("role", "user")
                            put("content", "[Start the conversation]")
                        }
                    )
                    lastRole = "user"
                    hasNormalMessages = true
                }

                adaptedMessagesBuffer.add(message)
                adaptedMessagesBufferRole = messageRole
                lastRole = messageRole
                if (messageRole != "system") {
                    hasNormalMessages = true
                }
            }

            flushAdaptedMessagesBuffer()

            if (lastRole != "user") {
                adaptedMessages.add(
                    ObjectNode(objectMapper.nodeFactory).apply {
                        put("role", "user")
                        put("content", "[Continue]")
                    }
                )
            }

            jsonBody.replace("messages", adaptedMessages)
        }

        val responseBytes = objectMapper.writeValueAsBytes(jsonBody)

        return InputStreamEntity(
            ByteArrayInputStream(responseBytes),
            responseBytes.size.toLong(),
            ContentType.parse(request.contentType)
        )
    }
}

//    private fun handleChatCompletions(request: HttpServletRequest, response: HttpServletResponse) {
//        val uri = URIBuilder(UPSTREAM_URI).setPath(request.requestURI).setCustomQuery(request.queryString).build()
//
//        val targetRequest = HttpPost(uri)
//
//        // Copy headers from the incoming request
//        val headerNames = request.headerNames
//        while (headerNames.hasMoreElements()) {
//            val headerName = headerNames.nextElement()
//            targetRequest.setHeader(headerName, request.getHeader(headerName))
//        }
//
//        // Parse and modify the JSON body
//        val jsonBody = objectMapper.readTree(request.inputStream)
//        val modifiedJsonBody = modifyJsonBody(jsonBody)
//
//        // Set the modified JSON body to the request
//        targetRequest.entity = StringEntity(modifiedJsonBody.toString())
//
//        // Execute the request to the target server and stream the response back to the client
//        httpClient.execute(targetRequest) { targetResponse ->
//            response.status = targetResponse.code
//            targetResponse.headers.forEach { header ->
//                response.setHeader(header.name, header.value)
//            }
//
//            targetResponse.entity?.content?.let { inputStream ->
//                inputStream.copyTo(response.outputStream)
//            }
//        }
//    }
//
//    private fun modifyJsonBody(json: JsonNode): JsonNode {
//        // Implement your logic to modify the JSON body here
//        // For example, let's add a new field:
//        (json as ObjectNode).put("newField", "newValue")
//        return json
//    }
