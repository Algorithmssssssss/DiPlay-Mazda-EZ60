package com.reachkim.ez60.hudsim;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for the EZ-60's HUD navigation service, reached the way DiPlay's MazdaHud reaches the car's:
 * ServiceManager "com.incall.SVR_MNG_SERVICE", getServer (6) "com.incall.double.INTERACTIVE_SERVICE", then
 * IDouInteractiveManager calls. Codes and arguments are from the EZ-60 findings (EZNav and the car's own
 * navigation app).
 *
 * <p>`ez60-emu hud start` runs it as root with app_process, because Android lets only system and shell
 * processes add services. It logs each call under EZ60HudSim and publishes the result to Settings.Global
 * ez60_hud_sim for {@link HudOverlayService}.
 */
public final class FakeCarHudService {
    static final String TAG = "EZ60HudSim";
    private static final String MANAGER_NAME = "com.incall.SVR_MNG_SERVICE";
    private static final String MANAGER_INTERFACE = "com.incall.serversdk.server.ISvrManager";
    private static final int GET_SERVER = 6;
    private static final String NAVIGATION_SERVICE = "com.incall.double.INTERACTIVE_SERVICE";
    private static final String NAVIGATION_INTERFACE = "com.incall.serversdk.interactive.IDouInteractiveManager";
    private static final String FOCUS_CALLBACK_INTERFACE = "com.incall.serversdk.interactive.callback.INaviFocusCallback";
    private static final int ON_FOCUS_CHANGE = 3;
    private static final int FOCUS_LOST = 0;
    private static final int FOCUS_GAINED = 1;
    private static final int STATUS = 22; // sendNavigateStatus(int)
    private static final int TURN = 24; // sendNavigateTurnInfo(int turnId, int distanceMeters)
    private static final int ROAD = 26; // sendNavigateRoadInfo(String current, String next)
    private static final int REMAIN = 27; // sendNavigateRemainInfo(int meters, int seconds)
    private static final int PERCENT = 50; // sendNavigatePercent(int)
    private static final int REQUEST_FOCUS = 63; // requestNaviFocus(String package, INaviFocusCallback)
    private static final int ABANDON_FOCUS = 64; // abandonNaviFocus(String package, INaviFocusCallback)
    /** `ez60-emu hud car-nav on|off` writes "car" or "release" here. */
    private static final File CONTROL = new File("/data/local/tmp/ez60-hud-control");

    private static final Object lock = new Object();
    private static final HudState state = new HudState();
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    // Guarded by lock.
    private static String holderPackage;
    private static IBinder holderCallback;
    private static boolean carNavigation;
    private static boolean publishPending;

    private FakeCarHudService() {
    }

    public static void main(String[] args) throws Exception {
        IBinder manager = new Manager(new Navigation());
        Class.forName("android.os.ServiceManager").getMethod("addService", String.class, IBinder.class)
                .invoke(null, MANAGER_NAME, manager);
        log("registered " + MANAGER_NAME + "; waiting for DiPlay");
        publish();
        // Binder calls arrive on app_process's binder threads; this thread only reads the control file.
        while (true) {
            Thread.sleep(300);
            readControl();
        }
    }

    /** ISvrManager: hands out the navigation service by name. */
    private static final class Manager extends Binder {
        private final IBinder navigation;

