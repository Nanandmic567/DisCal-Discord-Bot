package com.cloudcraftgaming.discal.web.handler;

import com.cloudcraftgaming.discal.Main;
import com.cloudcraftgaming.discal.api.calendar.CalendarAuth;
import com.cloudcraftgaming.discal.api.database.DatabaseManager;
import com.cloudcraftgaming.discal.api.object.calendar.CalendarData;
import com.cloudcraftgaming.discal.api.object.web.WebCalendar;
import com.cloudcraftgaming.discal.api.object.web.WebChannel;
import com.cloudcraftgaming.discal.api.object.web.WebGuild;
import com.cloudcraftgaming.discal.api.object.web.WebRole;
import com.cloudcraftgaming.discal.api.utils.ExceptionHandler;
import com.cloudcraftgaming.discal.api.utils.PermissionChecker;
import com.google.api.services.calendar.model.AclRule;
import com.google.api.services.calendar.model.Calendar;
import org.json.JSONException;
import spark.Request;
import spark.Response;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.IRole;
import sx.blah.discord.handle.obj.IUser;

import java.util.HashMap;
import java.util.Map;

import static spark.Spark.halt;

/**
 * Created by Nova Fox on 12/19/17.
 * Website: www.cloudcraftgaming.com
 * For Project: DisCal-Discord-Bot
 */
