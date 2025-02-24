@file:Suppress("DuplicatedCode")

package org.dreamexposure.discal.core.database

import discord4j.common.util.Snowflake
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions.*
import io.r2dbc.spi.Result
import org.dreamexposure.discal.core.cache.DiscalCache
import org.dreamexposure.discal.core.enums.announcement.AnnouncementModifier
import org.dreamexposure.discal.core.enums.announcement.AnnouncementStyle
import org.dreamexposure.discal.core.enums.announcement.AnnouncementType
import org.dreamexposure.discal.core.enums.calendar.CalendarHost
import org.dreamexposure.discal.core.enums.event.EventColor.Companion.fromNameOrHexOrId
import org.dreamexposure.discal.core.enums.time.TimeFormat
import org.dreamexposure.discal.core.extensions.asStringList
import org.dreamexposure.discal.core.extensions.setFromString
import org.dreamexposure.discal.core.logger.LOGGER
import org.dreamexposure.discal.core.`object`.BotSettings
import org.dreamexposure.discal.core.`object`.GuildSettings
import org.dreamexposure.discal.core.`object`.StaticMessage
import org.dreamexposure.discal.core.`object`.WebSession
import org.dreamexposure.discal.core.`object`.announcement.Announcement
import org.dreamexposure.discal.core.`object`.calendar.CalendarData
import org.dreamexposure.discal.core.`object`.event.EventData
import org.dreamexposure.discal.core.`object`.event.RsvpData
import org.dreamexposure.discal.core.`object`.google.GoogleCredentialData
import org.dreamexposure.discal.core.`object`.web.UserAPIAccount
import org.dreamexposure.discal.core.utils.GlobalVal.DEFAULT
import org.intellij.lang.annotations.Language
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.time.Instant
import java.util.function.Function

object DatabaseManager {
    private val pool: ConnectionPool

    init {
        val factory = ConnectionFactories.get(
            builder()
                .option(DRIVER, "pool")
                .option(PROTOCOL, "mysql")
                .option(HOST, BotSettings.SQL_HOST.get())
                .option(PORT, BotSettings.SQL_PORT.get().toInt())
                .option(USER, BotSettings.SQL_USER.get())
                .option(PASSWORD, BotSettings.SQL_PASS.get())
                .option(DATABASE, BotSettings.SQL_DB.get())
                .build()
        )

        val conf = ConnectionPoolConfiguration.builder()
            .connectionFactory(factory)
            .maxLifeTime(Duration.ofHours(1))
            .build()

        pool = ConnectionPool(conf)
    }

    //FIXME: attempt to fix constant open/close of connections
    private fun <T> connect(connection: Function<Connection, Mono<T>>): Mono<T> {
        return Mono.usingWhen(pool.create(), connection::apply, Connection::close)
    }

    fun disconnectFromMySQL() = pool.dispose()

