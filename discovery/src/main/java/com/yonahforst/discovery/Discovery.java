package com.yonahforst.discovery;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Yonah on 15/10/15.
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
public class Discovery {
    private final static String TAG = "discovery-Discovery";
    public interface DiscoveryCallback {
        void didUpdateUsers(ArrayList<BLEUser> users, Boolean usersChanged);
    }
    public enum DIStartOptions{
        DIStartAdvertisingAndDetecting,
        DIStartAdvertisingOnly,
        DIStartDetectingOnly,
        DIStartNone
    }

    private Context mContext;
    private String mUsername;
    private ParcelUuid mUUID;
    private Boolean mPaused;
    private Integer mUserTimeoutInterval;
    private Integer mScanForSeconds;
    private Integer mWaitForSeconds;
    private Boolean mShouldAdvertise;
    private Boolean mShouldDiscover;
    private Map<String, BLEUser> mUsersMap;
    private Handler mHandler;
    private Runnable mRunnable;
    private DiscoveryCallback mDiscoveryCallback;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private BluetoothAdapter.LeScanCallback mLeScanCallback;

    /**
     * Listens for notifications that the {@code AdvertiserService} has failed to start advertising.
     * This Receiver deals with Fragment UI elements and only needs to be active when the Fragment
     * is on-screen, so it's defined and registered in code instead of the Manifest.
     */
    private BroadcastReceiver mAdvertisingFailureReceiver;

    private final BluetoothGattCallback mBtleGattCallback = new BluetoothGattCallback() {

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            Log.v(TAG, "BluetoothGattCallback characteristicChanged: " + characteristic);
            // this will get called anytime you perform a read or write characteristic operation
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            Log.v(TAG, "BluetoothGattCallback stateChanged: " + newState);
            connectionStateChange(gatt, status, newState);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            Log.v(TAG, "BluetoothGattCallback serviceDiscovered. Status: " + status);
            servicesDiscovered(gatt, status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristicRead(gatt, characteristic, status);
            }
        }
    };


    public Discovery(Context context, ParcelUuid uuid, String username, DiscoveryCallback discoveryCallback) {
        this(context, uuid, username, DIStartOptions.DIStartAdvertisingAndDetecting, discoveryCallback);
    }

    public Discovery(Context context, ParcelUuid uuid, String username, DIStartOptions startOptions, DiscoveryCallback discoveryCallback ) {
//        initialize defaults
        mShouldAdvertise = false;
        mShouldDiscover = false;
        mPaused = false;
        mUserTimeoutInterval = 5;
        mScanForSeconds = 5;
        mWaitForSeconds = 5;
        mContext = context;
        mUUID = uuid;
        mUsername = username;
        mDiscoveryCallback = discoveryCallback;
        mUsersMap = new HashMap<>();
        mHandler = new Handler();

        //TODO            // listen for UIApplicationDidEnterBackgroundNotification
//            [[NSNotificationCenter defaultCenter] addObserver:self
//            selector:@selector(appDidEnterBackground:)
//            name:UIApplicationDidEnterBackgroundNotification
//            object:nil];
//
//            // listen for UIApplicationDidEnterBackgroundNotification
//            [[NSNotificationCenter defaultCenter] addObserver:self
//            selector:@selector(appWillEnterForeground:)
//            name:UIApplicationWillEnterForegroundNotification
//            object:nil];
//
//
        setupAdvertisingFailureReceiver();

        switch (startOptions) {
            case DIStartAdvertisingAndDetecting:
                this.setShouldAdvertise(true);
                this.setShouldDiscover(true);
                break;
            case DIStartAdvertisingOnly:
                this.setShouldAdvertise(true);
                break;
            case DIStartDetectingOnly:
                this.setShouldDiscover(true);
                break;
            case DIStartNone:
            default:
                break;
        }
    }

