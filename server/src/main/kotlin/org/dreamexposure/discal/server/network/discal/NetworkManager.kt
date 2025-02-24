package org.dreamexposure.discal.server.network.discal

import org.dreamexposure.discal.Application
import org.dreamexposure.discal.core.database.DatabaseManager
import org.dreamexposure.discal.core.logger.LOGGER
import org.dreamexposure.discal.core.`object`.BotSettings
import org.dreamexposure.discal.core.`object`.network.discal.BotInstanceData
import org.dreamexposure.discal.core.`object`.network.discal.InstanceData
import org.dreamexposure.discal.core.`object`.network.discal.NetworkData
import org.dreamexposure.discal.core.utils.GlobalVal.STATUS
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.function.TupleUtils
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class NetworkManager : ApplicationRunner {
    private val status: NetworkData = NetworkData(apiStatus = InstanceData())

    fun getStatus() = status.copy()

    fun handleCam(data: InstanceData) {
        val existing = status.camStatus.find { it.instanceId == data.instanceId }
        if (existing == null)
            LOGGER.info(STATUS, "CAM instance connected to network | Id: ${data.instanceId}")

        status.camStatus.remove(existing)
        status.camStatus.add(data)
    }

    fun handleWebsite(data: InstanceData) {
        val existing = status.websiteStatus
        if (existing == null)
            LOGGER.info(STATUS, "Website now connected")
        else if (existing.instanceId != data.instanceId)
            LOGGER.info(STATUS, "Website instance ID changed")

        status.websiteStatus = data
    }

    fun handleBot(data: BotInstanceData) {
        val existing = status.botStatus.find { it.shardIndex == data.shardIndex }
        if (existing == null)
            LOGGER.info(STATUS, "Shard connected to network | Index ${data.shardIndex}")
        else if (existing.instanceData.instanceId != data.instanceData.instanceId)
            LOGGER.info(STATUS, "Shard instance ID changed | Index ${data.shardIndex}")

        status.botStatus.remove(existing)
        status.botStatus.add(data)
        status.botStatus.sortWith(Comparator.comparingInt(BotInstanceData::shardIndex))
    }

    private fun updateAndReturnStatus(): Mono<NetworkData> {
        return Mono.zip(DatabaseManager.getCalendarCount(), DatabaseManager.getAnnouncementCount()).map(
            TupleUtils.function { calCount, annCount ->
                status.totalCalendars = calCount
                status.totalAnnouncements = annCount
                status.apiStatus = status.apiStatus.copy(lastHeartbeat = Instant.now(), uptime = Application.getUptime())

                status.copy()
            }
        )
    }

    private fun doRestartBot(bot: BotInstanceData): Mono<Void> {
        //Gotta actually see if it needs to be restarted


        if (!BotSettings.USE_RESTART_SERVICE.get().equals("true", true)) {
            status.botStatus.removeIf { it.shardIndex == bot.shardIndex }
            LOGGER.warn(STATUS, "Client disconnected from network | Index: ${bot.shardIndex} | Reason: Restart service not active!")
        } else {
            //TODO: Actually support restarting clients automatically one day
        }
        return Mono.empty()
    }

    private fun doRestartCam(cam: InstanceData): Mono<Void> {
        //Gotta actually see if it needs to be restarted


        if (!BotSettings.USE_RESTART_SERVICE.get().equals("true", true)) {
            status.camStatus.removeIf { it.instanceId == cam.instanceId }
            LOGGER.warn(STATUS, "Cam disconnected from network | Id: ${cam.instanceId} | Reason: Restart service not active!")
        } else {
            //TODO: Actually support restarting clients automatically one day
        }
        return Mono.empty()
    }

    override fun run(args: ApplicationArguments?) {
        Flux.interval(Duration.ofMinutes(1))
            .flatMap { updateAndReturnStatus() } //Update local status every minute
            .flatMap {
                val bot = Flux.from<BotInstanceData> { status.botStatus }
                    .filter { Instant.now().isAfter(it.instanceData.lastHeartbeat.plus(5, ChronoUnit.MINUTES)) }
                    .flatMap(this::doRestartBot)
                val cam = Flux.from<InstanceData> { status.botStatus }
                    .filter { Instant.now().isAfter(it.lastHeartbeat.plus(5, ChronoUnit.MINUTES)) }
                    .flatMap(this::doRestartCam)

                Mono.`when`(bot, cam)
            }.subscribe()
    }
}
