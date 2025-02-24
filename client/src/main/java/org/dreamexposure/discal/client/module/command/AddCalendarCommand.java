package org.dreamexposure.discal.client.module.command;

import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import org.dreamexposure.discal.client.message.Messages;
import org.dreamexposure.discal.client.network.google.GoogleExternalAuthHandler;
import org.dreamexposure.discal.core.database.DatabaseManager;
import org.dreamexposure.discal.core.enums.calendar.CalendarHost;
import org.dreamexposure.discal.core.object.GuildSettings;
import org.dreamexposure.discal.core.object.calendar.CalendarData;
import org.dreamexposure.discal.core.object.command.CommandInfo;
import org.dreamexposure.discal.core.utils.PermissionChecker;
import org.dreamexposure.discal.core.wrapper.google.CalendarWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;

/**
 * Created by Nova Fox on 6/29/2017.
 * Website: www.cloudcraftgaming.com
 * For Project: DisCal
 */
public class AddCalendarCommand implements Command {
    /**
     * Gets the command this Object is responsible for.
     *
     * @return The command this Object is responsible for.
     */
    @Override
    public String getCommand() {
        return "addCalendar";
    }

    /**
     * Gets the short aliases of the command this object is responsible for.
     * <br>
     * This will return an empty ArrayList if none are present
     *
     * @return The aliases of the command.
     */
    @Override
    public ArrayList<String> getAliases() {
        final ArrayList<String> aliases = new ArrayList<>();
        aliases.add("addcal");

        return aliases;
    }

    /**
     * Gets the info on the command (not sub command) to be used in help menus.
     *
     * @return The command info.
     */
    @Override
    public CommandInfo getCommandInfo() {
        return new CommandInfo(
            "addCalendar",
            "Starts the process of adding an external calendar",
            "!addCalendar (calendar ID)"
        );
    }

    /**
     * Issues the command this Object is responsible for.
     *
     * @param args  The command arguments.
     * @param event The event received.
     * @return {@code true} if successful, else {@code false}.
     */
    @Override
    public Mono<Void> issueCommand(final String[] args, final MessageCreateEvent event, final GuildSettings settings) {
        if (!(settings.getDevGuild() || settings.getPatronGuild())) {
            return Messages.sendMessage(Messages.getMessage("Notification.Patron", settings), event).then();
        }

        return PermissionChecker.hasManageServerRole(event).flatMap(hasManageRole -> {
            if (!hasManageRole) {
                return Messages.sendMessage(Messages.getMessage("Notification.Perm.MANAGE_SERVER", settings), event);
            }

            if (args.length == 0) {
                return DatabaseManager.INSTANCE.getMainCalendar(settings.getGuildID())
                    .hasElement()
                    .flatMap(hasCal -> {
                        if (hasCal) {
                            return Messages.sendMessage(
                                Messages.getMessage("Creator.Calendar.HasCalendar", settings), event);
                        } else {
                            return GoogleExternalAuthHandler.INSTANCE.requestCode(event, settings)
                                .then(Messages.sendMessage(
                                    Messages.getMessage("AddCalendar.Start", settings), event));
                        }
                    });
            } else if (args.length == 1) {
                return DatabaseManager.INSTANCE.getMainCalendar(settings.getGuildID())
                    .flatMap(data -> {
                        if (!"primary".equalsIgnoreCase(data.getCalendarAddress())) {
                            return Messages.sendMessage(Messages.getMessage("Creator.Calendar.HasCalendar", settings), event);
                        } else if ("N/a".equalsIgnoreCase(data.getEncryptedAccessToken())
                            && "N/a".equalsIgnoreCase(data.getEncryptedRefreshToken())) {
                            return Messages.sendMessage(Messages.getMessage("AddCalendar.Select.NotAuth", settings), event);
                        } else {
                            return CalendarWrapper.INSTANCE.getUsersExternalCalendars(data)
                                .flatMapMany(Flux::fromIterable)
                                .any(c -> !c.isDeleted() && c.getId().equals(args[0]))
                                .flatMap(valid -> {
                                    if (valid) {
                                        final CalendarData newData = new CalendarData(
                                            data.getGuildId(), 1, CalendarHost.GOOGLE, args[0], args[0], true, 0,
                                            data.getPrivateKey(), data.getEncryptedAccessToken(),
                                            data.getEncryptedRefreshToken(), data.getExpiresAt());

                                        //combine db calls and message send to be executed together async
                                        final Mono<Boolean> calInsert = DatabaseManager.INSTANCE.updateCalendar(newData);
                                        final Mono<Message> sendMsg = Messages.sendMessage(
                                            Messages.getMessage("AddCalendar.Select.Success", settings), event);

                                        return Mono.when(calInsert, sendMsg);
                                    } else {
                                        return Messages.sendMessage(Messages
                                            .getMessage("AddCalendar.Select.Failure.Invalid", settings), event);
                                    }
                                });
                        }
                    });
            } else {
                //Invalid argument count...
                return Messages.sendMessage(Messages.getMessage("AddCalendar.Specify", settings), event);
            }
        }).then();
    }
}
