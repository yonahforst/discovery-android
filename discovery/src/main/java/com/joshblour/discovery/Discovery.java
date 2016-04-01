package com.joshblour.discovery;

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
public class Discovery implements MultiScanner.MultiScannerCallback, GattManager.GattManagerCallback{
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
    private Boolean mDisableAndroidLScanner;
    private Map<String, BLEUser> mUsersMap;


    private Handler mHandler;
    private Runnable mRunnable;
    private DiscoveryCallback mDiscoveryCallback;
    private BluetoothAdapter mBluetoothAdapter;

    private GattManager mGattManager;
    private GattManager.GattManagerCallback mGattManagerCallback;
    private MultiScanner mScanner;

    public Discovery(Context context, ParcelUuid uuid, String username, DiscoveryCallback discoveryCallback) {
        this(context, uuid, username, DIStartOptions.DIStartAdvertisingAndDetecting, discoveryCallback);
    }

    public Discovery(Context context, ParcelUuid uuid, String username, DIStartOptions startOptions, DiscoveryCallback discoveryCallback ) {
//        initialize defaults
        mShouldAdvertise = false;
        mShouldDiscover = false;
        mDisableAndroidLScanner = false;
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

    //***BEGIN DETECTION METHODS***
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

        if (getBluetoothAdapter() == null)
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
        if (mScanner == null)
            mScanner = new MultiScanner(getBluetoothAdapter(), null, this, true);

        mScanner.start();
    }

    public void stopDetecting(){
        if (mScanner != null)
            mScanner.stop();
    }//***END DETECTION METHODS***





    //***BEGIN ADVERTISING METHODS***
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
    } // ***END ADVERTISING METHODS***


    //***BEGIN METHODS TO PROCESS SCAN RESULTS***

    public void updateList() {
        updateList(true);
    }

    // sends an update to the delegate with an array of identified users
    public void updateList(Boolean usersChanged) {
        ArrayList<BLEUser> users = new ArrayList<>(getUsersMap().values());

        // remove unidentified users and users who dont belong to our service
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

    // removes users who haven't been seen in mUserTimeoutInterval seconds and triggers
    // an update to the delegate
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

    private BLEUser userForDevice(BluetoothDevice device) {
        BLEUser bleUser = getUsersMap().get(device.getAddress());

        if (bleUser == null) {
            bleUser = new BLEUser(device);
            bleUser.setUsername(null);
            bleUser.setIdentified(false);
            getUsersMap().put(bleUser.getDeviceAddress(), bleUser);
        }

        return bleUser;
    }

    @Override
    public void onScanResult(BluetoothDevice device, int rssi, byte[] scanRecord) {

        BLEUser bleUser = userForDevice(device);

        // before we report this device to our delegate as a success, two things:
        // 1) Make sure it contains our service (it's another device advertising with our unique uuid)
        // 2) Make sure we can read its username

        // We check if we can get a cached copy of the devices service uuids
        if (bleUser.isMyService() == null) {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null && uuids.length > 0) {
                for (ParcelUuid uuid : uuids) {
                    if (uuid.getUuid().equals(mUUID.getUuid())) {
                        bleUser.setIsMyService(true);
                        updateList(true);
                    }
                }
            }
        }

        // We check if we can get the username from the advertisement data,
        // in case the advertising peer application is working at foreground
        if (bleUser.getUsername() == null) {
            String username = device.getName();

            if (username != null && username.length() > 0) {
                bleUser.setUsername(username);
                updateList(true);
            }
        }

        //if you have the username and a boolean value for isMyService, you have enough to identify the user
        if (bleUser.isMyService() != null && bleUser.getUsername() != null) {
            if (bleUser.isMyService()) {
                bleUser.setIdentified(true);
            }
        }


        if (bleUser.isIdentified()) {
            /// great! we know everything we need to about this service. just update the rssi and time and we're done
            Log.v(TAG, device.getAddress() + " - device is identified");
        } else if (bleUser.isMyService() == null) {
            // ok, maybe we know the username but we dont know if it's our service, so connect to gatt and check.
            if (mGattManager == null)
                mGattManager = new GattManager(mContext, mUUID, this);

            mGattManager.identify(device);
        } else if (!bleUser.isMyService()) {
            /// Ok, this isn't our service, we don't care about it.
            Log.v(TAG, device.getAddress() + " - device not our service");
        }

        bleUser.setRssi(rssi);
        bleUser.setUpdateTime(new Date().getTime());
    }

    @Override
    public void onScanFailed(int errorCode) {

    }

    @Override
    public void didIdentify(BluetoothDevice device, String username, ParcelUuid uuid) {
        BLEUser bleUser = userForDevice(device);
        bleUser.setUsername(username);
        bleUser.setIdentified(true);
        bleUser.setIsMyService(true);
        updateList(true);
    }


    @Override
    public void failedToMatchService(BluetoothDevice device) {
        BLEUser bleUser = userForDevice(device);
        bleUser.setIsMyService(false);
    }


    //***BEGIN GETTERS AND SETTERS**
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
    public Boolean getShouldDisableAndroidLScanner() {
        return mDisableAndroidLScanner;
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
    public Integer getWaitForSeconds() {
        return mWaitForSeconds;
    }

    public void setShouldDisableAndroidLScanner(Boolean disableAndroidLScanner) {
        this.mDisableAndroidLScanner = disableAndroidLScanner;
    }
    public void setScanForSeconds(Integer scanForSeconds) {
        this.mScanForSeconds = scanForSeconds;
        startDetectionCycling();
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

}
