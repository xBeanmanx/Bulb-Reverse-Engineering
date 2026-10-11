# Bulb Reverse Engineering

I have one of those cheap Bluetooth speaker bulbs (it shows up as "smart bulb"). The only app for it was
JBSmartBulb 2.9 from 2016. It doesn't work on current Android and its source code is gone, so I pulled
the protocol out of the old APK and wrote a new app from scratch.

The new app is Kotlin Multiplatform with a Material 3 UI. It runs on Android, and the protocol code
also builds for desktop and iOS.

What it does:

- **White**: brightness plus a cold to warm slider
- **Colour**: pick any hue
- **Fade**: your own list of colours (colour wheel or `#RRGGBB`), adjustable fade time in ms, smooth
  eased transitions. "Load default" gives you a full rainbow loop.
- **Sound**: use the bulb as your phone's speaker, plus volume and EQ presets
- **Settings**: sleep timer, rename the bulb, and a raw log of everything sent to and from the bulb

If you just want the app, grab the APK from the [Releases](../../releases) page.

The protocol write-up (frame format, BLE/SPP details, every command) is in
[docs/PROTOCOL.md](docs/PROTOCOL.md).

## Building it yourself

You need:

- JDK 17 or newer
- The Android SDK with platform 36 (installing Android Studio is the easiest way to get it)
- `adb` if you want to install from the command line

1. Clone the repo:

   ```sh
   git clone https://github.com/xBeanmanx/Bulb-Reverse-Engineering.git
   cd Bulb-Reverse-Engineering
   ```

2. Tell Gradle where the SDK is. Either set `ANDROID_HOME`, or create a `local.properties` file:

   ```properties
   sdk.dir=/path/to/Android/sdk
   ```

3. Build the APK. The first build downloads Gradle and the dependencies, so give it a few minutes.

   ```sh
   ./gradlew :composeApp:assembleDebug
   ```

   The APK ends up in `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

4. Install it. With the phone plugged in and USB debugging on:

   ```sh
   ./gradlew :composeApp:installDebug
   ```

   No cable? Turn on Wireless debugging on the phone, tap "Pair device with pairing code", then:

   ```sh
   adb pair <ip>:<pairing-port> <code>
   adb connect <ip>:<port>
   ./gradlew :composeApp:installDebug
   ```

   Or just copy the APK to the phone and open it.

The debug build also lists a "Demo bulb (simulated)", so you can click around without a bulb.

### Tests

```sh
./gradlew :shared:desktopTest
```

The protocol tests compare every command byte for byte with what the original app sends.

### Signed release build

Create a `keystore.properties` in the project root (it's gitignored) that points to your own keystore:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew :composeApp:assembleRelease`. Without that file the release APK comes out unsigned.

## Using it

1. Open the app and allow Bluetooth access.
2. Tap **Scan** and pick your bulb. It connects over BLE, nothing to pair.
3. For music, go to **Sound → Connect speaker**. If pairing times out, switch the bulb off and on at
   the wall and try again straight away.

## Notes

- Tested with a "smart bulb" that reports model `Model-BL-05`. Other bulbs that used the
  JBSmartBulb app might work too, but I only had this one to test with.
- [mertemr/chsmartbulb](https://github.com/mertemr/chsmartbulb) reverse engineered a very similar
  bulb and helped a lot with the BLE side.
- Not affiliated with the original app or the bulb's maker. I did this so I could keep using my own bulb.
