package org.dreamexposure.discal.client.service

import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.dreamexposure.discal.client.DisCalClient
import org.dreamexposure.discal.core.`object`.BotSettings
import org.dreamexposure.discal.core.`object`.network.discal.BotInstanceData
import org.dreamexposure.discal.core.`object`.rest.HeartbeatRequest
import org.dreamexposure.discal.core.`object`.rest.HeartbeatType
import org.dreamexposure.discal.core.logger.LOGGER
import org.dreamexposure.discal.core.utils.GlobalVal
import org.dreamexposure.discal.core.utils.GlobalVal.HTTP_CLIENT
import org.dreamexposure.discal.core.utils.GlobalVal.JSON
import org.dreamexposure.discal.core.utils.GlobalVal.JSON_FORMAT
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

@Component
class HeartbeatService : ApplicationRunner {

    private fun heartbeat(): Mono<Void> {
        //TODO: Use DI for this soon
        return BotInstanceData.load(DisCalClient.client)
                .map { data ->
                    val requestBody = HeartbeatRequest(HeartbeatType.BOT, botInstanceData = data)

                    val body = JSON_FORMAT.encodeToString(requestBody).toRequestBody(JSON)

                    Request.Builder()
                            .url("${BotSettings.API_URL.get()}/v2/status/heartbeat")
                            .post(body)
                            .header("Authorization", BotSettings.BOT_API_TOKEN.get())
                            .header("Content-Type", "application/json")
                            .build()
                }.flatMap {
                    Mono.fromCallable(HTTP_CLIENT.newCall(it)::execute)
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(Response::close)
                }.doOnError {
                    LOGGER.error(GlobalVal.DEFAULT, "[Heartbeat] Failed to heartbeat", it)
                }.onErrorResume { Mono.empty() }
                .then()

    }

    override fun run(args: ApplicationArguments?) {
        Flux.interval(Duration.ofMinutes(2))
                .flatMap { heartbeat() }
                .subscribe()
    }
}
