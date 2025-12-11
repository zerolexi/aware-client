
package com.aware;

import android.Manifest;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncRequest;
import android.database.SQLException;
import android.database.sqlite.SQLiteException;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.aware.providers.WiFi_Provider;
import com.aware.providers.WiFi_Provider.WiFi_Data;
import com.aware.providers.WiFi_Provider.WiFi_Sensor;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.Encrypter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * WiFi Module. Scans and returns surrounding WiFi AccessPoints devices information and RSSI dB values.
 *
 * @author denzil
 */
public class WiFi extends Aware_Sensor {

    private static String TAG = "AWARE::WiFi";

    private static AlarmManager alarmManager = null;
    private static WifiManager wifiManager = null;
    private static PendingIntent wifiScan = null;
    private static Intent backgroundService = null;

    /**
     * Broadcasted event: currently connected to this AP
     */
    public static final String ACTION_AWARE_WIFI_CURRENT_AP = "ACTION_AWARE_WIFI_CURRENT_AP";

    /**
     * Broadcasted event: new WiFi AP device detected
     */
    public static final String ACTION_AWARE_WIFI_NEW_DEVICE = "ACTION_AWARE_WIFI_NEW_DEVICE";
    public static final String EXTRA_DATA = "data";

    /**
     * Broadcasted event: WiFi scan started
     */
    public static final String ACTION_AWARE_WIFI_SCAN_STARTED = "ACTION_AWARE_WIFI_SCAN_STARTED";

    /**
     * Broadcasted event: WiFi scan ended
     */
    public static final String ACTION_AWARE_WIFI_SCAN_ENDED = "ACTION_AWARE_WIFI_SCAN_ENDED";

    /**
     * Broadcast receiving event: request a WiFi scan
     */
    public static final String ACTION_AWARE_WIFI_REQUEST_SCAN = "ACTION_AWARE_WIFI_REQUEST_SCAN";