//
//TODO    -(void)dealloc {
//        [[NSNotificationCenter defaultCenter] removeObserver:self name:UIApplicationWillEnterForegroundNotification object:nil];
//        [[NSNotificationCenter defaultCenter] removeObserver:self name:UIApplicationDidEnterBackgroundNotification object:nil];
//    }
//

    public void setPaused(Boolean paused) {
        if (getBluetoothAdapter() == null)
            return;

        if (this.mPaused == paused)
            return;
        this.mPaused = paused;

        if (paused) {
            stopDetecting();
            stopAdvertising();
        } else {
            startDetectionCycling();
            startAdvertising();
        }
    }

    // DETECTION METHODS

    public void setShouldDiscover(Boolean shouldDiscover) {
        if (getBluetoothAdapter() == null)
            return;

        if (this.mShouldDiscover == shouldDiscover)
            return;

        this.mShouldDiscover = shouldDiscover;

        if (shouldDiscover) {
            startDetectionCycling();
        } else {
            stopDetecting();
            checkList();
        }
    }

    // A more energy efficient way to detect.
    // It detects for mScanForSeconds(default: 5) then stops for mWaitForSeconds(default: 5) then starts again.
    // mShouldDiscover starts THIS method when set to true and stops it when set to false.
    private void startDetectionCycling() {
        if (!mShouldDiscover || mPaused)
            return;

        startDetecting();
        Log.v(TAG, "detection cycle started");

        if (mRunnable != null)
            mHandler.removeCallbacks(mRunnable);

         mRunnable = new Runnable() {
            @Override
            public void run() {
                stopDetecting();
                Log.v(TAG, "detection cycle stopped");

                Runnable runable = new Runnable() {
                    @Override
                    public void run() {
                        startDetectionCycling();
                    }
                };
                mHandler.postDelayed(runable, mWaitForSeconds * 1000);
                checkList();
            }
        };
        mHandler.postDelayed(mRunnable, mScanForSeconds * 1000);
    }

    public void startDetecting() {
        if (!getBluetoothAdapter().isEnabled())
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            // we only listen to the service that belongs to our uuid
            // this is important for performance and battery consumption
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
            List<ScanFilter> filters = new ArrayList<>();

            // filtering by the ServiceUUID prevents us from discovering iOS devices broadcasting in the background
            // since their serviceUUID gets moved into the 'overflow area'.
            // we need to find a way to filter also by the manufacturerData to find only devices with our UUID.
            // more here: https://forums.developer.apple.com/thread/11705
//            filters.add(new ScanFilter.Builder().setServiceUuid(getUUID()).build());

            // Empty data
            byte[] manData = new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,8,64,0,0};

//            // Data Mask
            byte[] mask = new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1,1};

            // Add data array to filters
            ScanFilter manDataFilter = new ScanFilter.Builder().setManufacturerData(76, manData, mask).build();
            ScanFilter serviceUUIDFilter = new ScanFilter.Builder().setServiceUuid(getUUID()).build();

//            filters.add(manDataFilter);
//            filters.add(serviceUUIDFilter);

            getBluetoothLeScanner().startScan(filters, settings, getScanCallback());
        } else {
            UUID[] serviceUUIDs = {getUUID().getUuid()};
            getBluetoothAdapter().startLeScan(serviceUUIDs, getLeScanCallback());
        }
        Log.v(TAG, "started detecting");

    }

    public void stopDetecting(){

        if (!getBluetoothAdapter().isEnabled())
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getBluetoothLeScanner().stopScan(getScanCallback());
            getBluetoothLeScanner().flushPendingScanResults(getScanCallback());
        } else {
            getBluetoothAdapter().stopLeScan(getLeScanCallback());
        }
        Log.v(TAG, "stopped detecting");
    }



    // ADVERTISING METHODS

    public void setShouldAdvertise(Boolean shouldAdvertise) {
        if (getBluetoothAdapter() == null)
            return;

        if (this.mShouldAdvertise == shouldAdvertise)
            return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            this.mShouldAdvertise = false;
            return;
        }

        this.mShouldAdvertise = shouldAdvertise;

        if (shouldAdvertise) {
            startAdvertising();
        } else {
            stopAdvertising();
        }
    }

    private void startAdvertising() {
        if (getBluetoothAdapter().isEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AdvertiserService.shouldAutoRestart = true;
            if (!AdvertiserService.running) {
                mContext.startService(getAdvertiserServiceIntent(mContext));
                Log.v(TAG, "started advertising");
            }
        }
    }

    private void stopAdvertising() {
        if (getBluetoothAdapter().isEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AdvertiserService.shouldAutoRestart = false;
            mContext.stopService(getAdvertiserServiceIntent(mContext));
            Log.v(TAG, "stopped advertising");
        }
    }

    /**
     * Returns Intent addressed to the {@code AdvertiserService} class.
     */
    private Intent getAdvertiserServiceIntent(Context c) {
        Intent intent = new Intent(c, AdvertiserService.class);
        intent.putExtra("uuid", getUUID().toString());
        intent.putExtra("username", getUsername());
        return intent;
    }


    //METHODS TO PROCESS SCAN RESULTS

    public void updateList() {
        updateList(true);
    }

    // sends an update to the delegate with an array of identified users
    public void updateList(Boolean usersChanged) {
        ArrayList<BLEUser> users = new ArrayList<>(getUsersMap().values());

        // remove unidentified users
        ArrayList<BLEUser> discardedItems = new ArrayList<>();
        for (BLEUser user : users) {
            if (!user.isIdentified()) {
                discardedItems.add(user);
            }
        }
        users.removeAll(discardedItems);

        // we sort the list according to "proximity".
        // so the client will receive ordered users according to the proximity.
        Collections.sort(users, new Comparator<BLEUser>() {
            public int compare(BLEUser s1, BLEUser s2) {
                if (s1.getProximity() == null || s2.getProximity() == null)
                    return 0;
                return s1.getProximity().compareTo(s2.getProximity());
            }
        });


        if (mDiscoveryCallback != null) {
            mDiscoveryCallback.didUpdateUsers(users, usersChanged);
        }
    }

