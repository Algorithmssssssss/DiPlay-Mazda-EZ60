package com.reachkim.ez60.hudsim;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the simulated HUD service last received. The service process publishes it as JSON in
 * Settings.Global {@link #SETTINGS_KEY}, and the overlay draws it.
 */
final class HudState {
    static final String SETTINGS_KEY = "ez60_hud_sim";
    /** {@link #focus} while the car's own navigation holds the HUD (`ez60-emu hud car-nav on`). */
    static final String FOCUS_CAR = "car";
    // sendNavigateStatus values of the car's own navigation app (WT_TinnoveNavigation).
    static final int STATUS_IDLE = 1;
    static final int STATUS_NAVIGATING = 2;
    static final int STATUS_SIMULATING = 3;

    int status = STATUS_IDLE;
    int turnId;
    int distanceMeters;
    String currentRoad = "";
    String nextRoad = "";
    int remainingMeters;
    int remainingSeconds;
    /** Route progress 0-100, or -1 before the first sendNavigatePercent. */
    int percent = -1;
    /** The package that holds navigation focus, {@link #FOCUS_CAR}, or "" for nobody. */
    String focus = "";
    String lastCall = "";
    long sequence;

    boolean navigating() {
        return status == STATUS_NAVIGATING || status == STATUS_SIMULATING;
    }

    boolean appHoldsFocus() {
        return !focus.isEmpty() && !FOCUS_CAR.equals(focus);
    }

    String toJson() {
        try {
            return new JSONObject()
                    .put("status", status)
                    .put("turnId", turnId)
                    .put("distanceMeters", distanceMeters)
                    .put("currentRoad", currentRoad)
                    .put("nextRoad", nextRoad)
                    .put("remainingMeters", remainingMeters)
                    .put("remainingSeconds", remainingSeconds)
                    .put("percent", percent)
                    .put("focus", focus)
                    .put("lastCall", lastCall)
                    .put("sequence", sequence)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Null when the service has published nothing, which means it is not running. */
    static HudState fromJson(String json) {
        if (json == null || json.isEmpty() || json.equals("null")) return null;
        try {
            JSONObject object = new JSONObject(json);
            HudState state = new HudState();
            state.status = object.optInt("status", STATUS_IDLE);
            state.turnId = object.optInt("turnId");
            state.distanceMeters = object.optInt("distanceMeters");
            state.currentRoad = object.optString("currentRoad");
            state.nextRoad = object.optString("nextRoad");
            state.remainingMeters = object.optInt("remainingMeters");
            state.remainingSeconds = object.optInt("remainingSeconds");
            state.percent = object.optInt("percent", -1);
            state.focus = object.optString("focus");
            state.lastCall = object.optString("lastCall");
            state.sequence = object.optLong("sequence");
            return state;
        } catch (JSONException e) {
            return null;
        }
    }
}
