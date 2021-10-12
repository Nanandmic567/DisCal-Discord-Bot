package org.dreamexposure.discal.core.extensions.discord4j

import discord4j.core.`object`.entity.Message
import discord4j.core.event.domain.interaction.InteractionCreateEvent
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.InteractionFollowupCreateSpec
import reactor.core.publisher.Mono

fun InteractionCreateEvent.followup(embed: EmbedCreateSpec): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .addEmbed(embed)
        .build()

    return this.createFollowup(spec)
}

fun InteractionCreateEvent.followup(message: String): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .content(message)
        .build()

    return this.createFollowup(spec)
}

fun InteractionCreateEvent.followup(message: String, embed: EmbedCreateSpec): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .content(message)
        .addEmbed(embed)
        .build()

    return this.createFollowup(spec)
}

fun InteractionCreateEvent.followupEphemeral(embed: EmbedCreateSpec): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .addEmbed(embed)
        .ephemeral(true)
        .build()

    return this.createFollowup(spec)
}

fun InteractionCreateEvent.followupEphemeral(message: String): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .content(message)
        .ephemeral(true)
        .build()

    return this.createFollowup(spec)
}

fun InteractionCreateEvent.followupEphemeral(message: String, embed: EmbedCreateSpec): Mono<Message> {
    val spec = InteractionFollowupCreateSpec.builder()
        .content(message)
        .addEmbed(embed)
        .ephemeral(true)
        .build()

    return this.createFollowup(spec)
}