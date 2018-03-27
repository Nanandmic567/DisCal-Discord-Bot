package com.cloudcraftgaming.discal.api.utils;

import com.cloudcraftgaming.discal.api.calendar.CalendarAuth;
import com.cloudcraftgaming.discal.api.database.DatabaseManager;
import com.cloudcraftgaming.discal.api.enums.event.EventColor;
import com.cloudcraftgaming.discal.api.object.GuildSettings;
import com.cloudcraftgaming.discal.api.object.event.PreEvent;
import com.cloudcraftgaming.discal.logger.Logger;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;

/**
 * Created by Nova Fox on 11/10/17.
 * Website: www.cloudcraftgaming.com
 * For Project: DisCal-Discord-Bot
 */
public class EventUtils {
	/**
	 * Deletes an event from the calendar.
	 *
	 * @param settings Guild settings
	 * @param eventId  The ID of the event to delete.
	 * @return <code>true</code> if successfully deleted, otherwise <code>false</code>.
	 */
	public static Boolean deleteEvent(GuildSettings settings, String eventId) {
		//TODO: Support multiple calendars...
		String calendarId = DatabaseManager.getManager().getMainCalendar(settings.getGuildID()).getCalendarAddress();
		try {
			Calendar service;
			if (settings.useExternalCalendar()) {
				service = CalendarAuth.getCalendarService(settings);
			} else {
				service = CalendarAuth.getCalendarService();
			}
			try {
				service.events().delete(calendarId, eventId).execute();
			} catch (Exception e) {
				//Failed to delete event...
				return false;
			}
			DatabaseManager.getManager().deleteAnnouncementsForEvent(settings.getGuildID(), eventId);
			DatabaseManager.getManager().deleteEventData(eventId);
			return true;
		} catch (Exception e) {
			System.out.println("Something weird happened when deleting an event!");
			Logger.getLogger().exception(null, "Failed to delete event.", e, EventUtils.class, true);
			e.printStackTrace();
		}
		return false;
	}

	public static boolean eventExists(GuildSettings settings, String eventId) {
		//TODO: Support multiple calendars...
		String calendarId = DatabaseManager.getManager().getMainCalendar(settings.getGuildID()).getCalendarAddress();
		try {
			Calendar service;
			if (settings.useExternalCalendar()) {
				service = CalendarAuth.getCalendarService(settings);
			} else {
				service = CalendarAuth.getCalendarService();
			}
			return service.events().get(calendarId, eventId).execute() != null;
		} catch (Exception e) {
			//Failed to check event, probably doesn't exist, safely ignore.
		}
		return false;
	}

	public static PreEvent copyEvent(long guildId, Event event) {
		PreEvent pe = new PreEvent(guildId);
		pe.setSummary(event.getSummary());
		pe.setDescription(event.getDescription());
		pe.setLocation(event.getLocation());
		if (event.getColorId() != null) {
			pe.setColor(EventColor.fromNameOrHexOrID(event.getColorId()));
		} else {
			pe.setColor(EventColor.RED);
		}
		pe.setEventData(DatabaseManager.getManager().getEventData(guildId, event.getId()));

		return pe;
	}

	public static String applyHoursToRawUserInput(String dateRaw, Integer plus) {
		//format: yyyy/MM/dd-HH:mm:ss
		String hoursS = dateRaw.substring(11, 13);
		try {
			Integer newHours = Integer.valueOf(hoursS);
			newHours = newHours + plus;

			String[] timeArray = dateRaw.split(":");

			return timeArray[0] + newHours + ":" + timeArray[1] + ":" + timeArray[2];

		} catch (NumberFormatException e) {
			Logger.getLogger().exception(null, "Failed to convert to number from: " + hoursS, e, EventUtils.class, true);
		}
		return dateRaw;
	}
}