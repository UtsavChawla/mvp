/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.launcher3.allapps;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.os.Process;
import android.os.UserHandle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.launcher3.AppInfo;
import com.android.launcher3.IconCache;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.compat.AlphabeticIndexCompat;
import com.android.launcher3.compat.LauncherAppsCompat;
import com.android.launcher3.compat.UserManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.discovery.AppDiscoveryAppInfo;
import com.android.launcher3.discovery.AppDiscoveryItem;
import com.android.launcher3.discovery.AppDiscoveryUpdateState;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.ComponentKeyMapper;
import com.android.launcher3.util.LabelComparator;
import com.utsav.mConstants;
import com.utsav.myapplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The alphabetically sorted list of applications.
 */
public class AlphabeticalAppsList {

    public static final String TAG = "AlphabeticalAppsList";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PREDICTIONS = false;

    private static final int FAST_SCROLL_FRACTION_DISTRIBUTE_BY_ROWS_FRACTION = 0;
    private static final int FAST_SCROLL_FRACTION_DISTRIBUTE_BY_NUM_SECTIONS = 1;

    private final int mFastScrollDistributionMode = FAST_SCROLL_FRACTION_DISTRIBUTE_BY_NUM_SECTIONS;

    private AppDiscoveryUpdateState mAppDiscoveryUpdateState;

    /**
     * Info about a fast scroller section, depending if sections are merged, the fast scroller
     * sections will not be the same set as the section headers.
     */
    public static class FastScrollSectionInfo {
        // The section name
        public String sectionName;
        // The AdapterItem to scroll to for this section
        public AdapterItem fastScrollToItem;
        // The touch fraction that should map to this fast scroll section info
        public float touchFraction;

        public FastScrollSectionInfo(String sectionName) {
            this.sectionName = sectionName;
        }
    }

    /**
     * Info about a particular adapter item (can be either section or app)
     */
    public static class AdapterItem {
        /** Common properties */
        // The index of this adapter item in the list
        public int position;
        // The type of this item
        public int viewType;

        /** App-only properties */
        // The section name of this app.  Note that there can be multiple items with different
        // sectionNames in the same section
        public String sectionName = null;
        // The row that this item shows up on
        public int rowIndex;
        // The index of this app in the row
        public int rowAppIndex;
        // The associated AppInfo for the app
        public AppInfo appInfo = null;
        // The index of this app not including sections
        public int appIndex = -1;

        public static AdapterItem asPredictedApp(int pos, String sectionName, AppInfo appInfo,
                int appIndex) {
            AdapterItem item = asApp(pos, sectionName, appInfo, appIndex);
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_PREDICTION_ICON;
            return item;
        }

        public static AdapterItem asApp(int pos, String sectionName, AppInfo appInfo,
                int appIndex) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_ICON;
            item.position = pos;
            item.sectionName = sectionName;
            item.appInfo = appInfo;
            item.appIndex = appIndex;
            return item;
        }

