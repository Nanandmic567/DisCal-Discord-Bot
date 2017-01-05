package com.cloudcraftgaming.internal.calendar.event;

import com.cloudcraftgaming.database.DatabaseManager;
import com.cloudcraftgaming.internal.calendar.CalendarAuth;
import com.google.api.services.calendar.model.Event;
import sx.blah.discord.handle.impl.events.MessageReceivedEvent;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by Nova Fox on 1/3/2017.
 * Website: www.cloudcraftgaming.com
 * For Project: DisCal
 */
public class EventCreator {
    private static EventCreator instance;

    private ArrayList<PreEvent> events = new ArrayList<>();

    private EventCreator() {}

    public static EventCreator getCreator() {
        if (instance == null) {
            instance = new EventCreator();
        }
        return instance;
    }

    //Functionals
    public PreEvent init(MessageReceivedEvent e, String eventName) {
        if (!hasPreEvent(e.getMessage().getGuild().getID())) {
            PreEvent event = new PreEvent(e.getMessage().getGuild().getID(), eventName);
            events.add(event);
            return event;
        }
        return getPreEvent(e.getMessage().getGuild().getID());
    }

    public Boolean terminate(MessageReceivedEvent e) {
        if (hasPreEvent(e.getMessage().getGuild().getID())) {
            events.remove(getPreEvent(e.getMessage().getGuild().getID()));
            return true;
        }
        return false;
    }

    public EventCreatorResponse confirmEvent(MessageReceivedEvent e) {
        if (hasPreEvent(e.getMessage().getGuild().getID())) {
            String guildId = e.getMessage().getGuild().getID();
            PreEvent preEvent = getPreEvent(guildId);
            if (preEvent.hasRequiredValues()) {
                Event event = new Event();
                event.setSummary(preEvent.getSummery());
                event.setDescription(preEvent.getDescription());
                event.setStart(preEvent.getStartDateTime());
                event.setEnd(preEvent.getEndDateTime());

                String calendarId = DatabaseManager.getManager().getData(guildId).getCalendarAddress();
                try {
                   Event confirmed = CalendarAuth.getCalendarService().events().insert(calendarId, event).execute();
                    terminate(e);
                    return new EventCreatorResponse(true, confirmed);
                } catch (IOException ex) {
                    return new EventCreatorResponse(false);
                }
            }
        }
        return new EventCreatorResponse(false);
    }

    //Getters
    public PreEvent getPreEvent(String guildId) {
        for (PreEvent e : events) {
            if (e.getGuildId().equals(guildId)) {
                return e;
            }
        }
        return null;
    }

    //Booleans/Checkers
    public Boolean hasPreEvent(String guildId) {
        for (PreEvent e : events) {
            if (e.getGuildId().equals(guildId)) {
                return true;
            }
        }
        return false;
    }
}