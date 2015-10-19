package com.yonahforst.discovery;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
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
public class Discovery {
    private final static String TAG = "discovery-Discovery";

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

    private DiscoveryCallback mDiscoveryCallback;

    public enum DIStartOptions{
        DIStartAdvertisingAndDetecting,
        DIStartAdvertisingOnly,
        DIStartDetectingOnly,
        DIStartNone
    }

    public interface DiscoveryCallback {
        void didUpdateUsers(ArrayList<BLEUser> users, Boolean usersChanged);
    }


    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothLeScanner mBluetoothLeScanner;
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.w(TAG, "LE Advertise Started.");
            super.onStartSuccess(settingsInEffect);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: " + errorCode);
            super.onStartFailure(errorCode);
        }
    };
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.w(TAG, "ScanCallback result callbackType: " + callbackType);
            scanResult(callbackType, result);
            super.onScanResult(callbackType, result);
        }
    };

    private final BluetoothGattCallback mBtleGattCallback = new BluetoothGattCallback() {

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            Log.w(TAG, "BluetoothGattCallback characteristicChanged: " + characteristic);

            // this will get called anytime you perform a read or write characteristic operation
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            Log.w(TAG, "BluetoothGattCallback stateChanged: " + newState);
            connectionStateChange(gatt, status, newState);
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            Log.w(TAG, "BluetoothGattCallback serviceDiscovered. Status: " + status);

            servicesDiscovered(gatt, status);
        }
    };


    public Discovery(Context context, ParcelUuid uuid, String username, DiscoveryCallback discoveryCallback) {
        this(context, uuid, username, DIStartOptions.DIStartAdvertisingAndDetecting, discoveryCallback);
    }

    public Discovery(Context context, ParcelUuid uuid, String username, DIStartOptions startOptions, DiscoveryCallback discoveryCallback ) {
        mShouldAdvertise = false;
        mShouldDiscover = false;

        this.setContext(context);
        this.setUUID(uuid);
        this.setUsername(username);

        this.setPaused(false);


        this.setUserTimeoutInterval(5);
        this.setScanForSeconds(5);
        this.setWaitForSeconds(5);

        this.setDiscoveryCallback(discoveryCallback);


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

        this.setUsersMap(new HashMap<String, BLEUser>());

        this.setShouldAdvertise(false);
        this.setShouldDiscover(false);

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

    public void startScanForAFewSeconds() {
        if (!getShouldDiscover())
            return;

        startDetecting();

        Runnable runable = new Runnable() {
                @Override
                public void run() {
                    stopScanForAFewSeconds();
                    checkList();
                }
            };
        getHandler().postDelayed(runable, getScanForSeconds() * 1000);
    }

    public void stopScanForAFewSeconds() {
        if (mBluetoothLeScanner != null)
            mBluetoothLeScanner.stopScan(mScanCallback);

        Runnable runable = new Runnable() {
            @Override
            public void run() {
                startScanForAFewSeconds();
            }
        };
        getHandler().postDelayed(runable, getWaitForSeconds() * 1000);
    }

//TODO    - (void)appDidEnterBackground:(NSNotification *)notification {
//        [self stopUpdateTimer];
//    }
//
//TODO    - (void)appWillEnterForeground:(NSNotification *)notification {
//        [self startUpdateTimer];
//    }
//

    private void startAdvertising() {
        if (mBluetoothLeAdvertiser == null)
            return;

        mBluetoothAdapter.setName(getUsername());

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(getUUID())
                .build();

        //// We dont need to add characteristics because android will display our username even in the backgound.
        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null)
            return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    private void startDetecting() {
        if (mBluetoothLeScanner == null)
            return;


        // we only listen to the service that belongs to our uuid
        // this is important for performance and battery consumption
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
        List<ScanFilter> filters = new ArrayList<>();

        // filtering by the ServiceUUID prevents us from discovering iOS devices broadcasting in the background
        // since their serviceUUID gets moved into the 'overflow area'.
        // we need to find a way to filter also by the manufacturerData to find only devices with our UUID.
        // more here: https://forums.developer.apple.com/thread/11705
        filters.add(new ScanFilter.Builder().setServiceUuid(getUUID()).build());

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);
    }


