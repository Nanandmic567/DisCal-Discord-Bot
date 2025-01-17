package org.dreamexposure.discal.server.endpoints.v3

import org.dreamexposure.discal.core.`object`.BotSettings
import org.dreamexposure.discal.core.annotations.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/v3/")
class InviteEndpoint {
    @Authentication(access = Authentication.AccessLevel.PUBLIC)
    @GetMapping("invite", produces = ["text/plain"])
    fun get(): Mono<String> {
        return Mono.just(BotSettings.INVITE_URL.get())
    }
}
