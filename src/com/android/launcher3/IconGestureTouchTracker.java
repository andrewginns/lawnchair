/*
 * Copyright (C) 2026 The Lawnchair Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.launcher3.folder.FolderIcon;

/**
 * Tracks a touch that starts on an icon and determines whether its current direction has an icon
 * gesture configured. Controllers can then leave only configured directions to the icon.
 */
public final class IconGestureTouchTracker {

    private final int mTouchSlop;
    private View mTouchedIcon;
    private int mInitialPointerId = MotionEvent.INVALID_POINTER_ID;
    private float mDownX;
    private float mDownY;

    public IconGestureTouchTracker(Context context) {
        this(ViewConfiguration.get(context).getScaledTouchSlop());
    }

    IconGestureTouchTracker(int touchSlop) {
        mTouchSlop = touchSlop;
    }

    /** Finds the touched icon for an event in drag-layer coordinates. */
    public void onDragLayerTouchDown(MotionEvent ev, Launcher launcher) {
        View icon = null;
        if (launcher.getDragLayer() != null) {
            float[] coord = new float[]{ev.getX(), ev.getY()};
            Hotseat hotseat = launcher.getHotseat();
            if (hotseat != null && hotseat.isShown()) {
                launcher.getDragLayer().mapCoordInSelfToDescendant(hotseat, coord);
                icon = hotseat.findIconAtPosition(coord[0], coord[1]);
            }
            Workspace<?> workspace = launcher.getWorkspace();
            if (icon == null && workspace != null) {
                coord[0] = ev.getX();
                coord[1] = ev.getY();
                launcher.getDragLayer().mapCoordInSelfToDescendant(workspace, coord);
                icon = workspace.findIconAtPosition(coord[0], coord[1]);
            }
        }
        onTouchDown(ev, icon);
    }

    /** Records the touched icon when the caller already has local hit-test coordinates. */
    public void onTouchDown(MotionEvent ev, View icon) {
        mTouchedIcon = icon;
        mInitialPointerId = ev.getPointerId(0);
        mDownX = ev.getX();
        mDownY = ev.getY();
    }

    /** Returns whether the initial pointer moves toward an action configured on the touched icon. */
    public boolean isMovingTowardConfiguredIconGesture(MotionEvent ev) {
        if (mTouchedIcon == null || ev.getActionMasked() != MotionEvent.ACTION_MOVE
                || ev.getPointerCount() != 1) {
            return false;
        }
        int pointerIndex = ev.findPointerIndex(mInitialPointerId);
        if (pointerIndex < 0) {
            return false;
        }
        float diffX = ev.getX(pointerIndex) - mDownX;
        float diffY = ev.getY(pointerIndex) - mDownY;
        if (Math.max(Math.abs(diffX), Math.abs(diffY)) < mTouchSlop) {
            return false;
        }
        if (mTouchedIcon instanceof BubbleTextView icon) {
            return icon.hasConfiguredIconSwipeGestureForDirection(diffX, diffY);
        }
        return mTouchedIcon instanceof FolderIcon folder && folder.canSwipeOpenCover()
                && diffY < 0 && -diffY >= Math.abs(diffX);
    }

    /** Clears the touch state after the gesture finishes or is cancelled. */
    public void reset() {
        mInitialPointerId = MotionEvent.INVALID_POINTER_ID;
        mTouchedIcon = null;
    }
}
