package com.bytehamster.changelog;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.DatePicker;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.omnirom.omnichange.OmniBuildData;
import org.omnirom.omnichange.R;
import org.omnirom.omnichange.SystemProperties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class Main extends Activity {
    private static final String TAG = "OmniChange:Main";
    public static final String DEFAULT_GERRIT_URL = "https://gerrit.omnirom.org/";
    public static final String DEFAULT_BRANCH = "android-11";
    public static final String DEFAULT_VERSION = "omni-11";
    public static final int MAX_CHANGES_FETCH = 800;  // Max changes to be fetched
    public static final int MAX_CHANGES_DB = 1500; // Max changes to be loaded from DB
    public static final String EMPTY_DEVICE_LIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><devicesList></devicesList>";
    private static final String EXTRA_SINCE_CURRENT = "since_current";

    public static final SimpleDateFormat mDateFormatFilter = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    public static final DateFormat mDateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    public static final DateFormat mDateDayFormat = DateFormat.getDateInstance(DateFormat.SHORT);


    private final ArrayList<Map<String, Object>> mChangesList = new ArrayList<Map<String, Object>>();
    private final ArrayList<Map<String, Object>> mDevicesList = new ArrayList<Map<String, Object>>();
    private final Map<String, Map<String, Object>> mDevicesMap = new HashMap<String, Map<String, Object>>();
    private final Map<String, Map<String, Object>> mWatchedMap = new HashMap<String, Map<String, Object>>();
    private final HashMap<Long, Map<String, Object>> mChangesDict = new HashMap<>();

    private ListView mListView = null;
    private Activity mActivity = null;
    private SharedPreferences mSharedPreferences = null;
    private String mDeviceFilterKeyword;
    private boolean mIsLoading = false;
    private boolean mJustStarted = true;
    private Document mWatchedDoc = null;
    private ChangeAdapter mChangeAdapter = null;
    private int mChangesCount = 0;
    private String GERRIT_URL = "https://gerrit.omnirom.org/";
    private TextView mNumItems;
    private TextView mStartDate;
    private List<Long> mWeeklyBuilds;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        getActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE);
        getActionBar().setElevation(0);

        mActivity = this;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        GERRIT_URL = mSharedPreferences.getString("server_url", DEFAULT_GERRIT_URL);
        mChangeAdapter = new ChangeAdapter(mActivity, mChangesList, GERRIT_URL);
        mListView = (ListView) findViewById(android.R.id.list);

        mListView.setAdapter(mChangeAdapter);
        mListView.setOnItemClickListener(MainListClickListener);
        //mListView.setOnItemLongClickListener(MainListLongClickListener);
        /*mListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                int topRowVerticalPosition =
                        (mListView == null || mListView.getChildCount() == 0) ?
                                0 : mListView.getChildAt(0).getTop();
            }
        });*/

        if (mSharedPreferences.getString("branch", DEFAULT_BRANCH).equals("All")) {
            mSharedPreferences.edit().putString("branch", "").commit();
        }
        Intent i = getIntent();
        if (i != null && i.hasExtra(EXTRA_SINCE_CURRENT)) {
            mSharedPreferences.edit().putLong("start_time", getUnifiedBuildTime()).commit();
        }
        mStartDate = findViewById(R.id.start_time);
        mNumItems = findViewById(R.id.num_items);

        TextView buildDate = findViewById(R.id.build_time);
        buildDate.setText(mDateFormat.format(Build.TIME));

        loadDeviceMap();
        if (mSharedPreferences.getString("watched_devices", null) == null) {
            setDefaultDeviceFilter();
        }

        load();
        checkAlerts();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_SINCE_CURRENT)) {
            mSharedPreferences.edit().putLong("start_time", getUnifiedBuildTime()).commit();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mJustStarted) {
            mJustStarted = false;
        } else {
            load();
        }
    }

    private void checkAlerts() {
    }

    public void load() {

        if (mIsLoading) return;
        mIsLoading = true;
        final long startTime = mSharedPreferences.getLong("start_time", getUnifiedBuildTime());
        final boolean endTimeFilter = mSharedPreferences.getBoolean("build_time", false);
        mStartDate.setText(mDateDayFormat.format(startTime));

        new Thread() {
            public void run() {

                if (!mChangesList.isEmpty()) mChangesList.clear();

                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mChangeAdapter.clear();
                        findViewById(R.id.progress).setVisibility(View.VISIBLE);
                    }
                });

                if (mWeeklyBuilds == null) {
                    mWeeklyBuilds = OmniBuildData.getWeeklyBuildTimes();
                    Log.d(TAG, "getWeeklyBuildTimes " + mWeeklyBuilds);
                }

                ChangeLoader loader = new ChangeLoader(mActivity, mSharedPreferences, GERRIT_URL);
                ChangeFilter filter = new ChangeFilter(mSharedPreferences);

                List<Change> changes;
                try {
                    changes = loader.loadAll();
                } catch (ChangeLoader.LoadException e) {
                    Dialogs.usingCacheAlert(mActivity, GERRIT_URL, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            load();
                        }
                    });
                    changes = loader.getCached();
                }

                mChangesCount = 0;
                long buildTime = Build.TIME;

                String buildTimeString = getResources().getString(R.string.build_time_label) + " " + Main.mDateFormat.format(buildTime);
                List<Long> weeklyBuilds = null;

                if (mWeeklyBuilds != null) {
                    weeklyBuilds = new CopyOnWriteArrayList<>(mWeeklyBuilds);
                    // same day as latest weekly - dont add this weekly entry
                    if (weeklyBuilds.size() > 0 && weeklyBuilds.get(0) == getUnifiedBuildTime()) {
                        weeklyBuilds.remove(0);
                    }
                }
                // add changes
                int change_size = changes.size();
                for (int i = 0; i < change_size; i++) {
                    Change currentChange = changes.get(i);
                    if (filter.isHidden(currentChange)) {
                        continue;
                    }

                    HashMap<String, Object> changeEntry = currentChange.getHashMap(mActivity);
                    Long changeTime = (Long) changeEntry.get("time");
                    mChangesDict.put(changeTime, changeEntry);

                    // add one header per new day
                    long dayHeaderTime = getDayHeaderTime(changeTime);
                    if (!mChangesDict.containsKey(dayHeaderTime)) {
                        Map<String, Object> dayItem = new HashMap<String, Object>();
                        dayItem.put("title", currentChange.dateDay);
                        dayItem.put("type", Change.TYPE_HEADER);
                        mChangesDict.put(dayHeaderTime, dayItem);
                    }

                    mChangesCount++;
                }
                // add this build time
                Map<String, Object> builtEntry = new HashMap<String, Object>();
                builtEntry.put("title", buildTimeString);
                builtEntry.put("type", Change.TYPE_BUILD);
                mChangesDict.put(buildTime, builtEntry);

                // add weekly builds
                if (weeklyBuilds != null) {
                    int j = 0;
                    for (Long weeklyTime : weeklyBuilds) {
                        if (weeklyTime < startTime) {
                            continue;
                        }
                        Map<String, Object> weeklyEntry = new HashMap<String, Object>();
                        weeklyEntry.put("title", getResources().getString(R.string.weekly_time_label) +
                                " " + Main.mDateFormat.format(weeklyTime));
                        weeklyEntry.put("type", Change.TYPE_WEEKLY);
                        weeklyEntry.put("time", weeklyTime);
                        mChangesDict.put(weeklyTime, weeklyEntry);
                    }
                }

                // now sort based on key
                List<Long> changeTimeList = new ArrayList<>(mChangesDict.keySet());
                Collections.sort(changeTimeList, Collections.reverseOrder());
                for (Long changeTime : changeTimeList){
                    if (endTimeFilter && changeTime > buildTime) {
                        continue;
                    }
                    Map<String, Object> currentChange = mChangesDict.get(changeTime);
                    mChangesList.add(currentChange);
                }

                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hideProgress();
                        mChangeAdapter.update(mChangesList);
                        mNumItems.setText(String.valueOf(mChangesCount));
                        mIsLoading = false;
                    }
                });
            }
        }.start();
    }

    void hideProgress() {
        findViewById(R.id.progress).setVisibility(View.GONE);
        if (mChangesList.isEmpty()) {
            findViewById(android.R.id.list).setVisibility(View.GONE);
            findViewById(android.R.id.empty).setVisibility(View.VISIBLE);
        } else {
            findViewById(android.R.id.empty).setVisibility(View.GONE);
            findViewById(android.R.id.list).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_refresh:
                load();
                return true;
            case R.id.action_filter:
                filter();
                return true;
            case R.id.action_settings:
                Intent i = new Intent(this, Preferences.class);
                startActivity(i);
                return true;
            case R.id.action_since:
                doSelectStartTime(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    void filter() {
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setCancelable(true);
        b.setTitle(R.string.filter);
        final View root = View.inflate(this, R.layout.dialog_filter, null);
        b.setView(root);

        b.setNeutralButton(R.string.add_device, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                add_device();
            }
        });

        b.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                load();
            }
        });

        final AlertDialog d = b.create();

        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                final Button neutralButton = d.getButton(DialogInterface.BUTTON_NEUTRAL);
                if (mSharedPreferences.getBoolean("display_all", false)) {
                    neutralButton.setEnabled(false);
                } else {
                    neutralButton.setEnabled(true);
                }
            }
        });
        ((CheckBox) root.findViewById(R.id.all_devices)).setChecked(mSharedPreferences.getBoolean("display_all", false));
        ((CheckBox) root.findViewById(R.id.translations)).setChecked(mSharedPreferences.getBoolean("translations", false));
        ((CheckBox) root.findViewById(R.id.show_twrp)).setChecked(mSharedPreferences.getBoolean("show_twrp", false));
        ((CheckBox) root.findViewById(R.id.build_time)).setChecked(mSharedPreferences.getBoolean("build_time", false));

        ((CheckBox) root.findViewById(R.id.translations)).setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSharedPreferences.edit().putBoolean("translations", isChecked).apply();
            }
        });
        ((CheckBox) root.findViewById(R.id.show_twrp)).setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSharedPreferences.edit().putBoolean("show_twrp", isChecked).apply();
            }
        });
        ((CheckBox) root.findViewById(R.id.all_devices)).setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                final Button neutralButton = d.getButton(DialogInterface.BUTTON_NEUTRAL);
                if (isChecked) {
                    mSharedPreferences.edit().putBoolean("display_all", true).apply();
                    neutralButton.setEnabled(false);
                } else {
                    mSharedPreferences.edit().putBoolean("display_all", false).apply();
                    neutralButton.setEnabled(true);
                }
            }
        });
        ((CheckBox) root.findViewById(R.id.build_time)).setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSharedPreferences.edit().putBoolean("build_time", isChecked).apply();
            }
        });
        d.show();
    }

    void add_device() {
        mDeviceFilterKeyword = null;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setCancelable(true);
        b.setTitle(R.string.device_list);
        b.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                load();
            }
        });
        final View root = View.inflate(this, R.layout.dialog_add_device, null);
        b.setView(root);

        final Dialog d = b.create();
        d.show();

        load_all_device_list(((ListView) root.findViewById(R.id.devices_listview)));
    }

    void load_all_device_list(final ListView listView) {
        mDevicesList.clear();
        loadWatchedDeviceList();
        if (mDeviceFilterKeyword == null) {
            mDevicesList.addAll(mDevicesMap.values());
        } else {
            for (String device : mDevicesMap.keySet()) {
                Map<String, Object> AddItemMap = mDevicesMap.get(device);
                if (device.toLowerCase(Locale.getDefault()).contains(mDeviceFilterKeyword.toLowerCase(Locale.getDefault()))) {
                    mDevicesList.add(AddItemMap);
                }
            }
        }
        Collections.sort(mDevicesList, new sortComparator());

        final BaseAdapter deviceListAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mDevicesList.size();
            }

            @Override
            public Object getItem(int position) {
                return mDevicesList.get(position);
            }

            @Override
            public long getItemId(int position) {
                return 0;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                convertView = getLayoutInflater().inflate(R.layout.list_entry_device, null, false);
                TextView name = convertView.findViewById(R.id.name);
                TextView code = convertView.findViewById(R.id.code);
                CheckBox watched = convertView.findViewById(R.id.watched);
                final Map<String, Object> deviceEntry = mDevicesList.get(position);

                name.setText((String) deviceEntry.get("name"));
                final String deviceName = (String) deviceEntry.get("code");
                code.setText(deviceName);
                watched.setChecked(mWatchedMap.keySet().contains(deviceName));
                watched.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (!isChecked) {
                            mWatchedDoc.getDocumentElement().removeChild((Element) mWatchedMap.get(deviceName).get("device_element"));
                        } else {
                            Node new_node = mWatchedDoc.importNode((Element) deviceEntry.get("device_element"), true);
                            mWatchedDoc.getDocumentElement().appendChild(new_node);
                        }
                        String watchedDevices = StringTools.XmlToString(mActivity, mWatchedDoc);
                        mSharedPreferences.edit().putString("watched_devices", watchedDevices).apply();
                        loadWatchedDeviceList();
                    }
                });
                return convertView;
            }
        };
        listView.setAdapter(deviceListAdapter);
    }

    private final AdapterView.OnItemLongClickListener MainListLongClickListener = new AdapterView.OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
            if ((Integer) mChangesList.get(position).get("type") == Change.TYPE_ITEM) {
                AlertDialog.Builder d = new AlertDialog.Builder(mActivity);
                d.setCancelable(true);
                d.setTitle(R.string.change);
                d.setMessage((String) mChangesList.get(position).get("title"));
                d.setNegativeButton(R.string.cancel, null);
                d.setPositiveButton("Gerrit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Uri uri = Uri.parse(GERRIT_URL + "#/c/" + mChangesList.get(position).get("number"));
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(intent);
                    }
                });
                d.show();
            }

            return true;
        }
    };

    private final AdapterView.OnItemClickListener MainListClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
            if ((Integer) mChangesList.get(position).get("type") == Change.TYPE_ITEM) {
                final TextView info = (TextView) view.findViewById(R.id.info);
                final View buttons = view.findViewById(R.id.buttons);

                if (info.getVisibility() == View.GONE) {
                    info.setVisibility(View.VISIBLE);
                    buttons.setVisibility(View.VISIBLE);
                    mChangesList.get(position).put("visibility", View.VISIBLE);
                } else {
                    info.setVisibility(View.GONE);
                    buttons.setVisibility(View.GONE);
                    mChangesList.get(position).put("visibility", View.GONE);
                }
            }
        }
    };

    private class sortComparator implements Comparator<Map<String, Object>> {
        @Override
        public int compare(Map<String, Object> m1, Map<String, Object> m2) {
            return ((String) m1.get("name")).compareToIgnoreCase((String) m2.get("name"));
        }
    }

    public static String getDefaultDevice() {
        return SystemProperties.get("ro.omni.device");
    }

    public static String getDefaultVersion() {
        return SystemProperties.get("ro.omni.version");
    }

    private void setDefaultDeviceFilter() {
        String device = getDefaultDevice();
        try {
            loadDeviceMap();
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db;
            db = dbf.newDocumentBuilder();
            InputSource is = new InputSource();
            is.setCharacterStream(new StringReader(EMPTY_DEVICE_LIST));
            Document doc = db.parse(is);
            doc.getDocumentElement().normalize();

            Node new_node = doc.importNode((Element) mDevicesMap.get(device).get("device_element"), true);
            doc.getDocumentElement().appendChild(new_node);
            String defaultDevice = StringTools.XmlToString(mActivity, doc);
            mSharedPreferences.edit().putString("watched_devices", defaultDevice).apply();
            Log.d(TAG, "setDefaultDeviceFilter = " + defaultDevice);
        } catch (Exception e) {
        }
    }

    private void doSelectStartTime(final Runnable r) {
        final long startTime = mSharedPreferences.getLong("start_time", getUnifiedBuildTime());

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startTime);
        int day = c.get(Calendar.DAY_OF_MONTH);
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(Main.this,
                new DatePickerDialog.OnDateSetListener() {

                    @Override
                    public void onDateSet(DatePicker view, int year,
                                          int monthOfYear, int dayOfMonth) {
                        Calendar c = Calendar.getInstance();
                        c.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        c.set(Calendar.YEAR, year);
                        c.set(Calendar.MONTH, monthOfYear);
                        c.set(Calendar.HOUR_OF_DAY, 0);
                        c.set(Calendar.MINUTE, 0);

                        mSharedPreferences.edit().putLong("start_time", c.getTimeInMillis()).commit();
                        r.run();
                    }
                }, year, month, day);
        datePickerDialog.show();
    }

    private void loadDeviceMap() {
        mDevicesMap.clear();

        HashMap<String, Object> AddItemMap;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db;
        Document doc = null;
        try {
            db = dbf.newDocumentBuilder();
            doc = db.parse(getAssets().open("projects.xml"));
            doc.getDocumentElement().normalize();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(mActivity, e.getMessage(), Toast.LENGTH_LONG).show();
        }

        if (doc != null) {

            NodeList oemList = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < oemList.getLength(); i++) {
                if (oemList.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
                Element oem = (Element) oemList.item(i);

                String oemName = oem.getAttribute("name");
                NodeList deviceList = oem.getChildNodes();
                for (int j = 0; j < deviceList.getLength(); j++) {
                    if (deviceList.item(j).getNodeType() != Node.ELEMENT_NODE) continue;

                    Element device = (Element) deviceList.item(j);
                    NodeList properties = device.getChildNodes();
                    AddItemMap = new HashMap<String, Object>();
                    AddItemMap.put("device_element", device);
                    String deviceName = null;
                    for (int k = 0; k < properties.getLength(); k++) {
                        if (properties.item(k).getNodeType() != Node.ELEMENT_NODE) continue;
                        Element property = (Element) properties.item(k);

                        if (property.getNodeName().equals("name")) {
                            AddItemMap.put("name", property.getTextContent());
                        }
                        if (property.getNodeName().equals("code")) {
                            deviceName = property.getTextContent();
                            AddItemMap.put("code", deviceName);
                        }
                    }
                    if (deviceName != null) {
                        mDevicesMap.put(deviceName, AddItemMap);
                    }
                }
            }
        }
    }

    void loadWatchedDeviceList() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db;

            db = dbf.newDocumentBuilder();
            InputSource is = new InputSource();
            String deviceListString = mSharedPreferences.getString("watched_devices", EMPTY_DEVICE_LIST);
            is.setCharacterStream(new StringReader(deviceListString));
            mWatchedDoc = db.parse(is);
            mWatchedDoc.getDocumentElement().normalize();

            mWatchedMap.clear();

            HashMap<String, Object> AddItemMap;

            NodeList devicesList = mWatchedDoc.getDocumentElement().getChildNodes();
            for (int i = 0; i < devicesList.getLength(); i++) {
                if (devicesList.item(i).getNodeType() != Node.ELEMENT_NODE) continue;

                Element device = (Element) devicesList.item(i);
                NodeList properties = device.getChildNodes();
                AddItemMap = new HashMap<String, Object>();
                AddItemMap.put("device_element", device);
                String deviceName = null;

                for (int k = 0; k < properties.getLength(); k++) {
                    if (properties.item(k).getNodeType() != Node.ELEMENT_NODE) continue;
                    Element property = (Element) properties.item(k);

                    if (property.getNodeName().equals("code")) {
                        deviceName = property.getTextContent();
                    }
                }
                if (deviceName != null) {
                    mWatchedMap.put(deviceName, AddItemMap);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static long getUnifiedBuildTime() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Build.TIME);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        return c.getTimeInMillis();
    }

    public static long getDayHeaderTime(Long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        return c.getTimeInMillis();
    }
}
