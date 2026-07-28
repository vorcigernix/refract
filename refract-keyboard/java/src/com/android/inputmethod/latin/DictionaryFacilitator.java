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

package com.android.inputmethod.latin;

import android.content.Context;
import android.text.TextUtils;

import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.common.ComposedData;
import com.android.inputmethod.latin.settings.SettingsValuesForSuggestion;
import com.android.inputmethod.latin.utils.ExecutorUtils;
import com.android.inputmethod.latin.utils.SuggestionResults;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Owns the two dictionaries used by Refract Keyboard: the locale's main dictionary and Android's
 * user dictionary.
 */
public final class DictionaryFacilitator {
    private static final String TAG = DictionaryFacilitator.class.getSimpleName();

    public interface DictionaryInitializationListener {
        void onUpdateMainDictionaryAvailability(boolean isMainDictionaryAvailable);
    }

    private static final class DictionaryGroup {
        private final Locale mLocale;
        private Dictionary mMainDictionary;
        private UserBinaryDictionary mUserDictionary;

        DictionaryGroup() {
            this(null, null, null);
        }

        DictionaryGroup(
                final Locale locale,
                final Dictionary mainDictionary,
                final UserBinaryDictionary userDictionary) {
            mLocale = locale;
            mMainDictionary = mainDictionary;
            mUserDictionary = userDictionary;
        }

        void closeExcept(final DictionaryGroup retainedGroup) {
            if (mMainDictionary != null && mMainDictionary != retainedGroup.mMainDictionary) {
                mMainDictionary.close();
            }
            if (mUserDictionary != null && mUserDictionary != retainedGroup.mUserDictionary) {
                mUserDictionary.close();
            }
        }

        void close() {
            if (mMainDictionary != null) {
                mMainDictionary.close();
                mMainDictionary = null;
            }
            if (mUserDictionary != null) {
                mUserDictionary.close();
                mUserDictionary = null;
            }
        }
    }

    private final Object mLock = new Object();
    private DictionaryGroup mDictionaryGroup = new DictionaryGroup();

    public boolean isActive() {
        return mDictionaryGroup.mLocale != null;
    }

    public Locale getLocale() {
        return mDictionaryGroup.mLocale;
    }

    public boolean isForLocale(final Locale locale) {
        return locale != null && locale.equals(mDictionaryGroup.mLocale);
    }

    public void resetDictionaries(
            final Context context,
            final Locale locale,
            final boolean forceReloadMainDictionary,
            final DictionaryInitializationListener listener) {
        final DictionaryGroup oldGroup;
        final DictionaryGroup newGroup;
        synchronized (mLock) {
            oldGroup = mDictionaryGroup;
            final boolean sameLocale = locale.equals(oldGroup.mLocale);
            final Dictionary mainDictionary =
                    sameLocale && !forceReloadMainDictionary ? oldGroup.mMainDictionary : null;
            final UserBinaryDictionary userDictionary = sameLocale
                    ? oldGroup.mUserDictionary
                    : UserBinaryDictionary.getDictionary(context, locale, null, "", null);
            newGroup = new DictionaryGroup(locale, mainDictionary, userDictionary);
            mDictionaryGroup = newGroup;
        }
        oldGroup.closeExcept(newGroup);

        if (newGroup.mMainDictionary == null) {
            loadMainDictionaryAsync(context, newGroup, listener);
        } else if (listener != null) {
            listener.onUpdateMainDictionaryAvailability(
                    newGroup.mMainDictionary.isInitialized());
        }
    }

    private void loadMainDictionaryAsync(
            final Context context,
            final DictionaryGroup targetGroup,
            final DictionaryInitializationListener listener) {
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(() -> {
            final Dictionary mainDictionary =
                    DictionaryFactory.createMainDictionaryFromManager(context, targetGroup.mLocale);
            boolean accepted = false;
            synchronized (mLock) {
                if (mDictionaryGroup == targetGroup) {
                    targetGroup.mMainDictionary = mainDictionary;
                    accepted = true;
                }
            }
            if (!accepted) {
                mainDictionary.close();
            } else if (listener != null) {
                listener.onUpdateMainDictionaryAvailability(mainDictionary.isInitialized());
            }
        });
    }

    public void closeDictionaries() {
        final DictionaryGroup groupToClose;
        synchronized (mLock) {
            groupToClose = mDictionaryGroup;
            mDictionaryGroup = new DictionaryGroup();
        }
        groupToClose.close();
    }

    public boolean hasAtLeastOneInitializedMainDictionary() {
        final Dictionary dictionary = mDictionaryGroup.mMainDictionary;
        return dictionary != null && dictionary.isInitialized();
    }

    public SuggestionResults getSuggestionResults(
            final ComposedData composedData,
            final NgramContext ngramContext,
            final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int sessionId,
            final int inputStyle) {
        final SuggestionResults results = new SuggestionResults(
                SuggestedWords.MAX_SUGGESTIONS,
                ngramContext.isBeginningOfSentenceContext(),
                false);
        final float[] modelWeight = {
                Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL
        };
        addSuggestions(
                mDictionaryGroup.mMainDictionary,
                composedData,
                ngramContext,
                keyboard,
                settingsValuesForSuggestion,
                sessionId,
                modelWeight,
                results);
        addSuggestions(
                mDictionaryGroup.mUserDictionary,
                composedData,
                ngramContext,
                keyboard,
                settingsValuesForSuggestion,
                sessionId,
                modelWeight,
                results);
        return results;
    }

    private static void addSuggestions(
            final Dictionary dictionary,
            final ComposedData composedData,
            final NgramContext ngramContext,
            final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValues,
            final int sessionId,
            final float[] modelWeight,
            final SuggestionResults results) {
        if (dictionary == null) {
            return;
        }
        final ArrayList<SuggestedWordInfo> suggestions = dictionary.getSuggestions(
                composedData,
                ngramContext,
                keyboard.getProximityInfo().getNativeProximityInfo(),
                settingsValues,
                sessionId,
                1.0f,
                modelWeight);
        if (suggestions == null) {
            return;
        }
        results.addAll(suggestions);
    }

    private boolean isValidWord(final String word) {
        if (TextUtils.isEmpty(word) || mDictionaryGroup.mLocale == null) {
            return false;
        }
        final Dictionary main = mDictionaryGroup.mMainDictionary;
        final Dictionary user = mDictionaryGroup.mUserDictionary;
        return main != null && main.isValidWord(word) || user != null && user.isValidWord(word);
    }

    public String dump(final Context context) {
        return "";
    }
}
