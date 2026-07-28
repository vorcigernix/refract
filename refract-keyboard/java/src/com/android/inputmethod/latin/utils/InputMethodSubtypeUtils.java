/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.text.TextUtils;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.latin.RichInputMethodSubtype;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.common.LocaleUtils;

import java.util.Locale;

import androidx.annotation.NonNull;

public final class InputMethodSubtypeUtils {
    private InputMethodSubtypeUtils() {
        // This utility class is not publicly instantiable.
    }

    @SuppressWarnings("deprecation")
    @NonNull
    public static InputMethodSubtype newInputMethodSubtype(int nameId, int iconId, String locale,
            String mode, String extraValue, boolean isAuxiliary,
            boolean overridesImplicitlyEnabledSubtype, int id) {
        return new InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameResId(nameId)
                .setSubtypeIconResId(iconId)
                .setSubtypeLocale(locale)
                .setSubtypeMode(mode)
                .setSubtypeExtraValue(extraValue)
                .setIsAuxiliary(isAuxiliary)
                .setOverridesImplicitlyEnabledSubtype(overridesImplicitlyEnabledSubtype)
                .setIsAsciiCapable(extraValue.contains(Constants.Subtype.ExtraValue.ASCII_CAPABLE))
                .setSubtypeId(id)
                .build();
    }

    public static boolean isAsciiCapable(final RichInputMethodSubtype subtype) {
        return isAsciiCapable(subtype.getRawSubtype());
    }

    public static boolean isAsciiCapable(final InputMethodSubtype subtype) {
        return subtype.isAsciiCapable()
                || subtype.containsExtraValueKey(Constants.Subtype.ExtraValue.ASCII_CAPABLE);
    }

    public static Locale getLocaleObject(final InputMethodSubtype subtype) {
        final String languageTag = subtype.getLanguageTag();
        if (!TextUtils.isEmpty(languageTag)) {
            return Locale.forLanguageTag(languageTag);
        }
        return LocaleUtils.constructLocaleFromString(subtype.getLocale());
    }
}
