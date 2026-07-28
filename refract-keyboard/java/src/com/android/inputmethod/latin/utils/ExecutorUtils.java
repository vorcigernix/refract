/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.inputmethod.latin.utils;

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Shared serial executor for dictionary work. */
public final class ExecutorUtils {
    public static final String KEYBOARD = "Keyboard";

    private static final ScheduledExecutorService KEYBOARD_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, KEYBOARD);
                thread.setUncaughtExceptionHandler(
                        (failedThread, error) -> Log.w(KEYBOARD, error));
                return thread;
            });

    private ExecutorUtils() {}

    public static ScheduledExecutorService getBackgroundExecutor(final String name) {
        if (!KEYBOARD.equals(name)) {
            throw new IllegalArgumentException("Invalid executor: " + name);
        }
        return KEYBOARD_EXECUTOR;
    }
}