// removes users who haven't been seen in mUserTimeoutInterval seconds and triggers an update to the delegate
    private void checkList() {

        if (getUsersMap() == null)
            return;

        long currentTime = new Date().getTime();
        ArrayList<String> discardedKeys = new ArrayList<>();

        for (String key : getUsersMap().keySet()) {
            BLEUser bleUser = getUsersMap().get(key);
            long diff = currentTime - bleUser.getUpdateTime();

            // We remove the user if we haven't seen him for the userTimeInterval amount of seconds.
            // You can simply set the userTimeInterval variable anything you want.
            if (diff > getUserTimeoutInterval() * 1000) {
                discardedKeys.add(key);
            }
        }

        // update the list if we removed a user.
        if (discardedKeys.size() > 0) {
            for (String key : discardedKeys) {
                getUsersMap().remove(key);
            }
            updateList();
        } else {
        // simply update the list, because the order of the users may have changed.
            updateList(false);
        }

    }

    private BLEUser userWithDeviceAddress(String deviceAddress) {
        return getUsersMap().get(deviceAddress);
    }

    public void myOnScanResult(int callbackType, ScanResult scanResult) {
        myOnLeScan(scanResult.getDevice(), scanResult.getRssi(), scanResult.getScanRecord().getBytes());
    }

    public void myOnLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        Log.e(TAG, "found device with address " + device.getAddress());

        String username = device.getName();
        BLEUser bleUser = userWithDeviceAddress(device.getAddress());
        if (bleUser == null) {
            bleUser = new BLEUser(device);
            bleUser.setUsername(null);
            bleUser.setIdentified(false);

            getUsersMap().put(bleUser.getDeviceAddress(), bleUser);
        }

        if (!bleUser.isIdentified()) {
            // We check if we can get the username from the advertisement data,
            // in case the advertising peer application is working at foreground
            // if we get the name from advertisement we don't have to establish a peripheral connection
            if (username != null && username.length() > 0) {
                bleUser.setUsername(username);
                bleUser.setIdentified(true);

                // we update our list for callback block
                updateList();
            } else {
                Log.e(TAG, "device not identified. will connect");
                // nope we could not get the username from CBAdvertisementDataLocalNameKey,
                // we have to connect to the peripheral and try to get the characteristic data
                // add we will extract the username from characteristics.
                device.connectGatt(mContext, true, mBtleGattCallback);
            }
        } else {
            Log.e(TAG, "device is identified");
        }
        bleUser.setRssi(rssi);
        bleUser.setUpdateTime(new Date().getTime());
    }

    public void connectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {

        // this will get called when a device connects or disconnects
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.e(TAG, "connection state changed: state connected!");

            gatt.discoverServices();
        }
    }

    public void servicesDiscovered(final BluetoothGatt gatt, final int status) {
        Log.e(TAG, "services discovered: gatt " + gatt);
        Log.e(TAG, "services discovered: status " + status);

        // this will get called after the client initiates a BluetoothGatt.discoverServices() call
        BluetoothGattService service = gatt.getService(getUUID().getUuid());
        Log.e(TAG, "services discovered: service " + service);

        if (service == null)
            return;

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        Boolean isMyService = false;
        for (BluetoothGattCharacteristic characteristic : characteristics) {
            if (characteristic.getUuid().equals(getUUID().getUuid())) {
                gatt.readCharacteristic(characteristic);
                isMyService = true;
            }
        }
        if (!isMyService)
            gatt.disconnect();
    }

    private void characteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        Log.e(TAG, "characteristic read: gatt " + gatt);
        Log.e(TAG, "characteristic read: characteristic " + characteristic);
        Log.e(TAG, "characteristic read: status " + status);



        if (characteristic.getUuid().equals(getUUID().getUuid())) {
            String value = characteristic.getStringValue(0);

            // if the value is not nil, we found our username!
            if (value != null && value.length() > 0) {
                BLEUser user = userWithDeviceAddress(gatt.getDevice().getAddress());
                user.setUsername(value);
                user.setIdentified(true);
                updateList();

                // cancel the subscription to our characteristic
                gatt.setCharacteristicNotification(characteristic, false);
                // and disconnect from the peripehral
                gatt.disconnect();
                gatt.close();

            }
        }
    }

    private List<UUID> parseUUIDs(byte[] advertisedData) {
        List<UUID> uuids = new ArrayList<UUID>();

        ByteBuffer buffer = ByteBuffer.wrap(advertisedData).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() > 2) {
            byte length = buffer.get();
            if (length == 0) break;

            byte type = buffer.get();
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (length >= 2) {
                        uuids.add(UUID.fromString(String.format(
                                "%08x-0000-1000-8000-00805f9b34fb", buffer.getShort())));
                        length -= 2;
                    }
                    break;

                case 0x06: // Partial list of 128-bit UUIDs
                case 0x07: // Complete list of 128-bit UUIDs
                    while (length >= 16) {
                        long lsb = buffer.getLong();
                        long msb = buffer.getLong();
                        uuids.add(new UUID(msb, lsb));
                        length -= 16;
                    }
                    break;

                default:
                    buffer.position(buffer.position() + length - 1);
                    break;
            }
        }

        return uuids;
    }

