# Discovery
Android port of https://github.com/omergul123/Discovery
 
##Install

Add to your bundle.gradle (module) dependencies

````java
dependencies {
   ...
   compile "com.joshblour.discovery:discovery:0.0.1"
   ...
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

##Problems

Can't detect iOS devices while they are in the background. This is because we are using a ScanFilter for the ServiceUUID to save battery. When an iOS app goes into the background, Apple moved all serviceUUIDs into a special 'overflow area' and our filter no longer picks them up