    fun updateAPIAccount(acc: UserAPIAccount): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_API_KEY)
                    .bind(0, acc.APIKey)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """ UPDATE ${Tables.API} SET
                                USER_ID = ?, BLOCKED = ?
                                WHERE API_KEY = ?
                                """.trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, acc.userId)
                            .bind(1, acc.blocked)
                            .bind(2, acc.APIKey)
                            .execute()
                    ).flatMap { res -> Mono.from(res.rowsUpdated) }
                        .thenReturn(true)
                } else {
                    val insertCommand = """INSERT INTO ${Tables.API}
                                (USER_ID, API_KEY, BLOCKED, TIME_ISSUED)
                                VALUES (?, ?, ?, ?)
                            """.trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, acc.userId)
                            .bind(1, acc.APIKey)
                            .bind(2, acc.blocked)
                            .bind(3, acc.timeIssued)
                            .execute()
                    ).flatMap { res -> Mono.from(res.rowsUpdated) }
                        .thenReturn(true)
                }
            }.doOnError {
                LOGGER.error(DEFAULT, "Failed to update API account", it)
            }.onErrorResume { Mono.just(false) }
        }
    }

    fun updateSettings(settings: GuildSettings): Mono<Boolean> {
        DiscalCache.guildSettings[settings.guildID] = settings

        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_GUILD_SETTINGS)
                    .bind(0, settings.guildID.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.GUILD_SETTINGS} SET
                                CONTROL_ROLE = ?, ANNOUNCEMENT_STYLE = ?, TIME_FORMAT = ?,
                                LANG = ?, PREFIX = ?, PATRON_GUILD = ?, DEV_GUILD = ?,
                                MAX_CALENDARS = ?, DM_ANNOUNCEMENTS = ?,
                                BRANDED = ? WHERE GUILD_ID = ?
                            """.trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, settings.controlRole)
                            .bind(1, settings.announcementStyle.value)
                            .bind(2, settings.timeFormat.value)
                            .bind(3, settings.lang)
                            .bind(4, settings.prefix)
                            .bind(5, settings.patronGuild)
                            .bind(6, settings.devGuild)
                            .bind(7, settings.maxCalendars)
                            .bind(8, settings.getDmAnnouncementsString())
                            .bind(9, settings.branded)
                            .bind(10, settings.guildID.asLong())
                            .execute()
                    ).flatMap { res -> Mono.from(res.rowsUpdated) }
                        .hasElement()
                        .thenReturn(true)
                } else {
                    val insertCommand = """INSERT INTO ${Tables.GUILD_SETTINGS}
                                (GUILD_ID, CONTROL_ROLE, ANNOUNCEMENT_STYLE, TIME_FORMAT, LANG, PREFIX,
                                PATRON_GUILD, DEV_GUILD, MAX_CALENDARS, DM_ANNOUNCEMENTS, BRANDED)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, settings.guildID.asLong())
                            .bind(1, settings.controlRole)
                            .bind(2, settings.announcementStyle.value)
                            .bind(3, settings.timeFormat.value)
                            .bind(4, settings.lang)
                            .bind(5, settings.prefix)
                            .bind(6, settings.patronGuild)
                            .bind(7, settings.devGuild)
                            .bind(8, settings.maxCalendars)
                            .bind(9, settings.getDmAnnouncementsString())
                            .bind(10, settings.branded)
                            .execute()
                    ).flatMap { res -> Mono.from(res.rowsUpdated) }
                        .hasElement()
                        .thenReturn(true)
                }
            }.doOnError {
                LOGGER.error(DEFAULT, "Failed to update guild settings", it)
            }.onErrorResume { Mono.just(false) }
        }
    }

    fun updateCalendar(calData: CalendarData): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_CALENDAR_BY_GUILD)
                    .bind(0, calData.guildId.asLong())
                    .bind(1, calData.calendarNumber)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.CALENDARS} SET
                        HOST = ?, CALENDAR_ID = ?,
                        CALENDAR_ADDRESS = ?, EXTERNAL = ?, CREDENTIAL_ID = ?,
                        PRIVATE_KEY = ?, ACCESS_TOKEN = ?, REFRESH_TOKEN = ?, EXPIRES_AT = ?
                        WHERE GUILD_ID = ? AND CALENDAR_NUMBER = ?
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, calData.host.name)
                            .bind(1, calData.calendarId)
                            .bind(2, calData.calendarAddress)
                            .bind(3, calData.external)
                            .bind(4, calData.credentialId)
                            .bind(5, calData.privateKey)
                            .bind(6, calData.encryptedAccessToken)
                            .bind(7, calData.encryptedRefreshToken)
                            .bind(8, calData.expiresAt.toEpochMilli())
                            .bind(9, calData.guildId.asLong())
                            .bind(10, calData.calendarNumber)
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    val insertCommand = """INSERT INTO ${Tables.CALENDARS}
                        (GUILD_ID, CALENDAR_NUMBER, HOST, CALENDAR_ID,
                        CALENDAR_ADDRESS, EXTERNAL, CREDENTIAL_ID,
                        PRIVATE_KEY, ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, calData.guildId.asLong())
                            .bind(1, calData.calendarNumber)
                            .bind(2, calData.host.name)
                            .bind(3, calData.calendarId)
                            .bind(4, calData.calendarAddress)
                            .bind(5, calData.external)
                            .bind(6, calData.credentialId)
                            .bind(7, calData.privateKey)
                            .bind(8, calData.encryptedAccessToken)
                            .bind(9, calData.encryptedRefreshToken)
                            .bind(10, calData.expiresAt.toEpochMilli())
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                }
            }.doOnError {
                LOGGER.error(DEFAULT, "Failed to update calendar data", it)
            }.onErrorResume { Mono.just(false) }
        }
    }

    fun updateAnnouncement(announcement: Announcement): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ANNOUNCEMENT_BY_GUILD)
                    .bind(0, announcement.guildId.asLong())
                    .bind(1, announcement.id)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.ANNOUNCEMENTS} SET
                        CALENDAR_NUMBER = ?, SUBSCRIBERS_ROLE = ?, SUBSCRIBERS_USER = ?, CHANNEL_ID = ?,
                        ANNOUNCEMENT_TYPE = ?, MODIFIER = ?, EVENT_ID = ?, EVENT_COLOR = ?,
                        HOURS_BEFORE = ?, MINUTES_BEFORE = ?,
                        INFO = ?, ENABLED = ?, PUBLISH = ?
                        WHERE ANNOUNCEMENT_ID = ? AND GUILD_ID = ?
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, announcement.calendarNumber)
                            .bind(1, announcement.subscriberRoleIds.asStringList())
                            .bind(2, announcement.subscriberUserIds.asStringList())
                            .bind(3, announcement.announcementChannelId)
                            .bind(4, announcement.type.name)
                            .bind(5, announcement.modifier.name)
                            .bind(6, announcement.eventId)
                            .bind(7, announcement.eventColor.name)
                            .bind(8, announcement.hoursBefore)
                            .bind(9, announcement.minutesBefore)
                            .bind(10, announcement.info)
                            .bind(11, announcement.enabled)
                            .bind(12, announcement.publish)
                            .bind(13, announcement.id)
                            .bind(14, announcement.guildId.asLong())
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    val insertCommand = """INSERT INTO ${Tables.ANNOUNCEMENTS}
                        (ANNOUNCEMENT_ID, CALENDAR_NUMBER, GUILD_ID, SUBSCRIBERS_ROLE, SUBSCRIBERS_USER,
                        CHANNEL_ID, ANNOUNCEMENT_TYPE, MODIFIER, EVENT_ID, EVENT_COLOR,
                        HOURS_BEFORE, MINUTES_BEFORE, INFO, ENABLED, PUBLISH)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, announcement.id)
                            .bind(1, announcement.calendarNumber)
                            .bind(2, announcement.guildId.asLong())
                            .bind(3, announcement.subscriberRoleIds.asStringList())
                            .bind(4, announcement.subscriberUserIds.asStringList())
                            .bind(5, announcement.announcementChannelId)
                            .bind(6, announcement.type.name)
                            .bind(7, announcement.modifier.name)
                            .bind(8, announcement.eventId)
                            .bind(9, announcement.eventColor.name)
                            .bind(10, announcement.hoursBefore)
                            .bind(11, announcement.minutesBefore)
                            .bind(12, announcement.info)
                            .bind(13, announcement.enabled)
                            .bind(14, announcement.publish)
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                }
            }.doOnError {
                LOGGER.error(DEFAULT, "Failed to update announcement", it)
            }.onErrorResume { Mono.just(false) }
        }
    }

    fun updateEventData(data: EventData): Mono<Boolean> {
        val id = if (data.eventId.contains("_"))
            data.eventId.split("_")[0]
        else
            data.eventId

        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_EVENT_BY_GUILD)
                    .bind(0, data.guildId.asLong())
                    .bind(1, id)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.EVENTS} SET
                        CALENDAR_NUMBER = ?, IMAGE_LINK = ?, EVENT_END = ?
                        WHERE EVENT_ID = ? AND GUILD_ID = ?
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, data.calendarNumber)
                            .bind(1, data.imageLink)
                            .bind(2, data.eventEnd)
                            .bind(3, id)
                            .bind(4, data.guildId.asLong())
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else if (data.shouldBeSaved()) {
                    val insertCommand = """INSERT INTO ${Tables.EVENTS}
                        (GUILD_ID, EVENT_ID, CALENDAR_NUMBER, EVENT_END, IMAGE_LINK)
                        VALUES(?, ?, ?, ?, ?)
                    """.trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, data.guildId.asLong())
                            .bind(1, id)
                            .bind(2, data.calendarNumber)
                            .bind(3, data.eventEnd)
                            .bind(4, data.imageLink)
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    Mono.just(false)
                }.doOnError {
                    LOGGER.error(DEFAULT, "Failed to update event data", it)
                }.onErrorResume { Mono.just(false) }
            }
        }
    }

    fun updateRsvpData(data: RsvpData): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_RSVP_BY_GUILD)
                    .bind(0, data.guildId.asLong())
                    .bind(1, data.eventId)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.RSVP} SET
                        CALENDAR_NUMBER = ?, EVENT_END = ?, GOING_ON_TIME = ?, GOING_LATE = ?,
                        NOT_GOING = ?, UNDECIDED = ?, waitlist = ?, RSVP_LIMIT = ?, RSVP_ROLE = ?
                        WHERE EVENT_ID = ? AND GUILD_ID = ?
                    """.trimMargin()

                    Mono.just(
                        c.createStatement(updateCommand)
                            .bind(0, data.calendarNumber)
                            .bind(1, data.eventEnd)
                            .bind(2, data.goingOnTime.asStringList())
                            .bind(3, data.goingLate.asStringList())
                            .bind(4, data.notGoing.asStringList())
                            .bind(5, data.undecided.asStringList())
                            .bind(6, data.waitlist.asStringList())
                            .bind(7, data.limit)
                            //8 deal with nullable role below
                            .bind(9, data.eventId)
                            .bind(10, data.guildId.asLong())
                    ).doOnNext { statement ->
                        if (data.roleId == null)
                            statement.bindNull(8, Long::class.java)
                        else
                            statement.bind(8, data.roleId!!.asLong())
                    }.flatMap {
                        Mono.from(it.execute())
                    }.flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else if (data.shouldBeSaved()) {
                    val insertCommand = """INSERT INTO ${Tables.RSVP}
                        (GUILD_ID, EVENT_ID, CALENDAR_NUMBER, EVENT_END, GOING_ON_TIME, GOING_LATE,
                        NOT_GOING, UNDECIDED, waitlist, RSVP_LIMIT, RSVP_ROLE)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimMargin()

                    Mono.just(
                        c.createStatement(insertCommand)
                            .bind(0, data.guildId.asLong())
                            .bind(1, data.eventId)
                            .bind(2, data.calendarNumber)
                            .bind(3, data.eventEnd)
                            .bind(4, data.goingOnTime.asStringList())
                            .bind(5, data.goingLate.asStringList())
                            .bind(6, data.notGoing.asStringList())
                            .bind(7, data.undecided.asStringList())
                            .bind(8, data.waitlist.asStringList())
                            .bind(9, data.limit)
                    ).doOnNext { statement ->
                        if (data.roleId == null)
                            statement.bindNull(10, Long::class.java)
                        else
                            statement.bind(10, data.roleId!!.asLong())
                    }.flatMap {
                        Mono.from(it.execute())
                    }.flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    Mono.just(false)
                }.doOnError {
                    LOGGER.error(DEFAULT, "Failed to update rsvp data", it)
                }.onErrorResume { Mono.just(false) }
            }
        }
    }

    fun updateCredentialData(credData: GoogleCredentialData): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_CREDENTIAL_DATA)
                    .bind(0, credData.credentialNumber)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    val updateCommand = """UPDATE ${Tables.CREDS} SET
                        REFRESH_TOKEN = ?, ACCESS_TOKEN = ?, EXPIRES_AT = ?
                        WHERE CREDENTIAL_NUMBER = ?""".trimMargin()

                    Mono.from(
                        c.createStatement(updateCommand)
                            .bind(0, credData.encryptedRefreshToken)
                            .bind(1, credData.encryptedAccessToken)
                            .bind(2, credData.expiresAt.toEpochMilli())
                            .bind(3, credData.credentialNumber)
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    val insertCommand = """INSERT INTO ${Tables.CREDS}
                        |(CREDENTIAL_NUMBER, REFRESH_TOKEN, ACCESS_TOKEN, EXPIRES_AT)
                        |VALUES(?, ?, ?, ?)""".trimMargin()

                    Mono.from(
                        c.createStatement(insertCommand)
                            .bind(0, credData.credentialNumber)
                            .bind(1, credData.encryptedRefreshToken)
                            .bind(2, credData.encryptedAccessToken)
                            .bind(3, credData.expiresAt.toEpochMilli())
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                }.doOnError {
                    LOGGER.error(DEFAULT, "Failed to update credential data", it)
                }.onErrorResume { Mono.just(false) }
            }

        }
    }

    fun getAPIAccount(APIKey: String): Mono<UserAPIAccount> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_API_KEY)
                    .bind(0, APIKey)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    UserAPIAccount(
                        row["USER_ID", String::class.java]!!,
                        APIKey,
                        row["BLOCKED", Boolean::class.java]!!,
                        row["TIME_ISSUED", Long::class.java]!!
                    )
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get API-key data", it)
            }.onErrorResume { Mono.empty() }
        }
    }

    fun getSettings(guildId: Snowflake): Mono<GuildSettings> {
        if (DiscalCache.guildSettings.containsKey(guildId))
            return Mono.just(DiscalCache.guildSettings[guildId]!!)

        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_GUILD_SETTINGS)
                    .bind(0, guildId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val controlRole = row["CONTROL_ROLE", String::class.java]!!
                    val announcementStyle = AnnouncementStyle.fromValue(row["ANNOUNCEMENT_STYLE", Int::class.java]!!)
                    val timeFormat = TimeFormat.fromValue(row["TIME_FORMAT", Int::class.java]!!)
                    val lang = row["LANG", String::class.java]!!
                    val prefix = row["PREFIX", String::class.java]!!
                    val patron = row["PATRON_GUILD", Boolean::class.java]!!
                    val dev = row["DEV_GUILD", Boolean::class.java]!!
                    val maxCals = row["MAX_CALENDARS", Int::class.java]!!
                    val dmAnnouncementsString = row["DM_ANNOUNCEMENTS", String::class.java]!!
                    val branded = row["BRANDED", Boolean::class.java]!!

                    val settings = GuildSettings(
                        guildId, controlRole, announcementStyle, timeFormat,
                        lang, prefix, patron, dev, maxCals, branded
                    )

                    settings.dmAnnouncements.setFromString(dmAnnouncementsString)

                    //Store in cache...
                    DiscalCache.guildSettings[guildId] = settings

                    settings
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get guild settings", it)
            }.onErrorReturn(GuildSettings.empty(guildId)).defaultIfEmpty(GuildSettings.empty(guildId))
        }
    }

    fun getMainCalendar(guildId: Snowflake): Mono<CalendarData> = getCalendar(guildId, 1)

    fun getCalendar(guildId: Snowflake, calendarNumber: Int): Mono<CalendarData> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_CALENDAR_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .bind(1, calendarNumber)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val calId = row["CALENDAR_ID", String::class.java]!!
                    val calNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    val calAddr = row["CALENDAR_ADDRESS", String::class.java]!!
                    val host = CalendarHost.valueOf(row["HOST", String::class.java]!!)
                    val external = row["EXTERNAL", Boolean::class.java]!!
                    val credId = row["CREDENTIAL_ID", Int::class.java]!!
                    val privateKey = row["PRIVATE_KEY", String::class.java]!!
                    val accessToken = row["ACCESS_TOKEN", String::class.java]!!
                    val refreshToken = row["REFRESH_TOKEN", String::class.java]!!
                    val expiresAt = Instant.ofEpochMilli(row["EXPIRES_AT", Long::class.java]!!)

                    CalendarData(
                        guildId, calNumber, host, calId, calAddr, external,
                        credId, privateKey, accessToken, refreshToken, expiresAt
                    )
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get all guild calendars", it)
            }.onErrorResume { Mono.empty() }
        }
    }

    fun getAllCalendars(guildId: Snowflake): Mono<List<CalendarData>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_CALENDARS_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val calId = row["CALENDAR_ID", String::class.java]!!
                    val calNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    val calAddr = row["CALENDAR_ADDRESS", String::class.java]!!
                    val host = CalendarHost.valueOf(row["HOST", String::class.java]!!)
                    val external = row["EXTERNAL", Boolean::class.java]!!
                    val credId = row["CREDENTIAL_ID", Int::class.java]!!
                    val privateKey = row["PRIVATE_KEY", String::class.java]!!
                    val accessToken = row["ACCESS_TOKEN", String::class.java]!!
                    val refreshToken = row["REFRESH_TOKEN", String::class.java]!!
                    val expiresAt = Instant.ofEpochMilli(row["EXPIRES_AT", Long::class.java]!!)

                    CalendarData(
                        guildId, calNumber, host, calId, calAddr, external,
                        credId, privateKey, accessToken, refreshToken, expiresAt
                    )
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get all guild calendars", it)
            }.onErrorReturn(mutableListOf())
        }
    }

    fun getCalendarCount(): Mono<Int> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_CALENDAR_COUNT)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val calendars = row.get(0, Long::class.java)!!
                    return@map calendars.toInt()
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get calendar count", it)
            }.onErrorReturn(-1)
        }
    }

    fun getCalendarCount(guildId: Snowflake): Mono<Int> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_CALENDAR_COUNT_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val calendars = row.get(0, Long::class.java)!!
                    return@map calendars.toInt()
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get calendar count", it)
            }.onErrorReturn(-1)
        }.defaultIfEmpty(0)
    }

    fun getEventData(guildId: Snowflake, eventId: String): Mono<EventData> {
        var eventIdLookup = eventId
        if (eventId.contains("_"))
            eventIdLookup = eventId.split("_")[0]

        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_EVENT_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .bind(1, eventIdLookup)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->

                    val id = row["EVENT_ID", String::class.java]!!
                    val calNum = row["CALENDAR_NUMBER", Int::class.java]!!
                    val end = row["EVENT_END", Long::class.java]!!
                    val img = row["IMAGE_LINK", String::class.java]!!

                    EventData(guildId, id, calNum, end, img)
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get event data", it)
            }.onErrorResume {
                Mono.empty()
            }
        }.defaultIfEmpty(EventData(guildId, eventId = eventIdLookup))
    }

    fun getRsvpData(guildId: Snowflake, eventId: String): Mono<RsvpData> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_RSVP_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .bind(1, eventId)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val calNumber = row["CALENDAR_NUMBER", Int::class.java]!!

                    val data = RsvpData(guildId, eventId, calNumber)

                    data.eventEnd = row["EVENT_END", Long::class.java]!!
                    data.goingOnTime.setFromString(row["GOING_ON_TIME", String::class.java]!!)
                    data.goingLate.setFromString(row["GOING_LATE", String::class.java]!!)
                    data.notGoing.setFromString(row["NOT_GOING", String::class.java]!!)
                    data.undecided.setFromString(row["UNDECIDED", String::class.java]!!)
                    data.waitlist.setFromString(row["waitlist", String::class.java]!!)
                    data.limit = row["RSVP_LIMIT", Int::class.java]!!

                    //Handle new rsvp role
                    if (row.get("RSVP_ROLE") != null)
                        data.setRole(Snowflake.of(row["RSVP_ROLE", Long::class.java]!!))

                    data
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get rsvp data", it)
            }.onErrorResume {
                Mono.empty()
            }.defaultIfEmpty(RsvpData(guildId, eventId))
        }
    }

    fun getAnnouncement(announcementId: String, guildId: Snowflake): Mono<Announcement> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ANNOUNCEMENT_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .bind(1, announcementId)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get announcement", it)
            }.onErrorResume { Mono.empty() }
        }
    }

    fun getAnnouncements(guildId: Snowflake): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_ANNOUNCEMENTS_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get announcements for guild", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getAnnouncements(guildId: Snowflake, type: AnnouncementType): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ANNOUNCEMENTS_BY_GUILD_AND_TYPE)
                    .bind(0, guildId.asLong())
                    .bind(1, type.name)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get guild's announcements by type", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getAnnouncements(): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_ANNOUNCEMENTS)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!
                    val guildId = Snowflake.of(row["GUILD_ID", Long::class.java]!!)

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get all announcements", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getAnnouncements(type: AnnouncementType): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_ANNOUNCEMENTS_BY_TYPE)
                    .bind(0, type.name)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!
                    val guildId = Snowflake.of(row["GUILD_ID", Long::class.java]!!)

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get announcements by type", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getEnabledAnnouncements(): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_ENABLED_ANNOUNCEMENTS)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!
                    val guildId = Snowflake.of(row["GUILD_ID", Long::class.java]!!)

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get enabled announcements", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getEnabledAnnouncements(guildId: Snowflake): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ENABLED_ANNOUNCEMENTS_BY_GUILD)
                    .bind(0, guildId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get guild's enabled announcements", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getEnabledAnnouncements(announcementType: AnnouncementType): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ENABLED_ANNOUNCEMENTS_BY_TYPE)
                    .bind(0, announcementType.name)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!
                    val guildId = Snowflake.of(row["GUILD_ID", Long::class.java]!!)

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get enabled announcements by type", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getEnabledAnnouncements(guildId: Snowflake, type: AnnouncementType): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ENABLED_ANNOUNCEMENTS_BY_TYPE_GUILD)
                    .bind(0, guildId.asLong())
                    .bind(1, type.name)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.collectList().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get guild's enabled announcements by type", it)
            }.onErrorReturn(mutableListOf())
        }.defaultIfEmpty(mutableListOf())
    }

    fun getAnnouncementCount(): Mono<Int> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ALL_ANNOUNCEMENT_COUNT)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcements = row[0, Long::class.java]!!
                    return@map announcements.toInt()
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get announcement count", it)
            }.onErrorReturn(-1)
        }.defaultIfEmpty(-1)
    }

    fun getCredentialData(credNumber: Int): Mono<GoogleCredentialData> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_CREDENTIAL_DATA)
                    .bind(0, credNumber)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val refresh = row["REFRESH_TOKEN", String::class.java]!!
                    val access = row["ACCESS_TOKEN", String::class.java]!!
                    val expires = Instant.ofEpochMilli(row["EXPIRES_AT", Long::class.java]!!)

                    GoogleCredentialData(credNumber, refresh, access, expires)
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get enabled announcements by type", it)
            }.onErrorResume { Mono.empty() }
        }
    }

    fun deleteAnnouncement(announcementId: String): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_ANNOUNCEMENT)
                    .bind(0, announcementId)
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Failed to delete announcements", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(false)
    }

    fun deleteAnnouncementsForEvent(guildId: Snowflake, eventId: String): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_ANNOUNCEMENTS_FOR_EVENT)
                    .bind(0, eventId)
                    .bind(1, guildId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Failed to delete announcements for event", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(false)
    }

    fun deleteEventData(eventId: String): Mono<Boolean> {
        if (eventId.contains("_")) return Mono.empty() // Don't delete if child event of recurring parent.
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_EVENT_DATA)
                    .bind(0, eventId)
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Failed to delete event data", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(false)
    }

    fun removeRsvpRole(guildId: Snowflake, roleId: Snowflake): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.REMOVE_RSVP_ROLE)
                    .bindNull(0, Long::class.java)
                    .bind(1, guildId.asLong())
                    .bind(2, roleId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Failed update all rsvp with role for guild ", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(false)
    }

    /* Utility Deletion Methods */

    fun deleteCalendarAndRelatedData(calendarData: CalendarData): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.FULL_CALENDAR_DELETE) //Monolith 8 statement query
                    // calendar delete bindings
                    .bind(0, calendarData.guildId.asLong())
                    .bind(1, calendarData.calendarNumber)
                    // event delete bindings
                    .bind(2, calendarData.guildId.asLong())
                    .bind(3, calendarData.calendarNumber)
                    // rsvp delete bindings
                    .bind(4, calendarData.guildId.asLong())
                    .bind(5, calendarData.calendarNumber)
                    // announcement delete bindings
                    .bind(6, calendarData.guildId.asLong())
                    .bind(7, calendarData.calendarNumber)
                    // delete static message bindings
                    .bind(8, calendarData.guildId.asLong())
                    .bind(9, calendarData.calendarNumber)
                    // decrement calendar bindings
                    .bind(10, calendarData.calendarNumber)
                    .bind(11, calendarData.guildId.asLong())
                    // decrement event bindings
                    .bind(12, calendarData.calendarNumber)
                    .bind(13, calendarData.guildId.asLong())
                    // decrement rsvp bindings
                    .bind(14, calendarData.calendarNumber)
                    .bind(15, calendarData.guildId.asLong())
                    // decrement announcement bindings
                    .bind(16, calendarData.calendarNumber)
                    .bind(17, calendarData.guildId.asLong())
                    // decrement static message bindings
                    .bind(18, calendarData.calendarNumber)
                    .bind(19, calendarData.guildId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Full calendar delete failed!", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(true) // If nothing was updated and no error was emitted, it's safe to return this worked.
    }

    fun deleteAllDataForGuild(guildId: Snowflake): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_EVERYTHING_FOR_GUILD) //Monolith 6 statement query
                    // settings delete bindings
                    .bind(0, guildId.asLong())
                    // calendar delete bindings
                    .bind(1, guildId.asLong())
                    // event delete bindings
                    .bind(2, guildId.asLong())
                    // rsvp delete bindings
                    .bind(3, guildId.asLong())
                    // announcement delete bindings
                    .bind(4, guildId.asLong())
                    // static message delete bindings
                    .bind(5, guildId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Full data delete failed!", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(true) // If nothing was updated and no error was emitted, it's safe to return this worked.
    }

    /* Static message */

    fun updateStaticMessage(message: StaticMessage): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_STATIC_MESSAGE)
                    .bind(0, message.guildId.asLong())
                    .bind(1, message.messageId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ -> row }
            }.hasElements().flatMap { exists ->
                if (exists) {
                    Mono.from(
                        c.createStatement(Queries.UPDATE_STATIC_MESSAGE)
                            .bind(0, message.lastUpdate)
                            .bind(1, message.scheduledUpdate)
                            .bind(2, message.guildId.asLong())
                            .bind(3, message.messageId.asLong())
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                } else {
                    Mono.from(
                        c.createStatement(Queries.INSERT_STATIC_MESSAGE)
                            .bind(0, message.guildId.asLong())
                            .bind(1, message.messageId.asLong())
                            .bind(2, message.channelId.asLong())
                            .bind(3, message.type.value)
                            .bind(4, message.lastUpdate)
                            .bind(5, message.scheduledUpdate)
                            .bind(6, message.calendarNumber)
                            .execute()
                    ).flatMapMany(Result::getRowsUpdated)
                        .hasElements()
                        .thenReturn(true)
                }.doOnError {
                    LOGGER.error(DEFAULT, "Failed to update static message data", it)
                }.onErrorResume { Mono.just(false) }
            }
        }
    }

    fun getStaticMessage(guildId: Snowflake, messageId: Snowflake): Mono<StaticMessage> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_STATIC_MESSAGE)
                    .bind(0, guildId.asLong())
                    .bind(1, messageId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val channelId = Snowflake.of(row["channel_id", Long::class.java]!!)
                    val type = StaticMessage.Type.valueOf(row["type", Int::class.java]!!)
                    val lastUpdate = row["last_update", Instant::class.java]!!
                    val scheduledUpdate = row["scheduled_update", Instant::class.java]!!
                    val calNum = row["calendar_number", Int::class.java]!!

                    StaticMessage(guildId, messageId, channelId, type, lastUpdate, scheduledUpdate, calNum)
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get static message data", it)
            }.onErrorResume {
                Mono.empty()
            }
        }
    }

    fun deleteStaticMessage(guildId: Snowflake, messageId: Snowflake): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_STATIC_MESSAGE)
                    .bind(0, guildId.asLong())
                    .bind(1, messageId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "Failed to delete static message data", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(false)
    }

    fun getStaticMessageCount(): Mono<Int> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_STATIC_MESSAGE_COUNT)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val messages = row.get(0, Long::class.java)!!
                    return@map messages.toInt()
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get static message count", it)
            }.onErrorReturn(-1)
        }
    }

    fun getStaticMessagesForShard(shardCount: Int, shardIndex: Int): Mono<List<StaticMessage>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_STATIC_MESSAGES_FOR_SHARD)
                    .bind(0, shardCount)
                    .bind(1, shardIndex)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val guildId = Snowflake.of(row["guild_id", Long::class.java]!!)
                    val messageId = Snowflake.of(row["message_id", Long::class.java]!!)
                    val channelId = Snowflake.of(row["channel_id", Long::class.java]!!)
                    val type = StaticMessage.Type.valueOf(row["type", Int::class.java]!!)
                    val lastUpdate = row["last_update", Instant::class.java]!!
                    val scheduledUpdate = row["scheduled_update", Instant::class.java]!!
                    val calNum = row["calendar_number", Int::class.java]!!

                    StaticMessage(guildId, messageId, channelId, type, lastUpdate, scheduledUpdate, calNum)
                }
            }.retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get static messages for shard", it)
            }.onErrorResume {
                Mono.empty()
            }.collectList()
        }
    }

    fun getStaticMessagesForCalendar(guildId: Snowflake, calendarNumber: Int): Mono<List<StaticMessage>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_STATIC_MESSAGES_FOR_CALENDAR)
                    .bind(0, guildId.asLong())
                    .bind(1, calendarNumber)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val messageId = Snowflake.of(row["message_id", Long::class.java]!!)
                    val channelId = Snowflake.of(row["channel_id", Long::class.java]!!)
                    val type = StaticMessage.Type.valueOf(row["type", Int::class.java]!!)
                    val lastUpdate = row["last_update", Instant::class.java]!!
                    val scheduledUpdate = row["scheduled_update", Instant::class.java]!!

                    StaticMessage(guildId, messageId, channelId, type, lastUpdate, scheduledUpdate, calendarNumber)
                }
            }.retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get static messages for calendar", it)
            }.onErrorResume {
                Mono.empty()
            }.collectList()
        }
    }

    /* Event Data */

    fun getEventsData(guildId: Snowflake, eventIds: List<String>): Mono<Map<String, EventData>> {
        // clean up IDs
        val idsToUse = mutableListOf<String>()
        eventIds.forEach {
            val id = if (it.contains("_")) it.split("_")[0] else it

            if (!idsToUse.contains(id)) idsToUse.add(id)
        }

        if (idsToUse.isEmpty()) return Mono.just(emptyMap())

        // Convert our list of IDs to sql escaped string
        val builder = StringBuilder()
        idsToUse.withIndex().forEach {
            if (it.index != idsToUse.size - 1) {
                builder.append("'${it.value}', ")
            } else {
                builder.append("'${it.value}'")
            }
        }

        return connect { c ->
            Mono.from(
                //Have to do it this way, sql injection is not possible as these IDs are not user input
                c.createStatement(Queries.SELECT_MANY_EVENT_DATA.replace("?", builder.toString()))
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val id = row["EVENT_ID", String::class.java]!!
                    val calNum = row["CALENDAR_NUMBER", Int::class.java]!!
                    val end = row["EVENT_END", Long::class.java]!!
                    val img = row["IMAGE_LINK", String::class.java]!!

                    EventData(guildId, id, calNum, end, img)
                }
            }.retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get many event data", it)
            }.onErrorResume {
                Mono.empty()
            }.collectMap {
                it.eventId
            }.defaultIfEmpty(emptyMap())
        }
    }

    /* Announcement Data */

    fun getAnnouncementsForShard(shardCount: Int, shardIndex: Int): Mono<List<Announcement>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_ANNOUNCEMENTS_FOR_SHARD)
                    .bind(0, shardCount)
                    .bind(1, shardIndex)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val announcementId = row["ANNOUNCEMENT_ID", String::class.java]!!
                    val guildId = Snowflake.of(row["GUILD_ID", Long::class.java]!!)

                    val a = Announcement(guildId, announcementId)
                    a.calendarNumber = row["CALENDAR_NUMBER", Int::class.java]!!
                    a.subscriberRoleIds.setFromString(row["SUBSCRIBERS_ROLE", String::class.java]!!)
                    a.subscriberUserIds.setFromString(row["SUBSCRIBERS_USER", String::class.java]!!)
                    a.announcementChannelId = row["CHANNEL_ID", String::class.java]!!
                    a.type = AnnouncementType.valueOf(row["ANNOUNCEMENT_TYPE", String::class.java]!!)
                    a.modifier = AnnouncementModifier.valueOf(row["MODIFIER", String::class.java]!!)
                    a.eventId = row["EVENT_ID", String::class.java]!!
                    a.eventColor = fromNameOrHexOrId(row["EVENT_COLOR", String::class.java]!!)
                    a.hoursBefore = row["HOURS_BEFORE", Int::class.java]!!
                    a.minutesBefore = row["MINUTES_BEFORE", Int::class.java]!!
                    a.info = row["INFO", String::class.java]!!
                    a.enabled = row["ENABLED", Boolean::class.java]!!
                    a.publish = row["PUBLISH", Boolean::class.java]!!

                    a
                }
            }.retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get announcements for shard", it)
            }.onErrorResume {
                Mono.empty()
            }.collectList()
        }
    }

    /* Session Data */
    fun insertSessionData(session: WebSession): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.INSERT_SESSION_DATA)
                    .bind(0, session.token)
                    .bind(1, session.user.asLong())
                    .bind(2, session.expiresAt)
                    .bind(3, session.accessToken)
                    .bind(4, session.refreshToken)
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
        }.doOnError {
            LOGGER.error(DEFAULT, "Failed to insert session data", it)
        }.onErrorResume { Mono.just(false) }
    }

    fun removeAndInsertSessionData(session: WebSession): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.REMOVE_AND_INSERT_SESSION_DATA)
                    // Remove all existing sessions for user bindings
                    .bind(0, session.user.asLong())
                    // Insert new session bindings
                    .bind(1, session.token)
                    .bind(2, session.user.asLong())
                    .bind(3, session.expiresAt)
                    .bind(4, session.accessToken)
                    .bind(5, session.refreshToken)
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
        }.doOnError {
            LOGGER.error(DEFAULT, "Failed to insert session data", it)
        }.onErrorResume { Mono.just(false) }
    }

    fun getSessionData(token: String): Mono<WebSession> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_SESSION_TOKEN)
                    .bind(0, token)
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val userId = Snowflake.of(row["user_id", Long::class.java]!!)
                    val expiresAt = row["expires_at", Instant::class.java]!!
                    val accessToken = row["access_token", String::class.java]!!
                    val refreshToken = row["refresh_token", String::class.java]!!

                    WebSession(token, userId, expiresAt, accessToken, refreshToken)
                }
            }.next().retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get session data by token", it)
            }.onErrorResume {
                Mono.empty()
            }
        }
    }

    fun getAllSessionsForUser(userId: Snowflake): Mono<List<WebSession>> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.SELECT_SESSIONS_USER)
                    .bind(0, userId.asLong())
                    .execute()
            ).flatMapMany { res ->
                res.map { row, _ ->
                    val token = row["token", String::class.java]!!
                    val expiresAt = row["expires_at", Instant::class.java]!!
                    val accessToken = row["access_token", String::class.java]!!
                    val refreshToken = row["refresh_token", String::class.java]!!

                    WebSession(token, userId, expiresAt, accessToken, refreshToken)
                }
            }.retryWhen(Retry.max(3)
                .filter(IllegalStateException::class::isInstance)
                .filter { it.message != null && it.message!!.contains("Request queue was disposed") }
            ).doOnError {
                LOGGER.error(DEFAULT, "Failed to get sessions for user", it)
            }.onErrorResume {
                Mono.empty()
            }.collectList()
        }
    }

    fun deleteSession(token: String): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_SESSION)
                    .bind(0, token)
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "session delete failure", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(true) // If nothing was updated and no error was emitted, it's safe to return this worked.
    }

    fun deleteAllSessionsForUser(userId: Snowflake): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_SESSIONS_FOR_USER)
                    .bind(0, userId.asLong())
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    LOGGER.error(DEFAULT, "delete all sessions for user failure", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(true) // If nothing was updated and no error was emitted, it's safe to return this worked.
    }

    fun deleteExpiredSessions(): Mono<Boolean> {
        return connect { c ->
            Mono.from(
                c.createStatement(Queries.DELETE_EXPIRED_SESSIONS)
                    .bind(0, Instant.now()) // Delete everything that expired before now
                    .execute()
            ).flatMapMany(Result::getRowsUpdated)
                .hasElements()
                .thenReturn(true)
                .doOnError {
                    // Technically because we have handling for expired tokens we don't need to panic if this breaks
                    LOGGER.error(DEFAULT, "Expired session delete failure", it)
                }.onErrorReturn(false)
        }.defaultIfEmpty(true) // If nothing was updated and no error was emitted, it's safe to return this worked.
    }
}