//    GETTERS AND SETTERS

    public String getUsername() {
        return mUsername;
    }

    public ParcelUuid getUUID() {
        return mUUID;
    }

    public Boolean getPaused() {
        return mPaused;
    }

    public Boolean getShouldDiscover() {
        return mShouldDiscover;
    }

    public Boolean getShouldAdvertise() {
        return mShouldAdvertise;
    }

    public Integer getUserTimeoutInterval() {
        return mUserTimeoutInterval;
    }

    public void setUserTimeoutInterval(Integer mUserTimeoutInterval) {
        this.mUserTimeoutInterval = mUserTimeoutInterval;
    }

    public Map<String, BLEUser> getUsersMap() {
        return mUsersMap;
    }

    public Integer getScanForSeconds() {
        return mScanForSeconds;
    }

    public void setScanForSeconds(Integer scanForSeconds) {
        this.mScanForSeconds = scanForSeconds;
        startDetectionCycling();
    }

    public Integer getWaitForSeconds() {
        return mWaitForSeconds;
    }

    public void setWaitForSeconds(Integer waitForSeconds) {
        this.mWaitForSeconds = waitForSeconds;
        startDetectionCycling();
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (mBluetoothAdapter == null) {
            BluetoothManager manager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = manager.getAdapter();
        }

        return mBluetoothAdapter;
    }


    private BluetoothLeScanner getBluetoothLeScanner() {
        if (mBluetoothLeScanner == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBluetoothLeScanner = getBluetoothAdapter().getBluetoothLeScanner();
        }
        return mBluetoothLeScanner;
    }


    private ScanCallback getScanCallback() {
        if (mScanCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mScanCallback = new ScanCallback() {

                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    Log.e(TAG, "ScanCallback result callbackType: " + callbackType);
                    myOnScanResult(callbackType, result);
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    Log.e(TAG, "ScanCallback batch results: " + results);
                    for (ScanResult r : results) {
                        myOnScanResult(-1, r);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    switch (errorCode) {
                        case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                            Log.e(TAG, "Scan failed: already started");
                            break;
                        case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                            Log.e(TAG, "Scan failed: app registration failed");
                            break;
                        case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                            Log.e(TAG, "Scan failed: feature unsupported");
                            break;
                        case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                            Log.e(TAG, "Scan failed: internal error");
                            break;
                    }
                }
            };
        }
        return mScanCallback;
    }

    private BluetoothAdapter.LeScanCallback getLeScanCallback() {
        if (mLeScanCallback == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    myOnLeScan(device, rssi, scanRecord);
                }
            };
        }
        return mLeScanCallback;
    }

    public void setupAdvertisingFailureReceiver() {
        if (mAdvertisingFailureReceiver == null) {
            this.mAdvertisingFailureReceiver = new BroadcastReceiver() {

                /**
                 * Receives Advertising error codes from {@code AdvertiserService} and displays error messages
                 * to the user. Sets the advertising toggle to 'false.'
                 */
                @Override
                public void onReceive(Context context, Intent intent) {

                    int errorCode = intent.getIntExtra(AdvertiserService.ADVERTISING_FAILED_EXTRA_CODE, -1);

                    switch (errorCode) {
                        case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                            Log.e(TAG, "Advertise failed: already started");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                            Log.e(TAG, "Advertise failed: data too large");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                            Log.e(TAG, "Advertise failed: feature unsupported");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                            Log.e(TAG, "Advertise failed: internal error");
                            break;
                        case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                            Log.e(TAG, "Advertise failed: too many advertisers");
                            break;
                    }
                }
            };
        }
    }
}
