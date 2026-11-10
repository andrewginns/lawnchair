/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.widget.util

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.util.Executors
import javax.inject.Inject

/** Helper class for handling widget updates */
open class WidgetSizeHandler @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Updates the size range of a bound widget when it changes. Comparing options first avoids
     * waking the provider for a full widget update when the sizes are already current.
     */
    open fun updateSizeRangesAsync(
        widgetId: Int,
        info: AppWidgetProviderInfo,
        spanX: Int,
        spanY: Int,
    ) {
        Executors.UI_HELPER_EXECUTOR.execute {
            val provider = info.provider ?: return@execute
            val widgetManager = AppWidgetManager.getInstance(context)
            val sizeOptions = WidgetSizes.getWidgetSizeOptions(context, provider, spanX, spanY)
                ?: return@execute
            val currentOptions = widgetManager.getAppWidgetOptions(widgetId)
            if (sizeOptions.getWidgetSizeList() == currentOptions.getWidgetSizeList()) return@execute

            // A restored placeholder can have a widget ID before Android binds its provider.
            // Android hasn't initialized the options for that ID yet.
            if (widgetManager.getAppWidgetInfo(widgetId)?.provider != provider) return@execute

            widgetManager.updateAppWidgetOptions(widgetId, sizeOptions)
        }
    }

    companion object {

        fun Bundle.getWidgetSizeList() = getParcelableArrayList<SizeF>(OPTION_APPWIDGET_SIZES)
    }
}