//
//    - (void)peripheralManagerDidUpdateState:(CBPeripheralManager *)peripheral {
//        if(peripheral.state == CBPeripheralManagerStatePoweredOn) {
//            [self startAdvertising];
//        }
//
//        //record the state because it's not accessible thru the peripheral manager
//        self.peripheralManagerState = peripheral.state;
//
//        [self notifyOfChangedState];
//    }
//
//
//
//    - (void)centralManagerDidUpdateState:(CBCentralManager *)central {
//        if (central.state == CBCentralManagerStatePoweredOn) {
//            [self startDetecting];
//        }
//
//        [self notifyOfChangedState];
//    }
//
//    }


    public void updateList() {
        updateList(true);
    }

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
                return s1.getProximity().compareTo(s2.getProximity());
            }
        });


        if (getDiscoveryCallback() != null) {
            getDiscoveryCallback().didUpdateUsers(users, usersChanged);
        }
    }

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
            if (diff > getUserTimeoutInterval()) {
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
//
//    #pragma mark - CBCentralManagerDelegate

    public void scanResult(int callbackType, ScanResult scanResult) {
        List <UUID> serviceUUIDs = parseUUIDs(scanResult.getScanRecord().getBytes());
        Log.d(TAG, serviceUUIDs.toString());
        if (serviceUUIDs == null || !serviceUUIDs.contains(getUUID().getUuid()))
            return;

        String username = scanResult.getDevice().getName();
        BLEUser bleUser = userWithDeviceAddress(scanResult.getDevice().getAddress());
        if (bleUser == null) {
            bleUser = new BLEUser(scanResult);
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
                // nope we could not get the username from CBAdvertisementDataLocalNameKey,
                // we have to connect to the peripheral and try to get the characteristic data
                // add we will extract the username from characteristics.
                scanResult.getDevice().connectGatt(getContext(), false, mBtleGattCallback);
            }
        }
        bleUser.setRssi(scanResult.getRssi());
        bleUser.setUpdateTime(new Date().getTime());
    }

    public void connectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {

        // this will get called when a device connects or disconnects
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices();
        }
    }

//    #pragma mark - CBPeripheralDelegate
    public void servicesDiscovered(final BluetoothGatt gatt, final int status) {

        // this will get called after the client initiates a BluetoothGatt.discoverServices() call
        BluetoothGattService service = gatt.getService(getUUID().getUuid());
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic characteristic : characteristics) {
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
    }

    public String getUsername() {
        return mUsername;
    }

    public void setUsername(String mUsername) {
        this.mUsername = mUsername;
    }

    public ParcelUuid getUUID() {
        return mUUID;
    }

    public void setUUID(ParcelUuid mUUID) {
        this.mUUID = mUUID;
    }

    public Boolean getPaused() {
        return mPaused;
    }

    public void setPaused(Boolean paused) {
        if (this.mPaused == paused)
            return;
        this.mPaused = paused;

        if (paused) {
            this.mBluetoothLeScanner.stopScan(mScanCallback);
        } else {
            startScanForAFewSeconds();
        }
    }

    public Integer getUserTimeoutInterval() {
        return mUserTimeoutInterval;
    }

    public void setUserTimeoutInterval(Integer mUserTimeoutInterval) {
        this.mUserTimeoutInterval = mUserTimeoutInterval;
    }

    public Boolean getShouldAdvertise() {
        return mShouldAdvertise;
    }

    public void setShouldAdvertise(Boolean shouldAdvertise) {
        if (this.mShouldAdvertise == shouldAdvertise)
            return;

        this.mShouldAdvertise = shouldAdvertise;

        if (shouldAdvertise) {
            if (mBluetoothAdapter == null) {
                BluetoothManager manager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
                mBluetoothAdapter = manager.getAdapter();
            }

            if (mBluetoothLeAdvertiser == null) {
                mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            }
            startAdvertising();
        } else {
            if (mBluetoothLeAdvertiser != null) {
                mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
            }
        }
    }

    public Boolean getShouldDiscover() {
        return mShouldDiscover;
    }

    public void setShouldDiscover(Boolean shouldDiscover) {
        if (this.mShouldDiscover == shouldDiscover)
            return;

        this.mShouldDiscover = shouldDiscover;

        if (shouldDiscover) {
            if (mBluetoothAdapter == null) {
                BluetoothManager manager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
                mBluetoothAdapter = manager.getAdapter();
            }

            if (mBluetoothLeScanner == null) {
                mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }

            startScanForAFewSeconds();
        } else {
            if (mBluetoothLeScanner != null) {
                mBluetoothLeScanner.stopScan(mScanCallback);
                checkList();
            }
        }
    }

    public Map<String, BLEUser> getUsersMap() {
        return mUsersMap;
    }

    public void setUsersMap(Map<String, BLEUser> mUsersMap) {
        this.mUsersMap = mUsersMap;
    }

    public Context getContext() {
        return mContext;
    }

    public void setContext(Context mContext) {
        this.mContext = mContext;
    }

    public DiscoveryCallback getDiscoveryCallback() {
        return mDiscoveryCallback;
    }

    public void setDiscoveryCallback(DiscoveryCallback mDiscoveryCallback) {
        this.mDiscoveryCallback = mDiscoveryCallback;
    }

    public Integer getScanForSeconds() {
        return mScanForSeconds;
    }

    public void setScanForSeconds(Integer scanForSeconds) {
        this.mScanForSeconds = scanForSeconds;
    }

    public Integer getWaitForSeconds() {
        return mWaitForSeconds;
    }

    public void setWaitForSeconds(Integer waitForSeconds) {
        this.mWaitForSeconds = waitForSeconds;
    }

    public Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
        return mHandler;
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
}
