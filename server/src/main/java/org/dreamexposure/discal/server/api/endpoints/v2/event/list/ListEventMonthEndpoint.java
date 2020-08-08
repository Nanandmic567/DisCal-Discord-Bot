package org.dreamexposure.discal.server.api.endpoints.v2.event.list;

import com.google.api.services.calendar.model.Event;

import org.dreamexposure.discal.core.database.DatabaseManager;
import org.dreamexposure.discal.core.logger.LogFeed;
import org.dreamexposure.discal.core.logger.object.LogObject;
import org.dreamexposure.discal.core.object.GuildSettings;
import org.dreamexposure.discal.core.object.web.AuthenticationState;
import org.dreamexposure.discal.core.utils.GlobalConst;
import org.dreamexposure.discal.core.utils.JsonUtils;
import org.dreamexposure.discal.core.wrapper.google.EventWrapper;
import org.dreamexposure.discal.server.utils.Authentication;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import discord4j.common.util.Snowflake;

@RestController
@RequestMapping("/v2/events/list")
public class ListEventMonthEndpoint {
    @PostMapping(value = "/month", produces = "application/json")
    public String getEventsForMonth(final HttpServletRequest request, final HttpServletResponse response, @RequestBody final String rBody) {
        //Authenticate...
        final AuthenticationState authState = Authentication.authenticate(request);
        if (!authState.isSuccess()) {
            response.setStatus(authState.getStatus());
            response.setContentType("application/json");
            return authState.toJson();
        }

        //Okay, now handle actual request.
        try {
            final JSONObject requestBody = new JSONObject(rBody);

            final Snowflake guildId = Snowflake.of(requestBody.getString("guild_id"));
            final int calNumber = requestBody.getInt("calendar_number");
            final int daysInMonth = requestBody.getInt("days_in_month");
            final long startEpoch = requestBody.getLong("epoch_start");
            final long endEpoch = startEpoch + (GlobalConst.oneDayMs * daysInMonth);
            final GuildSettings settings = DatabaseManager.getSettings(guildId).block();

            //okay, lets actually get the month's events.
            final List<Event> events = DatabaseManager.getCalendar(settings.getGuildID(), calNumber)
                .flatMap(calData -> EventWrapper.getEvents(calData, settings, startEpoch, endEpoch))
                .block();

            final List<JSONObject> jEvents = new ArrayList<>();
            for (final Event e : events) {
                jEvents.add(JsonUtils.convertEventToJson(e, settings));
            }

            final JSONObject body = new JSONObject();
            body.put("events", jEvents);
            body.put("message", "Events successfully listed.");

            response.setContentType("application/json");
            response.setStatus(GlobalConst.STATUS_SUCCESS);
            return body.toString();
        } catch (final JSONException e) {
            response.setContentType("application/json");
            response.setStatus(GlobalConst.STATUS_BAD_REQUEST);
            return JsonUtils.getJsonResponseMessage("Bad Request");
        } catch (final Exception e) {
            LogFeed.log(LogObject
                .forException("[API-v2]", "get events for month err", e, this.getClass()));

            response.setContentType("application/json");
            response.setStatus(GlobalConst.STATUS_INTERNAL_ERROR);
            return JsonUtils.getJsonResponseMessage("Internal Server Error");
        }
    }
}