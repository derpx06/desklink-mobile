/*
 * SPDX-FileCopyrightText: 2021 SohnyBohny <sohny.bean@streber24.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.mousereceiver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import org.desklink.mobile.R;
import org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.Cursor;

import java.util.ArrayDeque;
import java.util.Deque;

public class MouseReceiverService extends AccessibilityService {
    public static MouseReceiverService instance;

    private View cursorView;
    private LayoutParams cursorLayout;
    private WindowManager windowManager;
    private Handler runHandler;
    private Runnable hideRunnable;
    private GestureDescription.StrokeDescription swipeStoke;

    @Override
    public void onCreate() {
        MouseReceiverService.instance = this;
        Log.i("MouseReceiverService", "created");
    }

    @Override
    protected void onServiceConnected() {
        // Create an overlay and display the cursor
        windowManager = ContextCompat.getSystemService(this, WindowManager.class);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);

        cursorView = View.inflate(getBaseContext(), R.layout.mouse_receiver_cursor, null);
        cursorLayout = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                LayoutParams.FLAG_DISMISS_KEYGUARD | LayoutParams.FLAG_NOT_FOCUSABLE
                        | LayoutParams.FLAG_NOT_TOUCHABLE | LayoutParams.FLAG_FULLSCREEN
                        | LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        // allow cursor to move over status bar on devices having a display cutout
        // https://developer.android.com/guide/topics/display-cutout/#render_content_in_short_edge_cutout_areas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cursorLayout.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        cursorLayout.gravity = Gravity.START | Gravity.TOP;
        cursorLayout.x = displayMetrics.widthPixels / 2;
        cursorLayout.y = displayMetrics.heightPixels / 2;

        // https://developer.android.com/training/system-ui/navigation.html#behind
        cursorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        windowManager.addView(cursorView, cursorLayout);

        hideRunnable = () -> {
            cursorView.setVisibility(View.GONE);
            Log.i("MouseReceiverService", "Hiding pointer due to inactivity");
        };
        runHandler = new Handler();

        cursorView.setVisibility(View.GONE);
    }

    private void hideAfter5Seconds() {
        instance.hide(5000);
    }

    public void hide(int delayMillis) {
        runHandler.removeCallbacks(hideRunnable);
        runHandler.postDelayed(hideRunnable, delayMillis);
    }

    public int getX() {
        return cursorLayout.x + cursorView.getWidth() / 2;
    }

    public int getY() {
        return cursorLayout.y + cursorView.getHeight() / 2;
    }

    public void moveView(int dx, int dy) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        instance.windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);

        cursorLayout.x += dx;
        cursorLayout.y += dy;

        if (getX() > displayMetrics.widthPixels)
            cursorLayout.x = displayMetrics.widthPixels - cursorView.getWidth() / 2;
        if (getY() > displayMetrics.heightPixels)
            cursorLayout.y = displayMetrics.heightPixels - cursorView.getHeight() / 2;
        if (getX() < 0) cursorLayout.x = -cursorView.getWidth() / 2;
        if (getY() < 0) cursorLayout.y = -cursorView.getHeight() / 2;

        Cursor.INSTANCE.setX(getX());
        Cursor.INSTANCE.setY(getY());

        new Handler(instance.getMainLooper()).post(() -> {
            // Log.i("MouseReceiverService", "performing move");
            try {
                instance.windowManager.updateViewLayout(instance.cursorView, instance.cursorLayout);
                instance.cursorView.setVisibility(View.VISIBLE);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        });
    }

    public static boolean move(int dx, int dy) {
        if (instance == null) return false;

        int fromX = instance.getX();
        int fromY = instance.getY();

        instance.moveView(dx, dy);

        instance.hideAfter5Seconds();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && instance.isSwiping()) {
            return instance.continueSwipe(fromX, fromY);
        }

        return true;
    }

    public static boolean setPos(int x, int y) {
        if (instance == null) return false;

        return move(x - instance.getX(), y - instance.getY());
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static GestureDescription createClick(int x, int y, int duration) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(clickPath, 0, duration);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean click() {
        if (instance == null) return false;
        // Log.i("MouseReceiverService", "x: " + instance.getX() + " y:" + instance.getY());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && instance.isSwiping()) {
            return instance.stopSwipe();
        }

        return click(instance.getX(), instance.getY());
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean click(int x, int y) {
        if (instance == null) return false;
        return instance.dispatchGesture(createClick(x, y, 1 /*ms*/), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean longClick() {
        if (instance == null) return false;
        return instance.dispatchGesture(createClick(instance.getX(), instance.getY(),
                ViewConfiguration.getLongPressTimeout()), null, null);
    }

    /**
     * Executes a long press at the exact coordinate supplied by the remote
     * screen viewer.  A screen-mode action must not depend on the position of
     * a stale accessibility cursor from an earlier pointer packet.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean longClick(int x, int y) {
        if (instance == null) return false;
        return instance.dispatchGesture(createClick(x, y,
                ViewConfiguration.getLongPressTimeout()), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean longClickSwipe() {
        if (instance == null) return false;

        if (instance.isSwiping()) {
            return instance.stopSwipe();
        } else {
            return instance.startSwipe();
        }
    }

    /** Starts a drag at an absolute remote-screen coordinate.  Unlike the
     * legacy toggle helper above, this never interprets a repeated start as a
     * request to release the current drag. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean longClickSwipe(int x, int y) {
        if (instance == null || instance.isSwiping()) return false;
        if (!setPos(x, y)) return false;
        return instance.startSwipe();
    }

    private boolean isSwiping() {
        return swipeStoke != null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean startSwipe() {
        assert swipeStoke == null;
        Path path = new Path();
        path.moveTo(getX(), getY());
        swipeStoke = new GestureDescription.StrokeDescription(path, 0, 1, true);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(swipeStoke);
        ((ImageView) cursorView.findViewById(R.id.mouse_cursor)).setImageResource(R.drawable.mouse_pointer_clicked);
        return dispatchGesture(builder.build(), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean continueSwipe(int fromX, int fromY) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(getX(), getY());
        swipeStoke = swipeStoke.continueStroke(path, 0, 5, true);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(swipeStoke);
        return dispatchGesture(builder.build(), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public boolean stopSwipe() {
        Path path = new Path();
        path.moveTo(getX(), getY());
        if (swipeStoke == null) {
            return true;
        }
        swipeStoke = swipeStoke.continueStroke(path, 0, 1, false);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(swipeStoke);
        swipeStoke = null;
        ((ImageView) cursorView.findViewById(R.id.mouse_cursor)).setImageResource(R.drawable.mouse_pointer);
        return dispatchGesture(builder.build(), null, null);
    }


    /** Phone pixels a scroll unit moves the content: the desktop sends 3
     * units per wheel notch, and a notch should move about a line of text. */
    private static final float SCROLL_PIXELS_PER_UNIT = 30f;
    /** Wheel events arriving within this window become one swipe: each new
     * gesture cancels the one in progress, so a swipe per event stuttered. */
    private static final long SCROLL_BATCH_MS = 50;
    private float pendingScrollX;
    private float pendingScrollY;
    private boolean scrollFlushScheduled;

    /**
     * Scrolls where the pointer is, by a finger swipe as long as the wheel
     * moved. It used to wait for 500 units (some 170 wheel notches) and then
     * jump a whole page in whichever scrollable node a tree walk found first —
     * on a phone being controlled from the desktop, scrolling barely worked.
     */
    public static boolean scroll(int dx, int dy) {
        if (instance == null) return false;
        final MouseReceiverService service = instance;
        service.pendingScrollX += dx;
        service.pendingScrollY += dy;
        if (!service.scrollFlushScheduled) {
            service.scrollFlushScheduled = true;
            new Handler(service.getMainLooper()).postDelayed(service::flushScroll, SCROLL_BATCH_MS);
        }
        return true;
    }

    private void flushScroll() {
        scrollFlushScheduled = false;
        float dx = pendingScrollX * SCROLL_PIXELS_PER_UNIT;
        float dy = pendingScrollY * SCROLL_PIXELS_PER_UNIT;
        pendingScrollX = 0;
        pendingScrollY = 0;
        if (dx == 0 && dy == 0) return;
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        float startX = clamp(getX(), 1, metrics.widthPixels - 2);
        float startY = clamp(getY(), 1, metrics.heightPixels - 2);
        // Wheel up (positive dy) shows earlier content: the finger moves down.
        float endX = clamp(startX + dx, 1, metrics.widthPixels - 2);
        float endY = clamp(startY + dy, 1, metrics.heightPixels - 2);
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        float distance = (float) Math.hypot(endX - startX, endY - startY);
        if (distance < 1) return;
        // Slow enough not to fling: the content moves by the swipe, not more.
        long duration = Math.max(60, (long) (distance / 1.5f));
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build(), null, null);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    // https://codelabs.developers.google.com/codelabs/developing-android-a11y-service/#6
    private AccessibilityNodeInfo findNodeByAciton(AccessibilityNodeInfo root, AccessibilityNodeInfo.AccessibilityAction action) {
        Deque<AccessibilityNodeInfo> deque = new ArrayDeque<>();
        deque.add(root);
        while (!deque.isEmpty()) {
            AccessibilityNodeInfo node = deque.removeFirst();
            if (node.getActionList().contains(action)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                deque.addLast(node.getChild(i));
            }
        }
        return null;
    }

    public static boolean backButton() {
        if (instance == null) return false;
        return instance.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean homeButton() {
        if (instance == null) return false;
        return instance.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public static boolean recentButton() {
        if (instance == null) return false;
        return instance.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public static boolean powerButton() {
        if (instance == null) return false;

        return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (windowManager != null && cursorView != null) {
            windowManager.removeView(cursorView);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // "Type on <phone>": tell the desk whether a field has focus. Settled for a moment first,
        // since one focus move sends several events.
        handler.removeCallbacks(reportFocus);
        handler.postDelayed(reportFocus, 150);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reportFocus = () ->
            FocusedFieldTyping.INSTANCE.report(this, FocusedFieldTyping.INSTANCE.focusedFieldIn(this), false);

    @Override
    public void onInterrupt() {

    }
}
