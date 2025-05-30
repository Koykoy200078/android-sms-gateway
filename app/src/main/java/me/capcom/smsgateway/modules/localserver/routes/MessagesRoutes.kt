package me.capcom.smsgateway.modules.localserver.routes

import android.content.Context
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.localserver.domain.PostMessageRequest
import me.capcom.smsgateway.modules.localserver.domain.PostMessageResponse
import me.capcom.smsgateway.modules.localserver.domain.PostMessagesInboxExportRequest
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.data.Message
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.modules.receiver.ReceiverService
import java.util.Date

class MessagesRoutes(
    private val context: Context,
    private val messagesService: MessagesService,
    private val receiverService: ReceiverService,
) {
    fun register(routing: Route) {
        routing.apply {
            messagesRoutes()
            route("/inbox") {
                inboxRoutes(context)
            }
        }
    }

    private fun Route.messagesRoutes() {
        post {
            val request = call.receive<PostMessageRequest>()
            if (request.message.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "message is empty")
                )
            }
            if (request.phoneNumbers.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "phoneNumbers is empty")
                )
            }
            if (request.simNumber != null && request.simNumber < 1) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "simNumber must be >= 1")
                )
            }
            val skipPhoneValidation =
                call.request.queryParameters["skipPhoneValidation"]
                    ?.toBooleanStrict() ?: false

            val sendRequest = SendRequest(
                EntitySource.Local,
                Message(
                    request.id ?: NanoIdUtils.randomNanoId(),
                    request.message,
                    request.phoneNumbers,
                    request.isEncrypted ?: false,
                    Date(),
                ),
                SendParams(
                    request.withDeliveryReport ?: true,
                    skipPhoneValidation = skipPhoneValidation,
                    simNumber = request.simNumber,
                    validUntil = request.validUntil,
                    priority = request.priority,
                )
            )
            messagesService.enqueueMessage(sendRequest)

            val messageId = sendRequest.message.id

            call.respond(
                HttpStatusCode.Accepted,
                PostMessageResponse(
                    id = messageId,
                    state = ProcessingState.Pending,
                    recipients = request.phoneNumbers.map {
                        PostMessageResponse.Recipient(
                            it,
                            ProcessingState.Pending,
                            null
                        )
                    },
                    isEncrypted = request.isEncrypted ?: false,
                    mapOf(ProcessingState.Pending to Date())
                )
            )
        }
        get("{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val message = try {
                messagesService.getMessage(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
            } catch (e: Throwable) {
                return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to e.message)
                )
            }

            call.respond(
                PostMessageResponse(
                    message.message.id,
                    message.message.state,
                    message.recipients.map {
                        PostMessageResponse.Recipient(
                            it.phoneNumber,
                            it.state,
                            it.error
                        )
                    },
                    message.message.isEncrypted,
                    message.states.associate {
                        it.state to Date(it.updatedAt)
                    }
                )
            )
        }
    }

    private fun Route.inboxRoutes(context: Context) {
        post("export") {
            val request = call.receive<PostMessagesInboxExportRequest>().validate()
            try {
                receiverService.export(context, request.period)
                call.respond(HttpStatusCode.Accepted)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Failed to export inbox: ${e.message}")
                )
            }
        }
    }
}