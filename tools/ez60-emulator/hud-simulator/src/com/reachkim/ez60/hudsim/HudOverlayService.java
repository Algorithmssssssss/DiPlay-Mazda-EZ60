package com.reachkim.ez60.hudsim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Draws the car's own part of the HUD over the emulator's 800x480 HUD display, from what
 * {@link FakeCarHudService} received. DiPlay's window shows through the part the car projects.
 * Started by `ez60-emu hud start`.
 */
public final class HudOverlayService extends Service {
    private static final String CHANNEL = "hud";
    private static final int HUD_WIDTH = 800;
    private static final int HUD_HEIGHT = 480;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private HudView view;
    private ContentObserver observer;
    private DisplayManager.DisplayListener displayListener;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification();
        if (observer == null) {
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh();
                }
            };
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(HudState.SETTINGS_KEY), false, observer);
        }
        if (displayListener == null) {
            // The emulator adds its second display shortly after Android starts.
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    attach();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                }
            };
            getSystemService(DisplayManager.class).registerDisplayListener(displayListener, handler);
        }
        attach();
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        if (displayListener != null) getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        if (view != null) windowManager.removeView(view);
        view = null;
        super.onDestroy();
    }

    private void showNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "HUD simulator", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("EZ-60 HUD simulator")
                .setContentText("Drawing the car's HUD on the 800x480 display")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(1, notification);
    }

    /** The display DiPlay uses as the HUD: an 800x480 presentation display (MazdaHudScreen.findDisplay). */
    private Display findHudDisplay() {
        for (Display display : getSystemService(DisplayManager.class)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            Point size = new Point();
            display.getRealSize(size);
            if (size.x == HUD_WIDTH && size.y == HUD_HEIGHT) return display;
        }
        return null;
    }

    private void attach() {
        if (view != null) return;
        Display display = findHudDisplay();
        if (display == null) {
            Log.w(FakeCarHudService.TAG, "overlay: no 800x480 presentation display yet");
            return;
        }
        Context windowContext = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windowManager = windowContext.getSystemService(WindowManager.class);
        HudView hud = new HudView(windowContext);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.setTitle("EZ-60 HUD simulator");
        try {
            windowManager.addView(hud, params);
            view = hud;
            Log.i(FakeCarHudService.TAG, "overlay: drawing on display " + display.getDisplayId());
        } catch (RuntimeException e) {
            Log.e(FakeCarHudService.TAG, "overlay: could not add the window on display " + display.getDisplayId(), e);
        }
    }

    private void refresh() {
        if (view == null) return;
        view.setState(HudState.fromJson(Settings.Global.getString(getContentResolver(), HudState.SETTINGS_KEY)));
    }
}
