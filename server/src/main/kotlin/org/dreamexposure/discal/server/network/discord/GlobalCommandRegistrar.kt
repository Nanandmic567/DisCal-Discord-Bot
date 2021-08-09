package org.dreamexposure.discal.server.network.discord

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import discord4j.common.JacksonResources
import discord4j.discordjson.json.ApplicationCommandData
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.rest.RestClient
import org.dreamexposure.discal.core.logger.LOGGER
import org.dreamexposure.discal.core.utils.GlobalVal.DEFAULT
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

@Component
class GlobalCommandRegistrar(
        private val objectMapper: ObjectMapper,
        private val restClient: RestClient
) : ApplicationRunner {

    override fun run(args: ApplicationArguments?) {
        val d4jMapper = JacksonResources.createFromObjectMapper(objectMapper)

        val matcher = PathMatchingResourcePatternResolver()
        val applicationService = restClient.applicationService
        val applicationId = restClient.applicationId.block()!!
        val discordCommands = applicationService.getGlobalApplicationCommands(applicationId)
                .collectMap(ApplicationCommandData::name)
                .block()!!

        var added = 0
        var removed = 0
        var updated = 0

        val commands = mutableMapOf<String, ApplicationCommandRequest>()
        for (res in matcher.getResources("commands/*.json")) {
            val request = d4jMapper.objectMapper.readValue<ApplicationCommandRequest>(res.inputStream)
            commands[request.name()] = request

            if (discordCommands[request.name()] == null) {
                added++
                applicationService.createGlobalApplicationCommand(applicationId, request).block()
            }
        }

        for ((discordCommandName, discordCommand) in discordCommands) {
            val discordCommandId = discordCommand.id().toLong()
            val command = commands[discordCommandName]
            if (command == null) { // Removed command.json, delete global command
                removed++
                applicationService.deleteGlobalApplicationCommand(applicationId, discordCommandId).block()
                continue
            }

            val changed = discordCommand.description() != command.description()
                    || discordCommand.options() != command.options()
                    || discordCommand.defaultPermission() != command.defaultPermission()

            if (changed) {
                updated++
                applicationService.modifyGlobalApplicationCommand(applicationId, discordCommandId, command).block()
            }
        }

        //Send log message with details on changes...
        LOGGER.info(DEFAULT, "Slash commands: $added Added | $updated Updated | $removed Removed")
    }
}