package com.yonahforst.discovery;

import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by Yonah on 21/01/16.
 * base on: http://developer.android.com/samples/BluetoothAdvertisements/project.html
 */


/**
 * Manages BLE Advertising independent of the main app.
 * If the app goes off screen (or gets killed completely) advertising can continue because this
 * Service is maintaining the necessary Callback in memory.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class AdvertiserService extends Service {
    private final static String TAG = "discovery-AdvertiserSvc";

    /**
     * A global variable to let AdvertiserFragment check if the Service is running without needing
     * to start or bind to it.
     * This is the best practice method as defined here:
     * https://groups.google.com/forum/#!topic/android-developers/jEvXMWgbgzE
     */
    public static boolean running = false;

    /**
     * Setting autorestart to true will cause the service to automatically relaunch if it's
     * killed by the system. Note: this will not auto relaunch the service if it's killed by
     * the user.
     *
     * This will also restart advertising if the bluetooth state is toggled off then on.
     */
    public static boolean shouldAutoRestart = false;

    /**
     * The number of times the service will try to restart itself after a failure.
     * Failure, not being killed.
     */
    public static int maxRetriesAfterFailure = 3;


    public static final String ADVERTISING_FAILED =
            "com.example.android.bluetoothadvertisements.advertising_failed";

    public static final String ADVERTISING_FAILED_EXTRA_CODE = "failureCode";

    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mGattServer;

    private AdvertiseCallback mAdvertiseCallback;
    private BluetoothGattServerCallback mGattServerCallback;
    private ParcelUuid mUUID;
    private String mUsername;

    /**
     * how many times in a row we failed to start advertising
     */
    private int mRetriesAfterFailure = 0;

    /**
     * Monitor the bluetooth state. If autoRestart is true, start advertising whenever bluetooth
     * is turned back on.
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);

                switch (state) {
                    case BluetoothAdapter.STATE_ON:
                        if (shouldAutoRestart)
                            startAdvertising();
                        break;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Register for broadcasts on BluetoothAdapter state change
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mReceiver, filter);
    }

    @Override
    public void onDestroy() {
//        Log.v(TAG, "onDestroy");

        /**
         * Note that onDestroy is not guaranteed to be called quickly or at all. Services exist at
         * the whim of the system, and onDestroy can be delayed or skipped entirely if memory need
         * is critical.
         */
        running = false;
        stopAdvertising();

        // Unregister broadcast listeners
        unregisterReceiver(mReceiver);


        /**
         * If autorestart is true, launch a new service right before this one is killed. this
         * ensures that the system does turn off advertising by killing the service.
         */
        if (shouldAutoRestart) {
            Intent intent = new Intent(this, AdvertiserService.class);
            intent.putExtra("uuid", mUUID.toString());
            intent.putExtra("username", mUsername);
            startService(intent);
        }

        super.onDestroy();
    }

    /**
     * Required for extending service, but this will be a Started Service only, so no need for
     * binding.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Get references to system Bluetooth objects if we don't have them already.
     */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Bundle extras = intent.getExtras();

        this.mUUID = ParcelUuid.fromString(extras.getString("uuid"));
        this.mUsername = extras.getString("username");

        if (mBluetoothLeAdvertiser == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        }

        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
        }

        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.setName(mUsername);
            mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        }


        running = true;
        startAdvertising();

        return START_REDELIVER_INTENT;
    }

    /**
     * Starts BLE Advertising.
     */
    private void startAdvertising() {
//        Log.d(TAG, "Service: Starting Advertising");

        if (mAdvertiseCallback == null) {
            mAdvertiseCallback = new MyAdvertiseCallback();
        }

        if (mGattServerCallback == null) {
            mGattServerCallback = new MyGattServerCallback();
        }

        if (mGattServer == null) {
            mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
            mGattServer.addService(buildGattService());
        }

        if (mBluetoothLeAdvertiser != null) {
            AdvertiseSettings settings = buildAdvertiseSettings();
            AdvertiseData data = buildAdvertiseData();
            mBluetoothLeAdvertiser.startAdvertising(settings, data,
                    mAdvertiseCallback);
        }
    }

    /**
     * Stops BLE Advertising.
     */
    private void stopAdvertising() {
//        Log.d(TAG, "Service: Stopping Advertising");
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
            mAdvertiseCallback = null;
        }

        if (mGattServer != null) {
            mGattServer.clearServices();
            mGattServer.close();
            mGattServer = null;
        }
    }

    /**
     * Returns an AdvertiseData object which includes the Service UUID and Device Name.
     */
    private AdvertiseData buildAdvertiseData() {

        /**
         * Note: There is a strict limit of 31 Bytes on packets sent over BLE Advertisements.
         *  This includes everything put into AdvertiseData including UUIDs, device info, &
         *  arbitrary service or manufacturer data.
         *  Attempting to send packets over this limit will result in a failure with error code
         *  AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE. Catch this error in the
         *  onStartFailure() method of an AdvertiseCallback implementation.
         */

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
        dataBuilder.addServiceUuid(mUUID);
        dataBuilder.setIncludeDeviceName(mUsername.length() < 8);
        dataBuilder.setIncludeTxPowerLevel(false);

        /* For example - this will cause advertising to fail (exceeds size limit) */
        //String failureData = "asdghkajsghalkxcjhfa;sghtalksjcfhalskfjhasldkjfhdskf";
        //dataBuilder.addServiceData(Constants.Service_UUID, failureData.getBytes());

        return dataBuilder.build();
    }

    /**
     * Returns an AdvertiseSettings object set to use low power (to help preserve battery life)
     * and disable the built-in timeout since this code uses its own timeout runnable.
     */
    private AdvertiseSettings buildAdvertiseSettings() {
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER);
        settingsBuilder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
        settingsBuilder.setConnectable(true);
        settingsBuilder.setTimeout(0);
        return settingsBuilder.build();
    }

    /**
     * Returns a gatt service for the service uuid containing
     * a characteristic with the device name
     */
    private BluetoothGattService buildGattService() {
        BluetoothGattService gattService = new BluetoothGattService(
                mUUID.getUuid(),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );
        BluetoothGattCharacteristic gattCharacteristic = new BluetoothGattCharacteristic(
                mUUID.getUuid(),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );

        gattCharacteristic.setValue(mUsername);
        gattService.addCharacteristic(gattCharacteristic);
        return gattService;
    }

    /*
    * Callback handles all incoming requests from GATT clients.
    * From connections to read/write requests.
    */
    private class MyGattServerCallback extends BluetoothGattServerCallback {
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
//            Log.i(TAG, "onCharacteristicReadRequest " + characteristic.getUuid().toString());

            if (characteristic.getUuid().equals(mUUID.getUuid())) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        mUsername.getBytes());

            }
        }
    }

    /**
     * Custom callback after Advertising succeeds or fails to start. Broadcasts the error code
     * in an Intent. Will rety advertising x times before finally failing and stopping
     *
     *
     */
    private class MyAdvertiseCallback extends AdvertiseCallback {

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);

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
            mRetriesAfterFailure++;

            if (mRetriesAfterFailure < maxRetriesAfterFailure) {
                //RETRY CODE HERE!!

                new android.os.Handler().postDelayed(
                        new Runnable() {
                            public void run() {
                                stopAdvertising();
                                startAdvertising();
                            }
                        }, (int)(Math.pow(2, mRetriesAfterFailure) * 1000));

            } else {
                sendFailureIntent(errorCode);
                shouldAutoRestart = false;
                stopSelf();
            }
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            mRetriesAfterFailure = 0;
//            Log.d(TAG, "Advertising successfully started");
        }
    }

    /**
     * Builds and sends a broadcast intent indicating Advertising has failed. Includes the error
     * code as an extra. This is intended to be picked up by the {@code AdvertiserFragment}.
     */
    private void sendFailureIntent(int errorCode){

        Intent failureIntent = new Intent();
        failureIntent.setAction(ADVERTISING_FAILED);
        failureIntent.putExtra(ADVERTISING_FAILED_EXTRA_CODE, errorCode);
        sendBroadcast(failureIntent);
    }

}