    @Override
    public void onCreate() {
        super.onCreate();

        AUTHORITY = WiFi_Provider.getAuthority(this);

        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        wifiManager = (WifiManager) this.getApplicationContext().getSystemService(WIFI_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiMonitor, filter);

        backgroundService = new Intent(this, BackgroundService.class);
        backgroundService.setAction(ACTION_AWARE_WIFI_REQUEST_SCAN);
        wifiScan = PendingIntent.getService(this, 0, backgroundService, PendingIntent.FLAG_UPDATE_CURRENT);

        REQUIRED_PERMISSIONS.add(Manifest.permission.CHANGE_WIFI_STATE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_WIFI_STATE);
        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_NETWORK_STATE);
    }

    private static WiFi.AWARESensorObserver awareSensor;

    public static void setSensorObserver(WiFi.AWARESensorObserver observer) {
        awareSensor = observer;
    }

    public static WiFi.AWARESensorObserver getSensorObserver() {
        return awareSensor;
    }

    public interface AWARESensorObserver {
        void onWiFiAPDetected(ContentValues data);

        void onWiFiDisabled();

        void onWiFiScanStarted();

        void onWiFiScanEnded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (PERMISSIONS_OK) {
            if (wifiManager == null) {
                if (DEBUG) Log.d(TAG, "This device does not have a WiFi chip");
                Aware.setSetting(this, Aware_Preferences.STATUS_WIFI, false);
                stopSelf();
            } else {
                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
                Aware.setSetting(this, Aware_Preferences.STATUS_WIFI, true);

                if (Aware.getSetting(this, Aware_Preferences.FREQUENCY_WIFI).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.FREQUENCY_WIFI, 60);
                }

                alarmManager.cancel(wifiScan);
                alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_WIFI)) * 1000, wifiScan);

                if (Aware.DEBUG) Log.d(TAG, "WiFi service active...");
            }

            if (Aware.isStudy(this)) {
                ContentResolver.setIsSyncable(Aware.getAWAREAccount(this), WiFi_Provider.getAuthority(this), 1);
                ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), WiFi_Provider.getAuthority(this), true);
                long frequency = Long.parseLong(Aware.getSetting(this, Aware_Preferences.FREQUENCY_WEBSERVICE)) * 60;
                SyncRequest request = new SyncRequest.Builder()
                        .syncPeriodic(frequency, frequency / 3)
                        .setSyncAdapter(Aware.getAWAREAccount(this), WiFi_Provider.getAuthority(this))
                        .setExtras(new Bundle()).build();
                ContentResolver.requestSync(request);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(wifiMonitor);
        if (wifiScan != null) alarmManager.cancel(wifiScan);

        ContentResolver.setSyncAutomatically(Aware.getAWAREAccount(this), WiFi_Provider.getAuthority(this), false);
        ContentResolver.removePeriodicSync(
                Aware.getAWAREAccount(this),
                WiFi_Provider.getAuthority(this),
                Bundle.EMPTY
        );

        if (Aware.DEBUG) Log.d(TAG, "WiFi service terminated...");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public class WiFiMonitor extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                Intent backgroundService = new Intent(context, BackgroundService.class);
                backgroundService.setAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                context.startService(backgroundService);
            }
        }
    }

    private final WiFiMonitor wifiMonitor = new WiFiMonitor();

    /**
     * Asynchronously get the AP we are currently connected to.
     */
    private static class WifiInfoFetch implements Callable<String> {
        private Context mContext;
        private WifiInfo mWifi;

        WifiInfoFetch(Context c, WifiInfo w) {
            mContext = c;
            mWifi = w;
        }

        @Override
        public String call() throws Exception {
            ContentValues rowData = new ContentValues();
            rowData.put(WiFi_Sensor.DEVICE_ID, Aware.getSetting(mContext, Aware_Preferences.DEVICE_ID));
            rowData.put(WiFi_Sensor.TIMESTAMP, System.currentTimeMillis());
            rowData.put(WiFi_Sensor.MAC_ADDRESS, Encrypter.hashMac(mContext, mWifi.getMacAddress()));
            rowData.put(WiFi_Sensor.BSSID, Encrypter.hashMac(mContext, mWifi.getBSSID()));
            rowData.put(WiFi_Sensor.SSID, Encrypter.hashSsid(mContext, mWifi.getSSID()));

            // Add the signal strength (RSSI) of the current connection
            int signal_strength = mWifi.getRssi();
            rowData.put(WiFi_Sensor.SIGNAL_STRENGTH, signal_strength);

            // Get link speed in Mbps
            int linkSpeed = mWifi.getLinkSpeed();
            rowData.put(WiFi_Sensor.LINK_SPEED, linkSpeed);

            // Get frequency in MHz
            int frequency = mWifi.getFrequency();
            rowData.put(WiFi_Sensor.FREQUENCY, frequency);

            // Measure throughput on current connection
            double[] throughput = measureWiFiThroughput();
            rowData.put(WiFi_Sensor.THROUGHPUT_DOWNLOAD, throughput[0]);
            rowData.put(WiFi_Sensor.THROUGHPUT_UPLOAD, throughput[1]);

            try {
                if (Aware.DEBUG) Log.d(TAG, "WiFi details - Signal Strength: " + signal_strength +
                        " dBm, Link Speed: " + linkSpeed + " Mbps, Frequency: " + frequency + " MHz");

                mContext.getContentResolver().insert(WiFi_Sensor.CONTENT_URI, rowData);

                Intent currentAp = new Intent(ACTION_AWARE_WIFI_CURRENT_AP);
                currentAp.putExtra(EXTRA_DATA, rowData);
                mContext.sendBroadcast(currentAp);

                if (Aware.DEBUG) Log.d(TAG, "WiFi local sensor information: " + rowData.toString());

            } catch (SQLiteException e) {
                if (Aware.DEBUG) Log.d(TAG, e.getMessage());
            } catch (SQLException e) {
                if (Aware.DEBUG) Log.d(TAG, e.getMessage());
            }

            return Thread.currentThread().getName();
        }

        /**
         * Measure WiFi throughput (download and upload speeds)
         * @return Array with [downloadMbps, uploadMbps]
         */
        private double[] measureWiFiThroughput() {
            double downloadMbps = 0;
            double uploadMbps = 0;

            try {
                // Use a common speed test server or your own server
                // String testUrl = Aware.getSetting(mContext,
                //         "wifi_throughput_test_url", "https://speed.cloudflare.com/__down?bytes=1000000");
                String testUrl = "https://speed.cloudflare.com/__down?bytes=1000000";

                // Measure download speed
                downloadMbps = measureDownloadSpeed(testUrl);

                // Measure upload speed
                // String uploadUrl = Aware.getSetting(mContext,
                //         "wifi_throughput_upload_url", "https://speed.cloudflare.com/__up");
                String uploadUrl = "https://speed.cloudflare.com/__up";
                uploadMbps = measureUploadSpeed(uploadUrl);

                if (Aware.DEBUG)
                    Log.d(TAG, "WiFi throughput - Download: " + downloadMbps +
                            " Mbps, Upload: " + uploadMbps + " Mbps");

            } catch (Exception e) {
                if (Aware.DEBUG) Log.e(TAG, "Error measuring WiFi throughput: " + e.getMessage());
            }

            return new double[] {downloadMbps, uploadMbps};
        }

        /**
         * Measure download speed by downloading data from a URL
         * @param testUrl URL to download data from
         * @return Download speed in Mbps
         */
        private double measureDownloadSpeed(String testUrl) {
            HttpURLConnection connection = null;
            InputStream inputStream = null;

            try {
                // Connect to the test URL
                URL url = new URL(testUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.connect();

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "HTTP error code: " + connection.getResponseCode());
                    return 0;
                }

                // Start timing
                long startTime = System.currentTimeMillis();

                // Download data
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;

                inputStream = connection.getInputStream();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    totalBytesRead += bytesRead;
                }

                // Calculate speed
                long endTime = System.currentTimeMillis();
                double durationSeconds = (endTime - startTime) / 1000.0;
                double speedBps = (totalBytesRead * 8) / durationSeconds;
                double speedMbps = speedBps / (1024 * 1024);

                return speedMbps;

            } catch (Exception e) {
                Log.e(TAG, "Error measuring download speed: " + e.getMessage());
                return 0;
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing connection: " + e.getMessage());
                }
            }
        }

        /**
         * Measure upload speed by uploading data to a server
         * @param uploadUrl URL to upload data to
         * @return Upload speed in Mbps
         */
        private double measureUploadSpeed(String uploadUrl) {
            HttpURLConnection connection = null;
            OutputStream outputStream = null;

            try {
                // Connect to the test URL
                URL url = new URL(uploadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.connect();

                // Generate random data to upload (1MB)
                byte[] data = new byte[1 * 1024 * 1024];
                new Random().nextBytes(data);

                // Start timing
                long startTime = System.currentTimeMillis();

                // Upload data
                outputStream = connection.getOutputStream();
                outputStream.write(data);
                outputStream.flush();

                // We need to get the response to complete the request
                InputStream in = connection.getInputStream();
                byte[] buffer = new byte[1024];
                while (in.read(buffer) != -1) {
                    // Just consume the response
                }
                in.close();

                // Calculate speed
                long endTime = System.currentTimeMillis();
                double durationSeconds = (endTime - startTime) / 1000.0;
                double speedBps = (data.length * 8) / durationSeconds;
                double speedMbps = speedBps / (1024 * 1024);

                return speedMbps;

            } catch (Exception e) {
                Log.e(TAG, "Error measuring upload speed: " + e.getMessage());
                return 0;
            } finally {
                try {
                    if (outputStream != null) outputStream.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing connection: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Asynchronously process the APs we can see around us
     */
    private static class WifiApResults implements Callable<String> {
        private Context mContext;
        private List<ScanResult> mAPS;

        WifiApResults(Context c, List<ScanResult> aps) {
            mContext = c;
            mAPS = aps;
        }

        @Override
        public String call() throws Exception {
            if (Aware.DEBUG) Log.d(TAG, "Found " + mAPS.size() + " access points");
            long currentScan = System.currentTimeMillis();

            for (ScanResult ap : mAPS) {
                ContentValues rowData = new ContentValues();
                rowData.put(WiFi_Data.DEVICE_ID, Aware.getSetting(mContext, Aware_Preferences.DEVICE_ID));
                rowData.put(WiFi_Data.TIMESTAMP, currentScan);
                rowData.put(WiFi_Data.BSSID, Encrypter.hashMac(mContext, ap.BSSID));
                rowData.put(WiFi_Data.SSID, Encrypter.hashSsid(mContext, ap.SSID));
                rowData.put(WiFi_Data.SECURITY, ap.capabilities);
                rowData.put(WiFi_Data.FREQUENCY, ap.frequency);
                rowData.put(WiFi_Data.RSSI, ap.level);

                try {
                    mContext.getContentResolver().insert(WiFi_Data.CONTENT_URI, rowData);

                    if (awareSensor != null) awareSensor.onWiFiAPDetected(rowData);

                    if (Aware.DEBUG)
                        Log.d(TAG, ACTION_AWARE_WIFI_NEW_DEVICE + ": " + rowData.toString());

                    Intent detectedAP = new Intent(ACTION_AWARE_WIFI_NEW_DEVICE);
                    detectedAP.putExtra(EXTRA_DATA, rowData);
                    mContext.sendBroadcast(detectedAP);

                } catch (SQLiteException e) {
                    if (Aware.DEBUG) Log.d(TAG, e.getMessage());
                } catch (SQLException e) {
                    if (Aware.DEBUG) Log.d(TAG, e.getMessage());
                }
            }

            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_WIFI_SCAN_ENDED);

            Intent scanEnd = new Intent(ACTION_AWARE_WIFI_SCAN_ENDED);
            mContext.sendBroadcast(scanEnd);

            return Thread.currentThread().getName();
        }
    }

    /**
     * Background service for WiFi module
     * - ACTION_AWARE_WIFI_REQUEST_SCAN
     * - {@link WifiManager#SCAN_RESULTS_AVAILABLE_ACTION}
     * - ACTION_AWARE_WEBSERVICE
     *
     * @author df
     */
    public static class BackgroundService extends IntentService {
        public BackgroundService() {
            super(TAG + " background service");
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            if (intent.getAction() != null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

                if (intent.getAction().equals(WiFi.ACTION_AWARE_WIFI_REQUEST_SCAN)) {
                    try {
                        if (wifiManager.isWifiEnabled()) {

                            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_WIFI_SCAN_STARTED);

                            Intent scanStart = new Intent(ACTION_AWARE_WIFI_SCAN_STARTED);
                            sendBroadcast(scanStart);

                            wifiManager.startScan();

                            if (awareSensor != null) awareSensor.onWiFiScanStarted();

                        } else {
                            if (Aware.DEBUG) {
                                Log.d(WiFi.TAG, "WiFi is off");
                            }

                            ContentValues rowData = new ContentValues();
                            rowData.put(WiFi_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                            rowData.put(WiFi_Data.TIMESTAMP, System.currentTimeMillis());
                            rowData.put(WiFi_Data.LABEL, "disabled");

                            getContentResolver().insert(WiFi_Data.CONTENT_URI, rowData);

                            if (awareSensor != null) awareSensor.onWiFiDisabled();
                        }
                    } catch (NullPointerException e) {
                        if (Aware.DEBUG) {
                            Log.d(WiFi.TAG, "WiFi is off");
                        }

                        ContentValues rowData = new ContentValues();
                        rowData.put(WiFi_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                        rowData.put(WiFi_Data.TIMESTAMP, System.currentTimeMillis());
                        rowData.put(WiFi_Data.LABEL, "disabled");

                        getContentResolver().insert(WiFi_Data.CONTENT_URI, rowData);

                        if (awareSensor != null) awareSensor.onWiFiDisabled();
                    }
                }

                if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    WifiInfo wifi = wifiManager.getConnectionInfo();
                    if (wifi == null) return;

                    WifiInfoFetch wifiInfo = new WifiInfoFetch(getApplicationContext(), wifi);
                    WifiApResults scanResults = new WifiApResults(getApplicationContext(), wifiManager.getScanResults());

                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.submit(wifiInfo);
                    executor.submit(scanResults);
                    executor.shutdown();

                    if (awareSensor != null) awareSensor.onWiFiScanEnded();
                }
            }
        }
    }
}