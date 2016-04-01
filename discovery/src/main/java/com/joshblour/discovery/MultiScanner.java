package com.joshblour.discovery;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by Yonah on 18/03/16.
 *
 * The purpose of this class is to abstract the two types of scanning that we support
 * Pre-Lollipop and Lollipop+
 *
 * You specify which type of scanning you want when you initiate the class.
 * We provide a consistent callback interface for both types of scanning
 *
 * You can optionally filter by a serviceUUID.
 * Note: specifying the service uuid will prevent discovery of ios apps in the background
 * since all serviceUUIDs get moved to an overflow area when the app goes to background,
 * and to our scanner, they disappear. To discovery ios backgrounded apps, you need to
 * start an unfiltered scan and then filter the results yourself.
 *
 */
public class MultiScanner {
    private final static String TAG = "discovery-MultiScanner";

    public interface MultiScannerCallback {
        void onScanResult(BluetoothDevice device, int rssi, byte[] scanRecord);
        void onScanFailed(int errorCode);
    }

    BluetoothAdapter mAdapter;
    ParcelUuid mServiceUUID;
    MultiScannerCallback mScanCallback;
    boolean mUsePreLScanner;
    PostLScanCallback mPostLScanCallback;
    BluetoothAdapter.LeScanCallback mPreLScanCallback;

    public MultiScanner(BluetoothAdapter adapter, ParcelUuid uuid, MultiScannerCallback callback) {
        this(adapter, uuid, callback, false);
    }

    /*
    @param adapter - the system bluetooth adapter
    @param uuid - the uuid of the service we are searching for - leave this blank if you dont want to filter
    @param callback - a callback when the users are updated.
    @param usePreLScanner - an option to use the deprecated LEScanner, since it sometimes functions better
    */

    public MultiScanner(BluetoothAdapter adapter, ParcelUuid uuid, MultiScannerCallback callback, boolean usePreLScanner) {
        mAdapter = adapter;
        mServiceUUID = uuid;
        mScanCallback = callback;
        mUsePreLScanner = usePreLScanner;

    }

    public void start() {
        if (!mAdapter.isEnabled())
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !mUsePreLScanner) {
            // we only listen to the service that belongs to our uuid
            // this is important for performance and battery consumption
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();

            List<ScanFilter> filters = new ArrayList<>();

            if (mServiceUUID != null) {
                // filtering by the ServiceUUID prevents us from discovering iOS devices broadcasting in the background
                // since their serviceUUID gets moved into the 'overflow area'.
                // we need to find a way to filter also by the manufacturerData to find only devices with our UUID.
                // more here: https://forums.developer.apple.com/thread/11705
                ScanFilter serviceUUIDFilter = new ScanFilter.Builder().setServiceUuid(mServiceUUID).build();
                filters.add(serviceUUIDFilter);


//                // An alternative is to filter by manufacturer data,
//                // but I still haven't figured out how to show only apple devices
//
//                // Empty data
//                byte[] manData = new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,8,64,0,0};
//                // Data Mask
//                byte[] mask = new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1,1};
//                // Add data array to filters
//                ScanFilter manDataFilter = new ScanFilter.Builder().setManufacturerData(76, manData, mask).build();
//                filters.add(manDataFilter);
            }


            if (mPostLScanCallback == null)
                mPostLScanCallback = new PostLScanCallback();
            mAdapter.getBluetoothLeScanner().startScan(filters, settings, mPostLScanCallback );
        } else {
            if (mPreLScanCallback == null)
                mPreLScanCallback = new PreLScanCallback();

            if (mServiceUUID != null) {
                UUID[] serviceUUIDs = {mServiceUUID.getUuid()};
                mAdapter.startLeScan(serviceUUIDs, mPreLScanCallback);
            } else {
                mAdapter.startLeScan(mPreLScanCallback);
            }
        }
    }

    public void stop() {
        if (!mAdapter.isEnabled())
            return;

        if (mPostLScanCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mAdapter.getBluetoothLeScanner().stopScan(mPostLScanCallback);
            mAdapter.getBluetoothLeScanner().flushPendingScanResults(mPostLScanCallback);
        }

        if (mPreLScanCallback != null) {
            mAdapter.stopLeScan(mPreLScanCallback);
        }
    }


    private class PreLScanCallback implements BluetoothAdapter.LeScanCallback {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            mScanCallback.onScanResult(device, rssi, scanRecord);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private class PostLScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mScanCallback.onScanResult(result.getDevice(), result.getRssi(), result.getScanRecord().getBytes());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.v(TAG, "ScanCallback batch results: " + results);
            for (ScanResult r : results) {
                onScanResult(-1, r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            mScanCallback.onScanFailed(errorCode);
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
    }
}