private object Queries {
    @Language("MySQL")
    val SELECT_API_KEY = """SELECT * FROM ${Tables.API}
        WHERE API_KEY = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_GUILD_SETTINGS = """SELECT * FROM ${Tables.GUILD_SETTINGS}
        WHERE GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_CALENDAR_BY_GUILD = """SELECT * FROM ${Tables.CALENDARS}
        WHERE GUILD_ID = ? AND CALENDAR_NUMBER = ?
       """.trimMargin()

    @Language("MySQL")
    val SELECT_ALL_CALENDARS_BY_GUILD = """SELECT * FROM ${Tables.CALENDARS}
        WHERE GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ALL_CALENDAR_COUNT = """SELECT COUNT(*) FROM ${Tables.CALENDARS}"""

    @Language("MySQL")
    val SELECT_CALENDAR_COUNT_BY_GUILD = """SELECT COUNT(*) FROM ${Tables.CALENDARS}
        WHERE GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_EVENT_BY_GUILD = """SELECT * FROM ${Tables.EVENTS}
        WHERE GUILD_ID = ? AND EVENT_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_RSVP_BY_GUILD = """SELECT * FROM ${Tables.RSVP}
        WHERE GUILD_ID = ? AND EVENT_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ANNOUNCEMENT_BY_GUILD = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE GUILD_ID = ? and ANNOUNCEMENT_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ALL_ANNOUNCEMENTS = """SELECT * FROM ${Tables.ANNOUNCEMENTS}"""

