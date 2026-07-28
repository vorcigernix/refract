/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.inputmethod.latin;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Factory for dictionary instances.
 */
public final class DictionaryFactory {
    private static final String TAG = DictionaryFactory.class.getSimpleName();

    /**
     * Initializes a main dictionary collection from the dictionaries bundled with Refract.
     * Refract intentionally does not support downloadable dictionary packs.
     * @param context application context for reading resources
     * @param locale the locale for which to create the dictionary
     * @return an initialized instance of DictionaryCollection
     */
    public static DictionaryCollection createMainDictionaryFromManager(final Context context,
            final Locale locale) {
        return new DictionaryCollection(Dictionary.TYPE_MAIN, locale,
                createReadOnlyBinaryDictionary(context, locale));
    }

    /**
     * Initializes a read-only binary dictionary from a raw resource file
     * @param context application context for reading resources
     * @param locale the locale to use for the resource
     * @return an initialized instance of ReadOnlyBinaryDictionary
     */
    private static ReadOnlyBinaryDictionary createReadOnlyBinaryDictionary(final Context context,
            final Locale locale) {
        AssetFileDescriptor afd = null;
        try {
            final int resId = getMainDictionaryResourceId(locale);
            if (0 == resId) return null;
            afd = context.getResources().openRawResourceFd(resId);
            if (afd == null) {
                Log.e(TAG, "Found the resource but it is compressed. resId=" + resId);
                return null;
            }
            final String sourceDir = context.getApplicationInfo().sourceDir;
            final File packagePath = new File(sourceDir);
            // TODO: Come up with a way to handle a directory.
            if (!packagePath.isFile()) {
                Log.e(TAG, "sourceDir is not a file: " + sourceDir);
                return null;
            }
            return new ReadOnlyBinaryDictionary(sourceDir, afd.getStartOffset(), afd.getLength(),
                    false /* useFullEditDistance */, locale, Dictionary.TYPE_MAIN);
        } catch (android.content.res.Resources.NotFoundException e) {
            Log.e(TAG, "Could not find the resource");
            return null;
        } finally {
            if (null != afd) {
                try {
                    afd.close();
                } catch (java.io.IOException e) {
                    /* IOException on close ? What am I supposed to do ? */
                }
            }
        }
    }

    private static int getMainDictionaryResourceId(final Locale locale) {
        if (locale == null) {
            return R.raw.main;
        }
        if ("pt".equals(locale.getLanguage()) && "BR".equals(locale.getCountry())) {
            return R.raw.main_pt_br;
        }
        return switch (locale.getLanguage()) {
            case "de" -> R.raw.main_de;
            case "en" -> R.raw.main_en;
            case "es" -> R.raw.main_es;
            case "fr" -> R.raw.main_fr;
            case "it" -> R.raw.main_it;
            case "ru" -> R.raw.main_ru;
            default -> R.raw.main;
        };
    }
}