        public static AdapterItem asDiscoveryItem(int pos, String sectionName, AppInfo appInfo,
                int appIndex) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_DISCOVERY_ITEM;
            item.position = pos;
            item.sectionName = sectionName;
            item.appInfo = appInfo;
            item.appIndex = appIndex;
            return item;
        }

        public static AdapterItem asEmptySearch(int pos) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_EMPTY_SEARCH;
            item.position = pos;
            return item;
        }

        public static AdapterItem asPredictionDivider(int pos) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_PREDICTION_DIVIDER;
            item.position = pos;
            return item;
        }

        public static AdapterItem asMarketDivider(int pos) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_SEARCH_MARKET_DIVIDER;
            item.position = pos;
            return item;
        }

        public static AdapterItem asLoadingDivider(int pos) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_APPS_LOADING_DIVIDER;
            item.position = pos;
            return item;
        }

        public static AdapterItem asMarketSearch(int pos) {
            AdapterItem item = new AdapterItem();
            item.viewType = AllAppsGridAdapter.VIEW_TYPE_SEARCH_MARKET;
            item.position = pos;
            return item;
        }
    }

    private final Launcher mLauncher;
    private final Context mContext;

    // The set of apps from the system not including predictions
    private final List<AppInfo> mApps = new ArrayList<>();
    private final HashMap<ComponentKey, AppInfo> mComponentToAppMap = new HashMap<>();

    // The set of filtered apps with the current filter
    private final List<AppInfo> mFilteredApps = new ArrayList<>();
    // The current set of adapter items
    private final ArrayList<AdapterItem> mAdapterItems = new ArrayList<>();
    // The set of sections that we allow fast-scrolling to (includes non-merged sections)
    private final List<FastScrollSectionInfo> mFastScrollerSections = new ArrayList<>();
    // The set of predicted app component names
    private final List<ComponentKeyMapper<AppInfo>> mPredictedAppComponents = new ArrayList<>();
    // The set of predicted apps resolved from the component names and the current set of apps
    private final List<AppInfo> mPredictedApps = new ArrayList<>();
    private final List<AppDiscoveryAppInfo> mDiscoveredApps = new ArrayList<>();

    // The of ordered component names as a result of a search query
    private ArrayList<ComponentKey> mSearchResults;
    private HashMap<CharSequence, String> mCachedSectionNames = new HashMap<>();
    private AllAppsGridAdapter mAdapter;
    private AlphabeticIndexCompat mIndexer;
    private AppInfoComparator mAppNameComparator;
    private int mNumAppsPerRow;
    private int mNumPredictedAppsPerRow;
    private int mNumAppRowsInAdapter;

    public AlphabeticalAppsList(Context context) {
        mLauncher = Launcher.getLauncher(context);
        mIndexer = new AlphabeticIndexCompat(context);
        mAppNameComparator = new AppInfoComparator(context);
        mContext = context;
    }

    /**
     * Sets the number of apps per row.
     */
    public void setNumAppsPerRow(int numAppsPerRow, int numPredictedAppsPerRow) {
        mNumAppsPerRow = numAppsPerRow;
        mNumPredictedAppsPerRow = numPredictedAppsPerRow;

        updateAdapterItems();
    }

    /**
     * Sets the adapter to notify when this dataset changes.
     */
    public void setAdapter(AllAppsGridAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Returns all the apps.
     */
    public List<AppInfo> getApps() {
        return mApps;
    }

    /**
     * Returns the predicted apps.
     */
    public List<AppInfo> getPredictedApps() {
        return mPredictedApps;
    }

    /**
     * Returns fast scroller sections of all the current filtered applications.
     */
    public List<FastScrollSectionInfo> getFastScrollerSections() {
        return mFastScrollerSections;
    }

    /**
     * Returns the current filtered list of applications broken down into their sections.
     */
    public List<AdapterItem> getAdapterItems() {
        return mAdapterItems;
    }

    /**
     * Returns the number of rows of applications (not including predictions)
     */
    public int getNumAppRows() {
        return mNumAppRowsInAdapter;
    }

    /**
     * Returns the number of applications in this list.
     */
    public int getNumFilteredApps() {
        return mFilteredApps.size();
    }

    /**
     * Returns whether there are is a filter set.
     */
    public boolean hasFilter() {
        return (mSearchResults != null);
    }

    /**
     * Returns whether there are no filtered results.
     */
    public boolean hasNoFilteredResults() {
        return (mSearchResults != null) && mFilteredApps.isEmpty();
    }

    boolean shouldShowEmptySearch() {
        return hasNoFilteredResults() && !isAppDiscoveryRunning() && mDiscoveredApps.isEmpty();
    }

    /**
     * Sets the sorted list of filtered components.
     */
    public boolean setOrderedFilter(ArrayList<ComponentKey> f) {
        if (mSearchResults != f) {
            boolean same = mSearchResults != null && mSearchResults.equals(f);
            mSearchResults = f;
            updateAdapterItems();
            return !same;
        }
        return false;
    }

    public void onAppDiscoverySearchUpdate(@Nullable AppDiscoveryItem app,
                @NonNull AppDiscoveryUpdateState state) {
        mAppDiscoveryUpdateState = state;
        switch (state) {
            case START:
                mDiscoveredApps.clear();
                break;
            case UPDATE:
                mDiscoveredApps.add(new AppDiscoveryAppInfo(app));
                break;
        }
        updateAdapterItems();
    }

    //predicted apps would not show the flagged apps

    private List<AppInfo> processPredictedAppComponents(List<ComponentKeyMapper<AppInfo>> components) {
        if (mComponentToAppMap.isEmpty()) {
            // Apps have not been bound yet.
            return Collections.emptyList();
        }

        Set<String> pnames = mContext.getSharedPreferences(mConstants.Sharedprefname, Context.MODE_PRIVATE).getStringSet(mConstants.flaggedpackagekey, new HashSet<String>());
        List<AppInfo> predictedApps = new ArrayList<>();
        for (ComponentKeyMapper<AppInfo> mapper : components) {
            AppInfo info = mapper.getItem(mComponentToAppMap);
            if (info != null) {
                if(!pnames.contains(info.componentName.getPackageName()))
                predictedApps.add(info);
            } else {
                if (FeatureFlags.IS_DOGFOOD_BUILD) {
                    Log.e(TAG, "Predicted app not found: " + mapper);
                }
            }
            // Stop at the number of predicted apps
            if (predictedApps.size() == mNumPredictedAppsPerRow) {
                break;
            }
        }
        return predictedApps;
    }

    /**
     * Sets the current set of predicted apps.
     *
     * This can be called before we get the full set of applications, we should merge the results
     * only in onAppsUpdated() which is idempotent.
     *
     * If the number of predicted apps is the same as the previous list of predicted apps,
     * we can optimize by swapping them in place.
     */
    public void setPredictedApps(List<ComponentKeyMapper<AppInfo>> apps) {
        mPredictedAppComponents.clear();
        mPredictedAppComponents.addAll(apps);

        List<AppInfo> newPredictedApps = processPredictedAppComponents(apps);
        // We only need to do work if any of the visible predicted apps have changed.
        if (!newPredictedApps.equals(mPredictedApps)) {
            if (newPredictedApps.size() == mPredictedApps.size()) {
                swapInNewPredictedApps(newPredictedApps);
            } else {
                // We need to update the appIndex of all the items.
                onAppsUpdated();
            }
        }
    }

    /**
     * Swaps out the old predicted apps with the new predicted apps, in place. This optimization
     * allows us to skip an entire relayout that would otherwise be called by notifyDataSetChanged.
     *
     * Note: This should only be called if the # of predicted apps is the same.
     *       This method assumes that predicted apps are the first items in the adapter.
     */
    private void swapInNewPredictedApps(List<AppInfo> apps) {
        mPredictedApps.clear();
        mPredictedApps.addAll(apps);

        int size = apps.size();
        for (int i = 0; i < size; ++i) {
            AppInfo info = apps.get(i);
            AdapterItem appItem = AdapterItem.asPredictedApp(i, "", info, i);
            appItem.rowAppIndex = i;
            mAdapterItems.set(i, appItem);
            mFilteredApps.set(i, info);
            mAdapter.notifyItemChanged(i);
        }
    }

    /**
     * Sets the current set of apps.
     */
    public void setApps(List<AppInfo> apps) {
        mComponentToAppMap.clear();
        addOrUpdateApps(apps);
    }

    /**
     * Adds or updates existing apps in the list
     */
    public void addOrUpdateApps(List<AppInfo> apps) {
        for (AppInfo app : apps) {
            mComponentToAppMap.put(app.toComponentKey(), app);
        }
        onAppsUpdated();
    }

    /**
     * Removes some apps from the list.
     */
    public void removeApps(List<AppInfo> apps) {
        for (AppInfo app : apps) {
            mComponentToAppMap.remove(app.toComponentKey());
        }
        onAppsUpdated();
    }

    /**
     * Updates internals when the set of apps are updated.
     */
    private void onAppsUpdated() {
        // Sort the list of apps
        mApps.clear();
        mApps.addAll(mComponentToAppMap.values());
        Collections.sort(mApps, mAppNameComparator);

        // As a special case for some languages (currently only Simplified Chinese), we may need to
        // coalesce sections
        Locale curLocale = mLauncher.getResources().getConfiguration().locale;
        boolean localeRequiresSectionSorting = curLocale.equals(Locale.SIMPLIFIED_CHINESE);
        if (localeRequiresSectionSorting) {
            // Compute the section headers. We use a TreeMap with the section name comparator to
            // ensure that the sections are ordered when we iterate over it later
            TreeMap<String, ArrayList<AppInfo>> sectionMap = new TreeMap<>(new LabelComparator());
            for (AppInfo info : mApps) {
                // Add the section to the cache
                String sectionName = getAndUpdateCachedSectionName(info.title);

                // Add it to the mapping
                ArrayList<AppInfo> sectionApps = sectionMap.get(sectionName);
                if (sectionApps == null) {
                    sectionApps = new ArrayList<>();
                    sectionMap.put(sectionName, sectionApps);
                }
                sectionApps.add(info);
            }

            // Add each of the section apps to the list in order
            mApps.clear();
            for (Map.Entry<String, ArrayList<AppInfo>> entry : sectionMap.entrySet()) {
                mApps.addAll(entry.getValue());
            }
        } else {
            // Just compute the section headers for use below
            for (AppInfo info : mApps) {
                // Add the section to the cache
                getAndUpdateCachedSectionName(info.title);
            }
        }

        // Recompose the set of adapter items from the current set of apps
        updateAdapterItems();
    }

    /**
     * Updates the set of filtered apps with the current filter.  At this point, we expect
     * mCachedSectionNames to have been calculated for the set of all apps in mApps.
     */
    public void updateAdapterItems() {
        refillAdapterItems();
        refreshRecyclerView();
    }

    private void refreshRecyclerView() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void refillAdapterItems() {
        String lastSectionName = null;
        FastScrollSectionInfo lastFastScrollerSectionInfo = null;
        int position = 0;
        int appIndex = 0;

        // Prepare to update the list of sections, filtered apps, etc.
        mFilteredApps.clear();
        mFastScrollerSections.clear();
        mAdapterItems.clear();

        if (DEBUG_PREDICTIONS) {
            if (mPredictedAppComponents.isEmpty() && !mApps.isEmpty()) {
                mPredictedAppComponents.add(new ComponentKeyMapper<AppInfo>(new ComponentKey(mApps.get(0).componentName,
                        Process.myUserHandle())));
                mPredictedAppComponents.add(new ComponentKeyMapper<AppInfo>(new ComponentKey(mApps.get(0).componentName,
                        Process.myUserHandle())));
                mPredictedAppComponents.add(new ComponentKeyMapper<AppInfo>(new ComponentKey(mApps.get(0).componentName,
                        Process.myUserHandle())));
                mPredictedAppComponents.add(new ComponentKeyMapper<AppInfo>(new ComponentKey(mApps.get(0).componentName,
                        Process.myUserHandle())));
            }
        }

        // Process the predicted app components
        mPredictedApps.clear();
        if (mPredictedAppComponents != null && !mPredictedAppComponents.isEmpty() && !hasFilter()) {
            mPredictedApps.addAll(processPredictedAppComponents(mPredictedAppComponents));

            if (!mPredictedApps.isEmpty()) {
                // Add a section for the predictions
                lastFastScrollerSectionInfo = new FastScrollSectionInfo("");
                mFastScrollerSections.add(lastFastScrollerSectionInfo);

                // Add the predicted app items
                for (AppInfo info : mPredictedApps) {
                    AdapterItem appItem = AdapterItem.asPredictedApp(position++, "", info,
                            appIndex++);
                    if (lastFastScrollerSectionInfo.fastScrollToItem == null) {
                        lastFastScrollerSectionInfo.fastScrollToItem = appItem;
                    }
                    mAdapterItems.add(appItem);
                    mFilteredApps.add(info);
                }

                mAdapterItems.add(AdapterItem.asPredictionDivider(position++));
            }
        }

        // Recreate the filtered and sectioned apps (for convenience for the grid layout) from the
        // ordered set of sections
        for (AppInfo info : getFiltersAppInfos()) {
            String sectionName = getAndUpdateCachedSectionName(info.title);

            // Create a new section if the section names do not match
            if (!sectionName.equals(lastSectionName)) {
                lastSectionName = sectionName;
                lastFastScrollerSectionInfo = new FastScrollSectionInfo(sectionName);
                mFastScrollerSections.add(lastFastScrollerSectionInfo);
            }

            // Create an app item
            AdapterItem appItem = AdapterItem.asApp(position++, sectionName, info, appIndex++);
            if (lastFastScrollerSectionInfo.fastScrollToItem == null) {
                lastFastScrollerSectionInfo.fastScrollToItem = appItem;
            }
            mAdapterItems.add(appItem);
            mFilteredApps.add(info);
        }

        if (hasFilter()) {
            if (isAppDiscoveryRunning() || mDiscoveredApps.size() > 0) {
                mAdapterItems.add(AdapterItem.asLoadingDivider(position++));
                // Append all app discovery results
                for (int i = 0; i < mDiscoveredApps.size(); i++) {
                    AppDiscoveryAppInfo appDiscoveryAppInfo = mDiscoveredApps.get(i);
                    if (appDiscoveryAppInfo.isRecent) {
                        // already handled in getFilteredAppInfos()
                        continue;
                    }
                    AdapterItem item = AdapterItem.asDiscoveryItem(position++,
                            "", appDiscoveryAppInfo, appIndex++);
                    mAdapterItems.add(item);
                }

                if (!isAppDiscoveryRunning()) {
                    mAdapterItems.add(AdapterItem.asMarketSearch(position++));
                }
            } else {
                // Append the search market item
                if (hasNoFilteredResults()) {
                    mAdapterItems.add(AdapterItem.asEmptySearch(position++));
                } else {
                    mAdapterItems.add(AdapterItem.asMarketDivider(position++));
                }
                mAdapterItems.add(AdapterItem.asMarketSearch(position++));
            }
        }

        if (mNumAppsPerRow != 0) {
            // Update the number of rows in the adapter after we do all the merging (otherwise, we
            // would have to shift the values again)
            int numAppsInSection = 0;
            int numAppsInRow = 0;
            int rowIndex = -1;
            for (AdapterItem item : mAdapterItems) {
                item.rowIndex = 0;
                if (AllAppsGridAdapter.isDividerViewType(item.viewType)) {
                    numAppsInSection = 0;
                } else if (AllAppsGridAdapter.isIconViewType(item.viewType)) {
                    if (numAppsInSection % mNumAppsPerRow == 0) {
                        numAppsInRow = 0;
                        rowIndex++;
                    }
                    item.rowIndex = rowIndex;
                    item.rowAppIndex = numAppsInRow;
                    numAppsInSection++;
                    numAppsInRow++;
                }
            }
            mNumAppRowsInAdapter = rowIndex + 1;

            // Pre-calculate all the fast scroller fractions
            switch (mFastScrollDistributionMode) {
                case FAST_SCROLL_FRACTION_DISTRIBUTE_BY_ROWS_FRACTION:
                    float rowFraction = 1f / mNumAppRowsInAdapter;
                    for (FastScrollSectionInfo info : mFastScrollerSections) {
                        AdapterItem item = info.fastScrollToItem;
                        if (!AllAppsGridAdapter.isIconViewType(item.viewType)) {
                            info.touchFraction = 0f;
                            continue;
                        }

                        float subRowFraction = item.rowAppIndex * (rowFraction / mNumAppsPerRow);
                        info.touchFraction = item.rowIndex * rowFraction + subRowFraction;
                    }
                    break;
                case FAST_SCROLL_FRACTION_DISTRIBUTE_BY_NUM_SECTIONS:
                    float perSectionTouchFraction = 1f / mFastScrollerSections.size();
                    float cumulativeTouchFraction = 0f;
                    for (FastScrollSectionInfo info : mFastScrollerSections) {
                        AdapterItem item = info.fastScrollToItem;
                        if (!AllAppsGridAdapter.isIconViewType(item.viewType)) {
                            info.touchFraction = 0f;
                            continue;
                        }
                        info.touchFraction = cumulativeTouchFraction;
                        cumulativeTouchFraction += perSectionTouchFraction;
                    }
                    break;
            }
        }
    }

    public boolean isAppDiscoveryRunning() {
        return mAppDiscoveryUpdateState == AppDiscoveryUpdateState.START
                || mAppDiscoveryUpdateState == AppDiscoveryUpdateState.UPDATE;
    }

    /*Modified to block flagged apps.
    Returns only when to block is false ie exact label name match.
    Only one part of search is modified as other part never showed any activity while debugging.
     */

    private List<AppInfo> getFiltersAppInfos() {
        final ArrayList<AppInfo> result = new ArrayList<>();
        AppInfo temp;
        Set<String> pnames = mContext.getSharedPreferences(mConstants.Sharedprefname, Context.MODE_PRIVATE).getStringSet(mConstants.flaggedpackagekey, new HashSet<String>());
        if (mSearchResults == null) {
            for(int x=0; x<mApps.size(); x++)
            {
                temp = mApps.get(x);
                if(!pnames.contains(temp.componentName.getPackageName()))
                {
                    result.add(temp);
                }
            }
            return result;
        }

        final LauncherAppsCompat launcherApps = LauncherAppsCompat.getInstance(mLauncher);
        final IconCache iconCache = LauncherAppState.getInstance(mLauncher).getIconCache();
        final UserManagerCompat userManagerCompat = UserManagerCompat.getInstance(mLauncher);
        for (ComponentKey key : mSearchResults) {
            AppInfo match = mComponentToAppMap.get(key);
            if (match != null) {
                if(pnames.contains(match.componentName.getPackageName())){
                    if(!myapplication.toblock)
                        result.add(match);
                }
                else
                result.add(match);
            } else {
                for (LauncherActivityInfo info : launcherApps.getActivityList(key.componentName.getPackageName(), key.user)) {
                    if (info.getComponentName().equals(key.componentName)) {
                        final AppInfo appInfo = new AppInfo(info, key.user, userManagerCompat.isQuietModeEnabled(key.user));
                        iconCache.getTitleAndIcon(appInfo, false);
                        result.add(appInfo);
                        break;
                    }
                }
            }
        }

        // adding recently used instant apps
        if (mDiscoveredApps.size() > 0) {
            for (int i = 0; i < mDiscoveredApps.size(); i++) {
                AppDiscoveryAppInfo discoveryAppInfo = mDiscoveredApps.get(i);
                if (discoveryAppInfo.isRecent) {
                    result.add(discoveryAppInfo);
                }
            }
            Collections.sort(result, mAppNameComparator);
        }
        return result;
    }

    public AppInfo findApp(ComponentKeyMapper<AppInfo> mapper) {
        return mapper.getItem(mComponentToAppMap);
    }

    /**
     * Returns the cached section name for the given title, recomputing and updating the cache if
     * the title has no cached section name.
     */
    private String getAndUpdateCachedSectionName(CharSequence title) {
        String sectionName = mCachedSectionNames.get(title);
        if (sectionName == null) {
            sectionName = mIndexer.computeSectionName(title);
            mCachedSectionNames.put(title, sectionName);
        }
        return sectionName;
    }

}