        Manager(IBinder navigation) {
            this.navigation = navigation;
            attachInterface(null, MANAGER_INTERFACE);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != GET_SERVER) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(MANAGER_INTERFACE);
            String name = data.readString();
            boolean known = NAVIGATION_SERVICE.equals(name);
            log("getServer " + name + (known ? "" : " (not simulated)"));
            if (reply != null) {
                reply.writeNoException();
                reply.writeStrongBinder(known ? navigation : null);
            }
            return true;
        }
    }

    /** IDouInteractiveManager: the HUD's navigation calls. Other calls are logged and accepted. */
    private static final class Navigation extends Binder {
        Navigation() {
            attachInterface(null, NAVIGATION_INTERFACE);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(NAVIGATION_INTERFACE);
            String call;
            synchronized (lock) {
                switch (code) {
                    case STATUS:
                        state.status = data.readInt();
                        call = "sendNavigateStatus " + state.status;
                        break;
                    case TURN:
                        state.turnId = data.readInt();
                        state.distanceMeters = data.readInt();
                        call = "sendNavigateTurnInfo turnId=" + state.turnId + " distanceM=" + state.distanceMeters;
                        break;
                    case ROAD:
                        state.currentRoad = text(data.readString());
                        state.nextRoad = text(data.readString());
                        call = "sendNavigateRoadInfo current=\"" + state.currentRoad + "\" next=\"" + state.nextRoad + "\"";
                        break;
                    case REMAIN:
                        state.remainingMeters = data.readInt();
                        state.remainingSeconds = data.readInt();
                        call = "sendNavigateRemainInfo meters=" + state.remainingMeters + " seconds=" + state.remainingSeconds;
                        break;
                    case PERCENT:
                        state.percent = data.readInt();
                        call = "sendNavigatePercent " + state.percent;
                        break;
                    case REQUEST_FOCUS:
                        call = requestFocus(data.readString(), data.readStrongBinder());
                        break;
                    case ABANDON_FOCUS:
                        call = abandonFocus(data.readString());
                        break;
                    default:
                        call = "call " + code + " (not simulated, " + data.dataAvail() + " bytes)";
                }
                state.lastCall = call;
            }
            if (reply != null) reply.writeNoException();
            log(call);
            schedulePublish();
            return true;
        }
    }

    // Holding lock.
    private static String requestFocus(String packageName, IBinder callback) {
        if (holderCallback != null && holderCallback != callback && !carNavigation) send(holderCallback, FOCUS_LOST);
        holderPackage = packageName;
        holderCallback = callback;
        if (carNavigation) {
            send(callback, FOCUS_LOST);
            return "requestNaviFocus " + packageName + " (refused: the car's navigation has the HUD)";
        }
        state.focus = text(packageName);
        send(callback, FOCUS_GAINED);
        return "requestNaviFocus " + packageName + " (granted)";
    }

    // Holding lock.
    private static String abandonFocus(String packageName) {
        if (packageName != null && packageName.equals(holderPackage)) {
            holderPackage = null;
            holderCallback = null;
        }
        state.focus = carNavigation ? HudState.FOCUS_CAR : "";
        return "abandonNaviFocus " + packageName;
    }

    /** Pretends the car's own navigation takes the HUD ("car") or gives it back ("release"). */
    private static void readControl() {
        if (!CONTROL.exists()) return;
        String command;
        try {
            command = new String(Files.readAllBytes(CONTROL.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            command = "";
        }
        CONTROL.delete();
        String change;
        synchronized (lock) {
            if (command.equals("car")) {
                carNavigation = true;
                state.focus = HudState.FOCUS_CAR;
                send(holderCallback, FOCUS_LOST);
                change = "the car's navigation took the HUD (simulated)";
            } else if (command.equals("release")) {
                carNavigation = false;
                state.focus = holderCallback != null ? text(holderPackage) : "";
                send(holderCallback, FOCUS_GAINED);
                change = "the car's navigation gave the HUD back (simulated)";
            } else {
                change = "unknown control command \"" + command + "\"";
            }
            state.lastCall = change;
        }
        log(change);
        schedulePublish();
    }

    /** Tells a focus holder about a change, after the reply, as the car does. */
    private static void send(IBinder callback, int value) {
        if (callback == null) return;
        worker.schedule(() -> {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(FOCUS_CALLBACK_INTERFACE);
                data.writeInt(value);
                callback.transact(ON_FOCUS_CHANGE, data, null, IBinder.FLAG_ONEWAY);
                log("onFocusChange " + value + " sent");
            } catch (RemoteException | RuntimeException e) {
                log("onFocusChange " + value + " failed: " + e);
            } finally {
                data.recycle();
            }
        }, 50, TimeUnit.MILLISECONDS);
    }

    // DiPlay sends road, turn, remain and percent together: publish once per burst.
    private static void schedulePublish() {
        synchronized (lock) {
            if (publishPending) return;
            publishPending = true;
        }
        worker.schedule(() -> {
            synchronized (lock) {
                publishPending = false;
            }
            publish();
        }, 80, TimeUnit.MILLISECONDS);
    }

    private static void publish() {
        String json;
        synchronized (lock) {
            state.sequence++;
            json = state.toJson();
        }
        try {
            Process process = new ProcessBuilder("/system/bin/settings", "put", "global", HudState.SETTINGS_KEY, json)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
        } catch (IOException e) {
            log("publish failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void log(String message) {
        Log.i(TAG, message);
        System.out.println(message);
    }
}
