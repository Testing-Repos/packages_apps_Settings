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

package com.android.settings.applications;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import com.android.internal.app.IProcessStats;
import com.android.internal.app.ProcessStats;
import com.android.settings.R;
import com.android.settings.fuelgauge.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class ProcessStatsUi extends PreferenceFragment {
    private static final String TAG = "ProcessStatsUi";
    private static final boolean DEBUG = false;

    private static final String KEY_APP_LIST = "app_list";
    private static final String KEY_MEM_STATUS = "mem_status";

    private static final int MENU_STATS_REFRESH = Menu.FIRST;
    private static final int MENU_SHOW_SYSTEM = Menu.FIRST + 1;
    private static final int MENU_USE_USS = Menu.FIRST + 2;
    private static final int MENU_TYPE_BACKGROUND = Menu.FIRST + 3;
    private static final int MENU_TYPE_FOREGROUND = Menu.FIRST + 4;
    private static final int MENU_TYPE_CACHED = Menu.FIRST + 5;
    private static final int MENU_HELP = Menu.FIRST + 6;

    static final int MAX_ITEMS_TO_LIST = 40;

    final static Comparator<ProcStatsEntry> sEntryCompare = new Comparator<ProcStatsEntry>() {
        @Override
        public int compare(ProcStatsEntry lhs, ProcStatsEntry rhs) {
            if (lhs.mWeight < rhs.mWeight) {
                return 1;
            } else if (lhs.mWeight > rhs.mWeight) {
                return -1;
            }
            return 0;
        }
    };

    private static ProcessStats sStatsXfer;

    IProcessStats mProcessStats;
    UserManager mUm;
    ProcessStats mStats;
    int mMemState;

    private boolean mShowSystem;
    private boolean mUseUss;
    private int mStatsType;

    private MenuItem mShowSystemMenu;
    private MenuItem mUseUssMenu;
    private MenuItem mTypeBackgroundMenu;
    private MenuItem mTypeForegroundMenu;
    private MenuItem mTypeCachedMenu;

    private PreferenceGroup mAppListGroup;
    private Preference mMemStatusPref;

    long mMaxWeight;
    long mTotalTime;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (icicle != null) {
            mStats = sStatsXfer;
        }

        addPreferencesFromResource(R.xml.process_stats_summary);
        mProcessStats = IProcessStats.Stub.asInterface(
                ServiceManager.getService(ProcessStats.SERVICE_NAME));
        mUm = (UserManager)getActivity().getSystemService(Context.USER_SERVICE);
        mAppListGroup = (PreferenceGroup) findPreference(KEY_APP_LIST);
        mMemStatusPref = mAppListGroup.findPreference(KEY_MEM_STATUS);
        mShowSystem = icicle != null ? icicle.getBoolean("show_system") : false;
        mUseUss = icicle != null ? icicle.getBoolean("use_uss") : false;
        mStatsType = icicle != null ? icicle.getInt("stats_type", MENU_TYPE_BACKGROUND)
                : MENU_TYPE_BACKGROUND;
        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStats();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("show_system", mShowSystem);
        outState.putBoolean("use_uss", mUseUss);
        outState.putInt("stats_type", mStatsType);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (getActivity().isChangingConfigurations()) {
            sStatsXfer = mStats;
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (!(preference instanceof ProcessStatsPreference)) {
            return false;
        }

        ProcessStatsPreference pgp = (ProcessStatsPreference) preference;
        Bundle args = new Bundle();
        args.putParcelable(ProcessStatsDetail.EXTRA_ENTRY, pgp.getEntry());
        args.putBoolean(ProcessStatsDetail.EXTRA_USE_USS, mUseUss);
        args.putLong(ProcessStatsDetail.EXTRA_MAX_WEIGHT, mMaxWeight);
        args.putLong(ProcessStatsDetail.EXTRA_TOTAL_TIME, mTotalTime);
        ((PreferenceActivity) getActivity()).startPreferencePanel(
                ProcessStatsDetail.class.getName(), args, R.string.details_title, null, null, 0);

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem refresh = menu.add(0, MENU_STATS_REFRESH, 0, R.string.menu_stats_refresh)
                .setIcon(R.drawable.ic_menu_refresh_holo_dark)
                .setAlphabeticShortcut('r');
        refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM |
                MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        mShowSystemMenu = menu.add(0, MENU_SHOW_SYSTEM, 0, R.string.menu_show_system)
                .setAlphabeticShortcut('s')
                .setCheckable(true)
                .setChecked(mShowSystem)
                .setEnabled(mStatsType == MENU_TYPE_BACKGROUND);
        mUseUssMenu = menu.add(0, MENU_USE_USS, 0, R.string.menu_use_uss)
                .setAlphabeticShortcut('s')
                .setCheckable(true)
                .setChecked(mUseUss);
        SubMenu subMenu = menu.addSubMenu(R.string.menu_proc_stats_type);
        mTypeBackgroundMenu = subMenu.add(0, MENU_TYPE_BACKGROUND, 0,
                R.string.menu_proc_stats_type_background)
                .setAlphabeticShortcut('b')
                .setCheckable(true)
                .setChecked(mStatsType == MENU_TYPE_BACKGROUND);
        mTypeForegroundMenu = subMenu.add(0, MENU_TYPE_FOREGROUND, 0,
                R.string.menu_proc_stats_type_foreground)
                .setAlphabeticShortcut('f')
                .setCheckable(true)
                .setChecked(mStatsType == MENU_TYPE_FOREGROUND);
        mTypeCachedMenu = subMenu.add(0, MENU_TYPE_CACHED, 0,
                R.string.menu_proc_stats_type_cached)
                .setAlphabeticShortcut('c')
                .setCheckable(true)
                .setChecked(mStatsType == MENU_TYPE_CACHED);

        /*
        String helpUrl;
        if (!TextUtils.isEmpty(helpUrl = getResources().getString(R.string.help_url_battery))) {
            final MenuItem help = menu.add(0, MENU_HELP, 0, R.string.help_label);
            HelpUtils.prepareHelpMenuItem(getActivity(), help, helpUrl);
        }
        */
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_STATS_REFRESH:
                mStats = null;
                refreshStats();
                return true;
            case MENU_SHOW_SYSTEM:
                mShowSystem = !mShowSystem;
                refreshStats();
                return true;
            case MENU_USE_USS:
                mUseUss = !mUseUss;
                refreshStats();
                return true;
            case MENU_TYPE_BACKGROUND:
            case MENU_TYPE_FOREGROUND:
            case MENU_TYPE_CACHED:
                mStatsType = item.getItemId();
                refreshStats();
                return true;
            default:
                return false;
        }
    }

    private void addNotAvailableMessage() {
        Preference notAvailable = new Preference(getActivity());
        notAvailable.setTitle(R.string.power_usage_not_available);
        mAppListGroup.addPreference(notAvailable);
    }

    public static final int[] BACKGROUND_AND_SYSTEM_PROC_STATES = new int[] {
            ProcessStats.STATE_PERSISTENT, ProcessStats.STATE_IMPORTANT_FOREGROUND,
            ProcessStats.STATE_IMPORTANT_BACKGROUND, ProcessStats.STATE_BACKUP,
            ProcessStats.STATE_HEAVY_WEIGHT, ProcessStats.STATE_SERVICE,
            ProcessStats.STATE_SERVICE_RESTARTING, ProcessStats.STATE_RECEIVER
    };

    public static final int[] FOREGROUND_PROC_STATES = new int[] {
            ProcessStats.STATE_TOP
    };

    public static final int[] CACHED_PROC_STATES = new int[] {
            ProcessStats.STATE_CACHED_ACTIVITY, ProcessStats.STATE_CACHED_ACTIVITY_CLIENT,
            ProcessStats.STATE_CACHED_EMPTY
    };

    private void refreshStats() {
        if (mStats == null) {
            load();
        }

        if (mShowSystemMenu != null) {
            mShowSystemMenu.setChecked(mShowSystem);
            mShowSystemMenu.setEnabled(mStatsType == MENU_TYPE_BACKGROUND);
        }
        if (mUseUssMenu != null) {
            mUseUssMenu.setChecked(mUseUss);
        }
        if (mTypeBackgroundMenu != null) {
            mTypeBackgroundMenu.setChecked(mStatsType == MENU_TYPE_BACKGROUND);
        }
        if (mTypeForegroundMenu != null) {
            mTypeForegroundMenu.setChecked(mStatsType == MENU_TYPE_FOREGROUND);
        }
        if (mTypeCachedMenu != null) {
            mTypeCachedMenu.setChecked(mStatsType == MENU_TYPE_CACHED);
        }

        int[] stats;
        int statsLabel;
        if (mStatsType == MENU_TYPE_FOREGROUND) {
            stats = FOREGROUND_PROC_STATES;
            statsLabel = R.string.process_stats_type_foreground;
        } else if (mStatsType == MENU_TYPE_CACHED) {
            stats = CACHED_PROC_STATES;
            statsLabel = R.string.process_stats_type_cached;
        } else {
            stats = mShowSystem ? BACKGROUND_AND_SYSTEM_PROC_STATES
                    : ProcessStats.BACKGROUND_PROC_STATES;
            statsLabel = R.string.process_stats_type_background;
        }

        mAppListGroup.removeAll();
        mAppListGroup.setOrderingAsAdded(false);

        mMemStatusPref.setOrder(-2);
        mAppListGroup.addPreference(mMemStatusPref);
        String durationString = Utils.formatElapsedTime(getActivity(),
                mStats.mTimePeriodEndRealtime-mStats.mTimePeriodStartRealtime, false);
        CharSequence memString;
        CharSequence[] memStates = getResources().getTextArray(R.array.ram_states);
        if (mMemState >= 0 && mMemState < memStates.length) {
            memString = memStates[mMemState];
        } else {
            memString = "?";
        }
        mMemStatusPref.setTitle(getActivity().getString(R.string.process_stats_total_duration,
                getActivity().getString(statsLabel), durationString));
        mMemStatusPref.setSummary(getActivity().getString(R.string.process_stats_memory_status,
                        memString));
        /*
        mMemStatusPref.setTitle(DateFormat.format(DateFormat.getBestDateTimePattern(
                getActivity().getResources().getConfiguration().locale,
                "MMMM dd, yyyy h:mm a"), mStats.mTimePeriodStartClock));
        */
        /*
        BatteryHistoryPreference hist = new BatteryHistoryPreference(getActivity(), mStats);
        hist.setOrder(-1);
        mAppListGroup.addPreference(hist);
        */

        ProcessStats.ProcessDataCollection totals = new ProcessStats.ProcessDataCollection(
                ProcessStats.ALL_SCREEN_ADJ, ProcessStats.ALL_MEM_ADJ, stats);

        long now = SystemClock.uptimeMillis();

        final PackageManager pm = getActivity().getPackageManager();

        mTotalTime = ProcessStats.dumpSingleTime(null, null, mStats.mMemFactorDurations,
                mStats.mMemFactor, mStats.mStartTime, now);

        LinearColorPreference colors = new LinearColorPreference(getActivity());
        colors.setOrder(-1);
        mAppListGroup.addPreference(colors);

        long[] memTimes = new long[ProcessStats.ADJ_MEM_FACTOR_COUNT];
        for (int iscreen=0; iscreen<ProcessStats.ADJ_COUNT; iscreen+=ProcessStats.ADJ_SCREEN_MOD) {
            for (int imem=0; imem<ProcessStats.ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                memTimes[imem] += mStats.mMemFactorDurations[state];
            }
        }

        colors.setRatios(memTimes[ProcessStats.ADJ_MEM_FACTOR_CRITICAL] / (float)mTotalTime,
                (memTimes[ProcessStats.ADJ_MEM_FACTOR_LOW]
                        + memTimes[ProcessStats.ADJ_MEM_FACTOR_MODERATE]) / (float)mTotalTime,
                memTimes[ProcessStats.ADJ_MEM_FACTOR_NORMAL] / (float)mTotalTime);

        ArrayList<ProcStatsEntry> procs = new ArrayList<ProcStatsEntry>();

        /*
        ArrayList<ProcessStats.ProcessState> rawProcs = mStats.collectProcessesLocked(
                ProcessStats.ALL_SCREEN_ADJ, ProcessStats.ALL_MEM_ADJ,
                ProcessStats.BACKGROUND_PROC_STATES, now, null);
        for (int i=0, N=(rawProcs != null ? rawProcs.size() : 0); i<N; i++) {
            procs.add(new ProcStatsEntry(rawProcs.get(i), totals));
        }
        */

        ArrayMap<String, ProcStatsEntry> processes = new ArrayMap<String, ProcStatsEntry>(
                mStats.mProcesses.getMap().size());
        for (int ip=0, N=mStats.mProcesses.getMap().size(); ip<N; ip++) {
            SparseArray<ProcessStats.ProcessState> uids = mStats.mProcesses.getMap().valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                ProcStatsEntry ent = new ProcStatsEntry(uids.valueAt(iu), totals, mUseUss,
                        mStatsType == MENU_TYPE_BACKGROUND);
                procs.add(ent);
                processes.put(ent.mName, ent);
            }
        }

        Collections.sort(procs, sEntryCompare);
        while (procs.size() > MAX_ITEMS_TO_LIST) {
            procs.remove(procs.size()-1);
        }

        long maxWeight = 0;
        for (int i=0, N=(procs != null ? procs.size() : 0); i<N; i++) {
            ProcStatsEntry proc = procs.get(i);
            if (maxWeight < proc.mWeight) {
                maxWeight = proc.mWeight;
            }
        }
        mMaxWeight = maxWeight;

        for (int i=0, N=(procs != null ? procs.size() : 0); i<N; i++) {
            ProcStatsEntry proc = procs.get(i);
            final double percentOfWeight = (((double)proc.mWeight) / maxWeight) * 100;
            final double percentOfTime = (((double)proc.mDuration) / mTotalTime) * 100;
            if (percentOfWeight < 2) break;
            ProcessStatsPreference pref = new ProcessStatsPreference(getActivity(), null, proc);
            proc.evaluateTargetPackage(mStats, totals, sEntryCompare, mUseUss,
                    mStatsType == MENU_TYPE_BACKGROUND);
            proc.retrieveUiData(pm);
            pref.setTitle(proc.mUiLabel);
            if (proc.mUiTargetApp != null) {
                pref.setIcon(proc.mUiTargetApp.loadIcon(pm));
            }
            pref.setOrder(i);
            pref.setPercent(percentOfWeight, percentOfTime);
            mAppListGroup.addPreference(pref);
            if (mAppListGroup.getPreferenceCount() > (MAX_ITEMS_TO_LIST+1)) break;
        }

        // Add in service info.
        if (mStatsType == MENU_TYPE_BACKGROUND) {
            for (int ip=0, N=mStats.mPackages.getMap().size(); ip<N; ip++) {
                SparseArray<ProcessStats.PackageState> uids = mStats.mPackages.getMap().valueAt(ip);
                for (int iu=0; iu<uids.size(); iu++) {
                    ProcessStats.PackageState ps = uids.valueAt(iu);
                    for (int is=0, NS=ps.mServices.size(); is<NS; is++) {
                        ProcessStats.ServiceState ss = ps.mServices.valueAt(is);
                        if (ss.mProcessName != null) {
                            ProcStatsEntry ent = processes.get(ss.mProcessName);
                            ent.addService(ss);
                        }
                    }
                }
            }
        }
    }

    private void load() {
        try {
            mMemState = mProcessStats.getCurrentMemoryState();
            ArrayList<ParcelFileDescriptor> fds = new ArrayList<ParcelFileDescriptor>();
            byte[] data = mProcessStats.getCurrentStats(fds);
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            mStats = ProcessStats.CREATOR.createFromParcel(parcel);
            int i = fds.size()-1;
            while (i >= 0 && (mStats.mTimePeriodEndRealtime-mStats.mTimePeriodStartRealtime)
                    < (24*60*60*1000)) {
                Log.i(TAG, "Not enough data, loading next file @ " + i);
                ProcessStats stats = new ProcessStats(false);
                InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fds.get(i));
                stats.read(stream);
                try {
                    stream.close();
                } catch (IOException e) {
                }
                if (stats.mReadError == null) {
                    mStats.add(stats);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Added stats: ");
                    sb.append(stats.mTimePeriodStartClockStr);
                    sb.append(", over ");
                    TimeUtils.formatDuration(
                            stats.mTimePeriodEndRealtime-stats.mTimePeriodStartRealtime, sb);
                    Log.i(TAG, sb.toString());
                } else {
                    Log.w(TAG, "Read error: " + stats.mReadError);
                }
                i--;
            }
            while (i >= 0) {
                try {
                    fds.get(i).close();
                } catch (IOException e) {
                }
                i--;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:", e);
        }
    }
}