@SuppressWarnings({"unchecked", "ThrowableNotThrown"})
public class DashboardHandler {
	public static String handleGuildSelect(Request request, Response response) {
		try {
			String guildId = request.queryParams("guild");

			IGuild g = Main.client.getGuildByID(Long.valueOf(guildId));
			WebGuild wg = new WebGuild().fromGuild(g);

			Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());

			IUser u = Main.client.getUserByID(Long.valueOf((String) m.get("id")));

			if (m.containsKey("selected")) {
				m.remove("selected");
			}

			m.put("selected", wg);

			if (m.containsKey("settings")) {
				m.remove("settings");
			}

			if (m.containsKey("admin")) {
				m.remove("admin");
			}

			//Check if admin/manage server and/or has control role...
			m.put("admin", PermissionChecker.hasManageServerRole(g, u));
			m.put("controller", PermissionChecker.hasSufficientRole(g, u));

			DiscordAccountHandler.getHandler().appendAccount(m, request.session().id());

			response.redirect("/dashboard/guild", 301);
		} catch (JSONException e) {
			ExceptionHandler.sendException(null, "[WEB] JSON || Guild Select failed!", e, DashboardHandler.class);
			response.redirect("/dashboard", 301);
		} catch (Exception e) {
			ExceptionHandler.sendException(null, "[WEB] Guild Select failed!", e, DashboardHandler.class);
			halt(500, "Internal Server Exception");
		}
		return response.body();
	}

	public static String handleSettingsSelect(Request request, Response response) {
		try {
			String settings = request.queryParams("settings");

			Map m = new HashMap();
			m.put("settings", settings);

			DiscordAccountHandler.getHandler().appendAccount(m, request.session().id());

			response.redirect("/dashboard/guild", 301);
		} catch (JSONException e) {
			ExceptionHandler.sendException(null, "[WEB] JSON || Settings Select failed!", e, DashboardHandler.class);
			response.redirect("/dashboard", 301);
		} catch (Exception e) {
			ExceptionHandler.sendException(null, "[WEB] Settings Selected failed!", e, DashboardHandler.class);
			halt(500, "Internal Server Exception");
		}
		return response.body();
	}

	public static String handleSettingsUpdate(Request request, Response response) {
		try {
			if (request.queryParams().contains("bot-nick")) {
				//Update bot nickname...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setBotNick(request.queryParams("bot-nick"));

				IGuild guild = Main.client.getGuildByID(Long.valueOf(g.getId()));

				guild.setUserNickname(Main.client.getOurUser(), g.getBotNick());
			} else if (request.queryParams().contains("prefix")) {
				//Update prefix...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setSettings(DatabaseManager.getManager().getSettings(Long.valueOf(g.getId())));
				g.getSettings().setPrefix(request.queryParams("prefix"));

				DatabaseManager.getManager().updateSettings(g.getSettings());
			} else if (request.queryParams().contains("lang")) {
				//Update lang...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setSettings(DatabaseManager.getManager().getSettings(Long.valueOf(g.getId())));
				g.getSettings().setLang(request.queryParams("lang"));

				DatabaseManager.getManager().updateSettings(g.getSettings());
			} else if (request.queryParams().contains("simple-ann")) {
				//Update simple announcements...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setSettings(DatabaseManager.getManager().getSettings(Long.valueOf(g.getId())));
				g.getSettings().setSimpleAnnouncements(Boolean.valueOf(request.queryParams("simple-ann")));

				DatabaseManager.getManager().updateSettings(g.getSettings());
			} else if (request.queryParams().contains("con-role")) {
				//Update control role...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setSettings(DatabaseManager.getManager().getSettings(Long.valueOf(g.getId())));

				IGuild guild = Main.client.getGuildByID(Long.valueOf(g.getId()));
				IRole role = guild.getRoleByID(Long.valueOf(request.queryParams("con-role")));

				if (role.isEveryoneRole()) {
					g.getSettings().setControlRole("everyone");
				} else {
					g.getSettings().setControlRole(role.getStringID());
				}

				DatabaseManager.getManager().updateSettings(g.getSettings());

				//Update role list to display properly...
				g.getRoles().clear();

				for (IRole r : guild.getRoles()) {
					g.getRoles().add(new WebRole().fromRole(r, g.getSettings()));
				}
			} else if (request.queryParams().contains("discal-channel")) {
				//Update control role...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				g.setSettings(DatabaseManager.getManager().getSettings(Long.valueOf(g.getId())));

				IGuild guild = Main.client.getGuildByID(Long.valueOf(g.getId()));

				if (request.queryParams("discal-channel").equalsIgnoreCase("0")) {
					//All channels
					g.getSettings().setDiscalChannel("all");
				} else {
					g.getSettings().setDiscalChannel(request.queryParams("discal-channel"));
				}

				DatabaseManager.getManager().updateSettings(g.getSettings());

				//Update channel list to display properly...
				g.getChannels().clear();

				WebChannel all = new WebChannel();
				all.setId(0);
				all.setName("All Channels");
				all.setDiscalChannel(g.getSettings().getDiscalChannel().equalsIgnoreCase("all"));
				g.getChannels().add(all);
				for (IChannel c : guild.getChannels()) {
					g.getChannels().add(new WebChannel().fromChannel(c, g.getSettings()));
				}
			} else if (request.queryParams().contains("cal-name")) {
				//Update calendar name/summary...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				try {
					if (g.getCalendar().isExternal()) {
						Calendar cal = CalendarAuth.getCalendarService(g.getSettings()).calendars().get(g.getCalendar().getId()).execute();
						cal.setSummary(request.queryParams("cal-name"));
						CalendarAuth.getCalendarService(g.getSettings()).calendars().update(g.getCalendar().getId(), cal).execute();
					} else {
						Calendar cal = CalendarAuth.getCalendarService().calendars().get(g.getCalendar().getId()).execute();
						cal.setSummary(request.queryParams("cal-name"));
						CalendarAuth.getCalendarService().calendars().update(g.getCalendar().getId(), cal).execute();
					}

					g.getCalendar().setName(request.queryParams("cal-name"));
				} catch (Exception e) {
					ExceptionHandler.sendException(null, "[WEB] Failed to update calendar name", e, DashboardHandler.class);
				}
			} else if (request.queryParams().contains("cal-desc")) {
				//Update calendar description...
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				try {
					if (g.getCalendar().isExternal()) {
						Calendar cal = CalendarAuth.getCalendarService(g.getSettings()).calendars().get(g.getCalendar().getId()).execute();
						cal.setDescription(request.queryParams("cal-desc"));
						CalendarAuth.getCalendarService(g.getSettings()).calendars().update(g.getCalendar().getId(), cal).execute();
					} else {
						Calendar cal = CalendarAuth.getCalendarService().calendars().get(g.getCalendar().getId()).execute();
						cal.setDescription(request.queryParams("cal-desc"));
						CalendarAuth.getCalendarService().calendars().update(g.getCalendar().getId(), cal).execute();
					}

					g.getCalendar().setDescription(request.queryParams("cal-desc"));
				} catch (Exception e) {
					ExceptionHandler.sendException(null, "[WEB] Failed to update calendar description", e, DashboardHandler.class);
				}
			} else if (request.queryParams().contains("cal-tz")) {
				//Update calendar timezone
				Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
				WebGuild g = (WebGuild) m.get("selected");

				try {
					if (g.getCalendar().isExternal()) {
						Calendar cal = CalendarAuth.getCalendarService(g.getSettings()).calendars().get(g.getCalendar().getId()).execute();
						cal.setTimeZone(request.queryParams("cal-tz").replace("___", "/"));
						CalendarAuth.getCalendarService(g.getSettings()).calendars().update(g.getCalendar().getId(), cal).execute();
					} else {
						Calendar cal = CalendarAuth.getCalendarService().calendars().get(g.getCalendar().getId()).execute();
						cal.setTimeZone(request.queryParams("cal-tz").replace("___", "/"));
						CalendarAuth.getCalendarService().calendars().update(g.getCalendar().getId(), cal).execute();
					}

					g.getCalendar().setTimezone(request.queryParams("cal-tz"));
				} catch (Exception e) {
					ExceptionHandler.sendException(null, "[WEB] Failed to update calendar timezone", e, DashboardHandler.class);
				}
			}

			//Finally redirect back to the dashboard
			response.redirect("/dashboard/guild", 301);
		} catch (Exception e) {
			ExceptionHandler.sendException(null, "[WEB] Settings update failed!", e, DashboardHandler.class);
			halt(500, "Internal Server Exception");
		}
		return response.body();
	}

	public static String handleCalendarCreate(Request request, Response response) {
		try {
			String name = request.queryParams("cal-name");
			String desc = request.queryParams("cal-desc");
			String tz = request.queryParams("cal-tz");

			Map m = DiscordAccountHandler.getHandler().getAccount(request.session().id());
			WebGuild g = (WebGuild) m.get("selected");

			Calendar calendar = new Calendar();
			calendar.setSummary(name);
			calendar.setDescription(desc);
			calendar.setTimeZone(tz.replace("___", "/"));
			try {
				com.google.api.services.calendar.Calendar service;
				if (g.getSettings().useExternalCalendar()) {
					service = CalendarAuth.getCalendarService(g.getSettings());
				} else {
					service = CalendarAuth.getCalendarService();
				}

				Calendar confirmed = service.calendars().insert(calendar).execute();
				AclRule rule = new AclRule();
				AclRule.Scope scope = new AclRule.Scope();
				scope.setType("default");
				rule.setScope(scope).setRole("reader");
				service.acl().insert(confirmed.getId(), rule).execute();
				CalendarData calendarData = new CalendarData(Long.valueOf(g.getId()), 1);
				calendarData.setCalendarId(confirmed.getId());
				calendarData.setCalendarAddress(confirmed.getId());
				DatabaseManager.getManager().updateCalendar(calendarData);

				//Refresh to display correct info...
				g.setCalendar(new WebCalendar().fromCalendar(calendarData, g.getSettings()));
			} catch (Exception ex) {
				ExceptionHandler.sendException(null, "[WEB] Failed to confirm calendar.", ex, DashboardHandler.class);
			}

			//Finally redirect back to the dashboard
			response.redirect("/dashboard/guild", 301);
		} catch (Exception e) {
			ExceptionHandler.sendException(null, "[WEB] Calendar create failed!", e, DashboardHandler.class);
			halt(500, "Internal Server Exception");
		}
		return response.body();
	}
}