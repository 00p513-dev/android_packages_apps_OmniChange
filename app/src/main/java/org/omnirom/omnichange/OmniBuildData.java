package org.omnirom.omnichange;


import android.util.Log;

import com.bytehamster.changelog.Main;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public class OmniBuildData {
    private static final String TAG = "OmniChange:OmniBuildData";
    private static final int HTTP_READ_TIMEOUT = 30000;
    private static final int HTTP_CONNECTION_TIMEOUT = 30000;
    private static final String URL_BASE_JSON = "https://dl.omnirom.org/json.php";
    private static final String URL_BASE_GAPPS_JSON = "https://dl.omnirom.org/tmp/json.php";
    private static final String GAPPS_VERSION_TAG = "GAPPS";
    private static final String WEEKLY_VERSION_TAG = "WEEKLY";

    private static HttpsURLConnection setupHttpsRequest(String urlStr){
        URL url;
        HttpsURLConnection urlConnection = null;
        try {
            url = new URL(urlStr);
            urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(HTTP_CONNECTION_TIMEOUT);
            urlConnection.setReadTimeout(HTTP_READ_TIMEOUT);
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);
            urlConnection.connect();
            int code = urlConnection.getResponseCode();
            if (code != HttpsURLConnection.HTTP_OK) {
                Log.d(TAG, "response: " + code);
                return null;
            }
            return urlConnection;
        } catch (Exception e) {
            Log.d(TAG, "Failed to connect to server");
            return null;
        }
    }

    private static String downloadUrlMemoryAsString(String url) {
        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if(urlConnection == null){
                return null;
            }

            InputStream is = urlConnection.getInputStream();
            ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
            int byteInt;

            while((byteInt = is.read()) >= 0){
                byteArray.write(byteInt);
            }

            byte[] bytes = byteArray.toByteArray();
            if(bytes == null){
                return null;
            }
            String responseBody = new String(bytes, StandardCharsets.UTF_8);

            return responseBody;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Log.e(TAG, "downloadUrlMemoryAsString", e);
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private static boolean isMatchingImage(String fileName) {
        try {
            if(fileName.endsWith(".zip") && fileName.contains(Main.getDefaultDevice())) {
                if(fileName.contains(Main.DEFAULT_VERSION) &&
                        (fileName.contains(WEEKLY_VERSION_TAG) || fileName.contains(GAPPS_VERSION_TAG))) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isMatchingImage", e);
        }
        return false;
    }

    public static List<Long> getWeeklyBuildTimes() {
        String url = isGappsDevice() ? URL_BASE_GAPPS_JSON : URL_BASE_JSON;
        List<Long> l = getWeeklyBuildTimes(url);
        if (l.size() == 0) {
            // just try to catch unofficial devices
            l = getWeeklyBuildTimes(URL_BASE_GAPPS_JSON);
        }
        return l;
    }

    private static List<Long> getWeeklyBuildTimes(String url) {
        List<Long> weeklyBuildTimes = new ArrayList<>();
        String buildData = downloadUrlMemoryAsString(url);
        if (buildData == null || buildData.length() == 0) {
            return weeklyBuildTimes;
        }
        try {
            JSONObject object = new JSONObject(buildData);
            Iterator<String> nextKey = object.keys();
            while (nextKey.hasNext()) {
                String key = nextKey.next();
                if (key.equals("./" + Main.getDefaultDevice())) {
                    JSONArray builds = object.getJSONArray(key);
                    for (int i = 0; i < builds.length(); i++) {
                        JSONObject build = builds.getJSONObject(i);
                        String fileName = build.getString("filename");
                        if(isMatchingImage(fileName)) {
                            long modTime = build.getLong("timestamp") * 1000;
                            weeklyBuildTimes.add(modTime);
                        }
                    }
                }
            }
            Collections.sort(weeklyBuildTimes);
            Collections.reverse(weeklyBuildTimes);
        } catch (Exception e) {
            Log.e(TAG, "getWeeklyBuildTimes", e);
        }
        return weeklyBuildTimes;
    }

    private static boolean isGappsDevice() {
        return Main.getDefaultVersion().contains(GAPPS_VERSION_TAG);
    }
}
