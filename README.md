# Discovery
Android port of https://github.com/omergul123/Discovery
Discover nearby devices using BLE.

##What
Discovery is a very simple but useful library for discovering nearby devices with BLE(Bluetooth Low Energy) and for exchanging a value (kind of ID or username determined by you on the running app on peer device) regardless of whether the app on peer device works at foreground or background state.

##Why
I ported [Discovery](https://github.com/omergul123/Discovery) from iOS to Android because I needed a beacon technology that worked cross-platform. I chose Discovery for its straightforward implementation and reliability in the background. You can read more about why Discovery was necessary for iOS here: https://github.com/omergul123/Discovery#the-concept-the-problem-and-why-we-need-discovery

##Install

Add to your bundle.gradle (module) dependencies

````java
dependencies {
   //...
   compile "com.joshblour.discovery:discovery:0.0.3"
   //...
}
````
##Example usage

````java
public class MainActivity extends Activity implements Discovery.DiscoveryCallback {
    public static final ParcelUuid uuidStr = ParcelUuid.fromString("B9407F30-F5F8-466E-AFF9-25556B57FE99");
    public static final String username = "myUsername";
    
    private Discovery mDiscovery;
    
    //...boilerplate onCreate code...
    
    public void startDiscovery() {
        mDiscovery = new Discovery(getApplicationContext(), uuidStr, username, this);
    }

    @Override
    public void didUpdateUsers(ArrayList<BLEUser> users, Boolean usersChanged) {
        //reload your table from users
    }
}
````

##API
`public Discovery(Context context, ParcelUuid uuid, String username, DIStartOptions startOptions, DiscoveryCallback discoveryCallback )`
  - `uuid`: A UUID that identifies your application. If you want to discover iOS devices, both libraries need to be using the same UUID
  - `username`: The username to include in the broadcast. Note: in this implementation, we can only broadcast a max of 7 characters. You can use longer usernames but nearby devices will need to connect in order to read it. (the username is then cached so this only happens once per discovery)
  - `startOptions`:
     - DIStartAdvertisingAndDetecting
     - DIStartAdvertisingOnly
     - DIStartDetectingOnly
     - DIStartNone
  - `discoveryCallback`: implements `didUpdateUsers(ArrayList<BLEUser> users, Boolean usersChanged)`

`public Discovery(Context context, ParcelUuid uuid, String username, DiscoveryCallback discoveryCallback)` - same as above but starts using `DIStartAdvertisingAndDetecting`

`public void setPaused(Boolean paused)` - pauses advertising and detection

`public void setShouldDiscover(Boolean shouldDiscover)` - starts and stops discovery only

`public void setShouldAdvertise(Boolean shouldAdvertise)` - starts and stops advertising only

`public void setUserTimeoutInterval(Integer mUserTimeoutInterval)` - in seconds, default is 5. After not seeing a user for x seconds, we remove him from the users list in our callback.
  
  
*The following two methods are specific to the Android version, since the Android docs advise against continuous scanning. Instead, we cycle scanning on and off. This also allows us to modify the scan behaviour when the app moves to the background.*

`public void setScanForSeconds(Integer scanForSeconds)` - in seconds, default is 5. This parameter specifies the duration of the ON part of the scan cycle.
    
`public void setWaitForSeconds(Integer waitForSeconds)` - in seconds default is 5. This parameter specifies the duration of the OFF part of the scan cycle.

##Problems

~~Can't detect iOS devices while they are in the background. This is because we are using a ScanFilter for the ServiceUUID to save battery. When an iOS app goes into the background, Apple moved all serviceUUIDs into a special 'overflow area' and our filter no longer picks them up~~ (disabled scan filters because I needed to detect iOS devices when they are in the background)