    @Language("MySQL")
    val SELECT_ALL_ANNOUNCEMENTS_BY_GUILD = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ANNOUNCEMENTS_BY_GUILD_AND_TYPE = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE GUILD_ID = ? AND ANNOUNCEMENT_TYPE = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ALL_ANNOUNCEMENTS_BY_TYPE = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE ANNOUNCEMENT_TYPE = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ALL_ENABLED_ANNOUNCEMENTS = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE ENABLED = 1
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ENABLED_ANNOUNCEMENTS_BY_GUILD = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE ENABLED = 1 and GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ENABLED_ANNOUNCEMENTS_BY_TYPE = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE ENABLED = 1 and ANNOUNCEMENT_TYPE = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_ENABLED_ANNOUNCEMENTS_BY_TYPE_GUILD = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE ENABLED = 1 AND GUILD_ID = ? AND ANNOUNCEMENT_TYPE = ?
        """.trimMargin()


    @Language("MySQL")
    val SELECT_ALL_ANNOUNCEMENT_COUNT = """SELECT COUNT(*) FROM ${Tables.ANNOUNCEMENTS}"""

    @Language("MySQL")
    val SELECT_CREDENTIAL_DATA = """SELECT * FROM ${Tables.CREDS}
        WHERE CREDENTIAL_NUMBER = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ANNOUNCEMENT = """DELETE FROM ${Tables.ANNOUNCEMENTS}
        WHERE ANNOUNCEMENT_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ANNOUNCEMENTS_FOR_EVENT = """DELETE FROM ${Tables.ANNOUNCEMENTS}
        WHERE EVENT_ID = ? AND GUILD_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_EVENT_DATA = """DELETE FROM ${Tables.EVENTS}
        WHERE EVENT_ID = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ALL_EVENT_DATA = """DELETE FROM ${Tables.EVENTS}
        WHERE GUILD_ID = ? AND CALENDAR_NUMBER = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ALL_ANNOUNCEMENT_DATA = """DELETE FROM ${Tables.ANNOUNCEMENTS}
        WHERE GUILD_ID = ? AND CALENDAR_NUMBER = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ALL_RSVP_DATA = """DELETE FROM ${Tables.RSVP}
        WHERE GUILD_ID = ? AND CALENDAR_NUMBER = ?
        """.trimMargin()

    @Language("MySQL")
    val REMOVE_RSVP_ROLE = """UPDATE ${Tables.RSVP}
        SET RSVP_ROLE = ?
        WHERE GUILD_ID = ? AND RSVP_ROLE = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_CALENDAR = """DELETE FROM ${Tables.CALENDARS} 
        WHERE GUILD_ID = ? AND calendar_number = ?
        """.trimMargin()

