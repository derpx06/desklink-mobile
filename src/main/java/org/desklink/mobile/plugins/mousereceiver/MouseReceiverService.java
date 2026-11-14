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
import android.view.Choreographer;
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
    /** Read from the binder and main threads and from every packet: never cached across a call (see the local copies in the statics). */
    public static volatile MouseReceiverService instance;

    private View cursorView;
    private LayoutParams cursorLayout;
    private WindowManager windowManager;
    private Handler runHandler;
    private Runnable hideRunnable;
    private GestureDescription.StrokeDescription swipeStoke;
    /** The main thread's, taken when the overlay is made: frames are asked for from any thread (it is thread-safe). */
    private volatile Choreographer choreographer;
    /** The pointer should be on screen (a move since the last hide). A frame never shows a pointer that was hidden. */
    private volatile boolean shown;
    /** The position (the truth) and one overlay update per frame from it. */
    private final PointerFrames pointer = new PointerFrames(this::requestFrame);
    private final Choreographer.FrameCallback drawFrame = frameTimeNanos -> drawPointer();
    /** The pointer hides after this long without a move. */
    private static final int IDLE_HIDE_MS = 5000;

    @Override
    public void onCreate() {
        MouseReceiverService.instance = this;
        Log.i("MouseReceiverService", "created");
        org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.reportAllowedToAll();
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
        DisplayMetrics real = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(real);
        pointer.setBounds(real.widthPixels, real.heightPixels);
        pointer.moveTo(real.widthPixels / 2.0, real.heightPixels / 2.0);

        // https://developer.android.com/training/system-ui/navigation.html#behind
        cursorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        windowManager.addView(cursorView, cursorLayout);

        hideRunnable = () -> {
            shown = false;
            if (cursorView == null) return;
            cursorView.setVisibility(View.GONE);
            Log.i("MouseReceiverService", "Hiding pointer due to inactivity");
        };
        runHandler = new Handler();
        choreographer = Choreographer.getInstance();

        cursorView.setVisibility(View.GONE);
    }

    /** Hides the pointer after [delayMillis]; 0 hides it for good, so a frame already asked for does not show it again. */
    public void hide(int delayMillis) {
        if (delayMillis == 0) shown = false;
        scheduleHide(delayMillis);
    }

    private void scheduleHide(int delayMillis) {
        final Handler handler = runHandler;
        final Runnable hider = hideRunnable;
        if (handler == null || hider == null) return; // not connected yet, or already torn down
        handler.removeCallbacks(hider);
        handler.postDelayed(hider, delayMillis);
    }

    /** Where the pointer's hot spot is (real pixels): the position, not the overlay, which may be a frame behind. */
    public int getX() {
        return pointer.x();
    }

    public int getY() {
        return pointer.y();
    }

    private boolean requestFrame() {
        final Choreographer c = choreographer;
        if (c == null) return false;
        c.postFrameCallback(drawFrame);
        return true;
    }

    /** One frame: the overlay goes where the pointer is now, whatever number of moves came since the last frame. */
    private void drawPointer() {
        final int[] at = pointer.frame();
        final WindowManager manager = windowManager;
        final View view = cursorView;
        final LayoutParams layout = cursorLayout;
        if (manager == null || view == null || layout == null) return; // switched off since the frame was asked for
        layout.x = at[0] - view.getWidth() / 2;
        layout.y = at[1] - view.getHeight() / 2;
        try {
            manager.updateViewLayout(view, layout);
            if (shown) {
                view.setVisibility(View.VISIBLE);
                scheduleHide(IDLE_HIDE_MS);
            }
        } catch (RuntimeException e) {
            // The window went with the service (IllegalArgumentException), or the token did (BadTokenException).
            Log.w("MouseReceiverService", "pointer update dropped: " + e.getClass().getSimpleName());
        }
    }

    private void moveBy(double dx, double dy) {
        final WindowManager manager = windowManager;
        if (manager != null) {
            // The real size, read each move: a rotation changes it.
            DisplayMetrics displayMetrics = new DisplayMetrics();
            manager.getDefaultDisplay().getRealMetrics(displayMetrics);
            pointer.setBounds(displayMetrics.widthPixels, displayMetrics.heightPixels);
        }
        shown = true;
        pointer.moveBy(dx, dy);
        Cursor.INSTANCE.setX(pointer.x());
        Cursor.INSTANCE.setY(pointer.y());
    }

    /** Moves the pointer by a delta in desk pixels; fractions are kept, not rounded up per packet. */
    public static boolean move(double dx, double dy) {
        final MouseReceiverService s = instance;
        if (s == null) return false;

        int fromX = s.getX();
        int fromY = s.getY();

        s.moveBy(dx, dy);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && s.isSwiping()
                && (fromX != s.getX() || fromY != s.getY())) {
            return s.continueSwipe(fromX, fromY);
        }

        return true;
    }

    public static boolean move(int dx, int dy) {
        return move((double) dx, (double) dy);
    }

    public static boolean setPos(int x, int y) {
        final MouseReceiverService s = instance;
        if (s == null) return false;

        return move((double) (x - s.getX()), (double) (y - s.getY()));
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
        final MouseReceiverService s = instance;
        if (s == null) return false;
        // Log.i("MouseReceiverService", "x: " + s.getX() + " y:" + s.getY());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && s.isSwiping()) {
            return s.stopSwipe();
        }

        return click(s.getX(), s.getY());
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean click(int x, int y) {
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.dispatchGesture(createClick(x, y, 1 /*ms*/), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean longClick() {
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.dispatchGesture(createClick(s.getX(), s.getY(),
                ViewConfiguration.getLongPressTimeout()), null, null);
    }

    /**
     * Executes a long press at the exact coordinate supplied by the remote
     * screen viewer.  A screen-mode action must not depend on the position of
     * a stale accessibility cursor from an earlier pointer packet.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean longClick(int x, int y) {
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.dispatchGesture(createClick(x, y,
                ViewConfiguration.getLongPressTimeout()), null, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean longClickSwipe() {
        final MouseReceiverService s = instance;
        if (s == null) return false;

        if (s.isSwiping()) {
            return s.stopSwipe();
        } else {
            return s.startSwipe();
        }
    }

    /** Starts a drag at an absolute remote-screen coordinate.  Unlike the
     * legacy toggle helper above, this never interprets a repeated start as a
     * request to release the current drag. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean longClickSwipe(int x, int y) {
        final MouseReceiverService s = instance;
        if (s == null || s.isSwiping()) return false;
        if (!setPos(x, y)) return false;
        return s.startSwipe();
    }

    /**
     * Lets go of anything a remote user was holding: a drag in progress is released at its last point. Called whenever a
     * session ends, pauses or drops, so a finger is never left down on the screen for the next pointer move to drag.
     */
    public static void releaseHeld() {
        final MouseReceiverService s = instance;
        if (s == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && s.isSwiping()) {
            s.stopSwipe();
        }
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
        showPointer(R.drawable.mouse_pointer_clicked);
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
        showPointer(R.drawable.mouse_pointer);
        return dispatchGesture(builder.build(), null, null);
    }

    /** The pointer's picture (pressed or not), changed on the main thread: drags start from the input thread. */
    private void showPointer(int drawable) {
        final View view = cursorView;
        final Handler handler = runHandler;
        if (view == null || handler == null) return;
        handler.post(() -> ((ImageView) view.findViewById(R.id.mouse_cursor)).setImageResource(drawable));
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
        final MouseReceiverService service = instance;
        if (service == null) return false;
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
        // A wheel event between onCreate (instance) and onServiceConnected (windowManager) is dropped, not an NPE.
        if ((dx == 0 && dy == 0) || windowManager == null) return;
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
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean homeButton() {
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public static boolean recentButton() {
        final MouseReceiverService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public static boolean powerButton() {
        final MouseReceiverService s = instance;
        if (s == null) return false;

        return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Switched off or killed while the desk's pointer is here: it is handed back before this goes.
        org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.endActive(true);
        if (instance == this) instance = null;
        org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.reportAllowedToAll();
        if (windowManager != null && cursorView != null) {
            try {
                windowManager.removeView(cursorView);
            } catch (IllegalArgumentException ignored) {
                // already gone with its window
            }
        }
        cursorView = null;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.endActive(true);
        if (instance == this) instance = null;
        org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.reportAllowedToAll();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {

    }
}
