package com.joshblour.discovery;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Yonah on 18/03/16.
 */
public class GattManager {
    private final static String TAG = "discovery-GattManager";

    interface GattManagerCallback {
        void didIdentify(BluetoothDevice device, String username, ParcelUuid serviceUUID);
        void failedToMatchService(BluetoothDevice device);
    }

    private Integer mGattTimeoutInterval;
    private Map<String, Long> mGattConnectionStartTimes;
    private Map<String, BluetoothGatt> mGattConnections;

    private ParcelUuid mServiceUUID;
    private GattManagerCallback mCallback;
    private Context mContext;
    private MyBluetoothGattCallback mMyBluetoothGattCallback;

    public GattManager(Context context, ParcelUuid serviceUUID, GattManagerCallback callback) {
        mContext = context;
        mServiceUUID = serviceUUID;
        mCallback = callback;

        mGattConnections = new HashMap<>();
        mGattConnectionStartTimes = new HashMap<>();
        mGattTimeoutInterval = 30;

        mMyBluetoothGattCallback = new MyBluetoothGattCallback();
    }

    // call this method to try to identify a device.
    // this will attempt to connect to the device and read its services
    // if a service matching ours is found. the callback didMatchService is called and we try to read the characteristics
    // if we can read the characteristic matching our service, the callback didIdentify is called with the username
    // if no service matching ours is found, the callback failedToMatchService is called.
    public void identify(BluetoothDevice device) {
        boolean shouldConnect;

        // first check if there are any existing connection attempts in progress.
        // If there are, check to see if they have timed out.
        // If they have, cancel them and try again. If not, wait..
        // if no existing attempts, start a new one and store it (if successful).
        long currentTime = new Date().getTime();
        BluetoothGatt existingGatt = mGattConnections.get(device.getAddress());

        if (existingGatt == null) {
            Log.v(TAG, device.getAddress() + " - device not identified. will connect");
            shouldConnect = true;
        } else {
            long startedAt = mGattConnectionStartTimes.get(device.getAddress());
            if (currentTime - startedAt < mGattTimeoutInterval * 1000) {
                Log.v(TAG, device.getAddress() + " - device not identified. connection already in progress");
                shouldConnect = false;
            } else {
                Log.w(TAG, device.getAddress() + " - connection did timeout. will retry");
                existingGatt.disconnect();
                existingGatt.close();
                mGattConnections.remove(device.getAddress());
                mGattConnectionStartTimes.remove(device.getAddress());
                shouldConnect = true;
            }
        }

        if (shouldConnect) {
            BluetoothGatt gatt = device.connectGatt(mContext, true, mMyBluetoothGattCallback);

            if (gatt != null) {
                Log.v(TAG, device.getAddress() + " - attempted connection");
                mGattConnections.put(device.getAddress(), gatt);
                mGattConnectionStartTimes.put(device.getAddress(), new Date().getTime());
            }
        }

    }

    private class MyBluetoothGattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            // this will get called when a device connects or disconnects
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.v(TAG, gatt.getDevice().getAddress() + " - connected!");

                boolean started = gatt.discoverServices();

                if (!started)
                    gatt.disconnect();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.v(TAG, gatt.getDevice().getAddress() + " - disconnected...");
                gatt.close();
                mGattConnections.remove(gatt.getDevice().getAddress());
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            // this will get called after the client initiates a BluetoothGatt.discoverServices() call
            BluetoothGattService service = gatt.getService(mServiceUUID.getUuid());

            if (service == null)
                return;

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            Boolean isMyService = false;

            for (BluetoothGattCharacteristic characteristic : characteristics) {
                if (characteristic.getUuid().equals(mServiceUUID.getUuid())) {
                    isMyService = true;
                    Log.v(TAG, gatt.getDevice().getAddress() + " - found service!");

                    gatt.readCharacteristic(characteristic);
                }
            }

            if (!isMyService) {
                mCallback.failedToMatchService(gatt.getDevice());
                gatt.disconnect();
            }

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getUuid().equals(mServiceUUID.getUuid())) {
                String value = characteristic.getStringValue(0);
                ParcelUuid uuid = new ParcelUuid(characteristic.getUuid());

                // if the value is not nil, we found our username!
                if (value != null && value.length() > 0) {
                    Log.v(TAG, gatt.getDevice().getAddress() + " - got username!!");

                    mCallback.didIdentify(gatt.getDevice(), value, uuid);

                    // cancel the subscription to our characteristic
                    gatt.setCharacteristicNotification(characteristic, false);
                    // and disconnect from the peripehral
                    gatt.disconnect();

                }
            }
        }
    }

}