    @Language("MySQL")
    val DECREMENT_CALENDARS = """UPDATE ${Tables.CALENDARS} 
        SET calendar_number = calendar_number - 1
        WHERE calendar_number >=? AND guild_id = ?
        """.trimMargin()

    @Language("MySQL")
    val DECREMENT_ANNOUNCEMENTS = """UPDATE ${Tables.ANNOUNCEMENTS}
        SET calendar_number = calendar_number - 1
        WHERE calendar_number >=? AND guild_id = ?
        """.trimMargin()

    @Language("MySQL")
    val DECREMENT_EVENTS = """UPDATE ${Tables.EVENTS}
        SET calendar_number = calendar_number - 1
        WHERE calendar_number >=? AND guild_id = ?
        """.trimMargin()

    @Language("MySQL")
    val DECREMENT_RSVPS = """UPDATE ${Tables.RSVP}
        SET calendar_number = calendar_number - 1
        WHERE calendar_number >=? AND guild_id = ?
        """.trimMargin()

    @Language("MySQL")
    val DECREMENT_STATIC_MESSAGES = """UPDATE ${Tables.STATIC_MESSAGES}
        SET calendar_number = calendar_number - 1
        WHERE calendar_number >=? AND guild_id = ?
        """.trimMargin()

    @Language("MySQL")
    val DELETE_ALL_STATIC_MESSAGES = """DELETE FROM ${Tables.STATIC_MESSAGES}
        WHERE guild_id = ? AND calendar_number = ?
        """.trimMargin()

    @Language("MySQL")
    val FULL_CALENDAR_DELETE = """
        $DELETE_CALENDAR;$DELETE_ALL_EVENT_DATA;$DELETE_ALL_RSVP_DATA;$DELETE_ALL_ANNOUNCEMENT_DATA;$DELETE_ALL_STATIC_MESSAGES;
        $DECREMENT_CALENDARS;$DECREMENT_EVENTS;$DECREMENT_RSVPS;$DECREMENT_ANNOUNCEMENTS;$DECREMENT_STATIC_MESSAGES
    """.trimIndent()

    @Language("MySQL")
    val SELECT_STATIC_MESSAGE = """SELECT * FROM ${Tables.STATIC_MESSAGES}
        WHERE guild_id = ? AND message_id = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_STATIC_MESSAGES_FOR_SHARD = """SELECT * FROM ${Tables.STATIC_MESSAGES}
        WHERE MOD(guild_id >> 22, ?) = ?
    """.trimMargin()

    @Language("MySQL")
    val SELECT_STATIC_MESSAGES_FOR_CALENDAR = """SELECT * FROM ${Tables.STATIC_MESSAGES}
        WHERE guild_id = ? AND calendar_number = ?
    """.trimMargin()

    @Language("MySQL")
    val INSERT_STATIC_MESSAGE = """INSERT INTO ${Tables.STATIC_MESSAGES}
        (guild_id, message_id, channel_id, type, last_update, scheduled_update, calendar_number)
        VALUES(?, ?, ?, ?, ?, ?, ?)
        """.trimMargin()

    @Language("MySQL")
    val UPDATE_STATIC_MESSAGE = """UPDATE ${Tables.STATIC_MESSAGES} SET
        last_update = ?, scheduled_update = ?
        WHERE guild_id = ? AND message_id = ?
         """.trimMargin()

    @Language("MySQL")
    val DELETE_STATIC_MESSAGE = """DELETE FROM ${Tables.STATIC_MESSAGES}
        WHERE guild_id = ? AND message_id = ?
        """.trimMargin()

    @Language("MySQL")
    val SELECT_STATIC_MESSAGE_COUNT = """SELECT COUNT(*) FROM ${Tables.STATIC_MESSAGES}"""

    @Language("MySQL")
    val SELECT_MANY_EVENT_DATA = """SELECT * FROM ${Tables.EVENTS}
        WHERE event_id in (?)
    """.trimMargin()

    @Language("MySQL")
    val SELECT_ANNOUNCEMENTS_FOR_SHARD = """SELECT * FROM ${Tables.ANNOUNCEMENTS}
        WHERE MOD(guild_id >> 22, ?) = ?
    """.trimMargin()

    /* Session Data */

    @Language("MySQL")
    val INSERT_SESSION_DATA = """INSERT INTO ${Tables.SESSIONS}
        (token, user_id, expires_at, access_token, refresh_token)
        VALUES(?, ?, ?, ?, ?)
    """.trimMargin()

    @Language("MySQL")
    val SELECT_SESSION_TOKEN = """SELECT * FROM ${Tables.SESSIONS}
        WHERE token = ?
    """.trimMargin()

    @Language("MySQL")
    val SELECT_SESSIONS_USER = """SELECT * FROM ${Tables.SESSIONS}
        WHERE user_id = ?
    """.trimMargin()

    @Language("MySQL")
    val DELETE_SESSION = """DELETE FROM ${Tables.SESSIONS}
        WHERE token = ?
    """.trimMargin()

    @Language("MySQL")
    val DELETE_SESSIONS_FOR_USER = """DELETE FROM ${Tables.SESSIONS}
        WHERE user_id = ?
    """.trimMargin()

    val REMOVE_AND_INSERT_SESSION_DATA = "$DELETE_SESSIONS_FOR_USER;$INSERT_SESSION_DATA"

    @Language("MySQL")
    val DELETE_EXPIRED_SESSIONS = """DELETE FROM ${Tables.SESSIONS}
        where expires_at < ?
    """.trimMargin()

    /* Delete everything */

    @Language("MySQL")
    val DELETE_EVERYTHING_FOR_GUILD = """
        delete from ${Tables.GUILD_SETTINGS} where GUILD_ID=?;
        delete from ${Tables.CALENDARS} where GUILD_ID=?;
        delete from ${Tables.EVENTS} where GUILD_ID=?;
        delete from ${Tables.RSVP} where GUILD_ID=?;
        delete from ${Tables.ANNOUNCEMENTS} where GUILD_ID=?;
        delete from ${Tables.STATIC_MESSAGES} where guild_id=?;
    """.trimMargin()
}

private object Tables {
    /* The language annotations are there because IntelliJ is dumb and assumes this needs to be proper MySQL */

    @Language("Kotlin")
    const val API: String = "api"

    @Language("Kotlin")
    const val GUILD_SETTINGS = "guild_settings"

    @Language("Kotlin")
    const val CALENDARS = "calendars"

    @Language("Kotlin")
    const val ANNOUNCEMENTS = "announcements"

    @Language("Kotlin")
    const val EVENTS = "events"

    @Language("Kotlin")
    const val RSVP = "rsvp"

    @Language("Kotlin")
    const val CREDS = "credentials"

    @Language("Kotlin")
    const val STATIC_MESSAGES = "static_messages"

    @Language("Kotlin")
    const val SESSIONS = "sessions"
}
