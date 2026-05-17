# anyapk Functionality Map

## Purpose

`anyapk` is an Android app that installs APK files by talking to the device's own local ADB daemon over wireless debugging. Instead of handing installation to the normal package installer flow, it pairs once with local ADB and then streams APKs through `cmd package install`.

At a high level, the app provides:

- one-time pairing with wireless ADB
- APK installation from the app itself or from external file managers
- connection testing and authorization prompting
- self-update checks against GitHub releases
- in-app download and install of updates via the same ADB path

## Platform and Constraints

- Package name: `com.anyapk.installer`
- Minimum Android version: Android 11 / API 30
- Target SDK: 34
- Current app version in source: `0.0.5` (`versionCode = 5`)
- Main requirement: wireless debugging must be available and enabled

## App Entry Points

### `MainActivity`

This is the launcher activity and the control center for setup and maintenance.

Primary responsibilities:

- checks whether the app is already connected to local ADB
- shows a setup checklist when not connected
- starts the pairing flow
- requests notification permission for the pairing notification flow
- lets the user test the connection and trigger ADB authorization
- opens a file picker to choose an APK manually
- checks GitHub for app updates

User-visible states:

- connected/ready
- setup required
- authorization required

### `InstallActivity`

This is the APK handler activity. It is exported and registered for APK MIME types and install intents, so other apps can send APK files to it.

It is responsible for:

- receiving an APK `Uri` from another app or from `MainActivity`
- copying that APK into app cache as a temporary file
- invoking the ADB install flow
- showing success/failure to the user

This is what enables "open APK with anyapk" from a file manager, browser, or downloads app.

## Android Manifest Behavior

The manifest enables several important behaviors:

- launcher entry through `MainActivity`
- exported APK handling through `InstallActivity`
- pairing notification foreground service through `PairingInputService`
- notification reply handling through `PairingInputReceiver`
- `FileProvider` support for file sharing during updates

Declared permissions:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `REQUEST_INSTALL_PACKAGES`
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`

## Core Functional Areas

## 1. ADB Identity and Connection Management

### `AdbConnectionManager`

This class extends LibADB's `AbsAdbConnectionManager` and owns the app's ADB identity.

Responsibilities:

- generates an RSA keypair and certificate if none exist
- persists the private key and certificate in `SharedPreferences`
- reloads those credentials on later launches
- identifies the client to ADB as `anyapk`

Important detail:

- pairing persistence depends on reusing the same stored ADB keys

Stored data:

- preference file: `adb_keys`
- fields: base64-encoded private key and X.509 certificate

### `AdbInstaller`

This object is the functional API over ADB. It contains the actual pairing, connection-test, and install logic.

Responsibilities:

- determines connection status
- pairs with the local device via ADB pairing
- tests authorization by running a shell command
- installs APKs by streaming bytes into package manager install

Connection assumptions:

- host is always `127.0.0.1`
- normal ADB connection port defaults to `5555`
- pairing uses the pairing port shown by Android wireless debugging

Connection status model:

- `NOT_CONNECTED`
- `CONNECTED`
- `NEEDS_PAIRING`
- `ERROR`

In practice, the current code mostly collapses failures into `NEEDS_PAIRING`.

## 2. Pairing Flow

The pairing flow is split across the main UI, a foreground service, and a broadcast receiver.

### Pairing steps in code

1. `MainActivity` shows setup steps and exposes `Start Pairing`.
2. Tapping `Start Pairing` starts `PairingInputService`.
3. The service posts a foreground notification with a `RemoteInput` reply action.
4. The user opens Android developer settings and views the pairing code and port.
5. The user enters `CODE PORT` into the notification reply field.
6. `PairingInputReceiver` parses the reply and calls `AdbInstaller.pair(...)`.
7. Success/failure is shown using notifications and toasts.

### `PairingInputService`

Responsibilities:

- runs as a short foreground service
- creates the notification channel
- posts a high-priority notification
- provides a `RemoteInput` action for entering pairing code and port

Expected input format:

- `123456 37829`

### `PairingInputReceiver`

Responsibilities:

- receives the reply action from the notification
- validates input format
- parses code and port
- calls `AdbInstaller.pair(...)`
- updates the notification to show progress/success/error
- stops the service on success

## 3. Connection Testing and Authorization

After pairing, Android may still require the user to approve the ADB client.

### Trigger path

- `MainActivity.testConnection()`
- `AdbInstaller.testConnection()`

What happens:

- app attempts local ADB auto-connect
- app opens `shell:echo test`
- if Android shows the "Allow USB debugging?" prompt, the user must approve it
- successful command execution marks the connection as usable

This is the path intended to move the user from "paired but not yet authorized" to fully usable.

## 4. APK Installation Flow

There are two ways into installation:

- external app sends an APK to `InstallActivity`
- user picks an APK in `MainActivity`, which launches `InstallActivity`

### Installation sequence

1. `InstallActivity` receives an APK `Uri`.
2. The APK is copied into `cacheDir/temp_install.apk`.
3. `AdbInstaller.install(...)` creates a fresh ADB manager instance.
4. It auto-connects to local ADB.
5. It opens an ADB stream:
   - `exec:cmd package install -S <apkSize>`
6. It streams the APK bytes to the package manager command.
7. It reads command output until success/failure.
8. Temporary file is deleted.

Success condition:

- output contains `Success`

Failure condition:

- any exception, timeout, or package manager response without `Success`

## 5. APK Handler Integration

`InstallActivity` is exported with intent filters for:

- `android.intent.action.VIEW`
- `android.intent.action.INSTALL_PACKAGE`
- MIME type `application/vnd.android.package-archive`
- `content://` and `file://` schemes

This is the integration that makes the app behave like an installer choice in other apps.

## 6. Update System

The app has a built-in GitHub release updater.

### `UpdateChecker`

Responsibilities:

- queries GitHub Releases API for the latest release
- currently hardcoded to `sam1am/anyapk`
- compares GitHub version against installed app version
- finds the first APK asset in the release
- returns version metadata and release notes

Update source:

- `https://api.github.com/repos/sam1am/anyapk/releases/latest`

### `UpdateManager`

Responsibilities:

- downloads the update APK directly via HTTP
- stores it in app cache
- reports download progress back to the UI
- installs the downloaded APK via `AdbInstaller.install(...)`

Important behavior:

- self-update is expected to interrupt or kill the running app during replacement

### UI flow

1. user taps `Check for Updates`
2. app checks GitHub latest release
3. if newer version exists, app shows an update dialog
4. user chooses `Download & Install`
5. app downloads the APK
6. app installs the update through the same local ADB install mechanism

## 7. Data and State

Persistent app state visible in code:

- ADB private key and certificate in `SharedPreferences`

Short-lived state:

- cached APK selected for installation
- cached downloaded update APK
- in-memory connection status cache in `AdbInstaller`

Connection caching:

- `AdbInstaller` caches recent connection status for 2 seconds to avoid repeated reconnect attempts

## Main User Flows

### First-time setup flow

1. Open app.
2. App checks connection state.
3. If not connected, it shows setup checklist.
4. User enables developer options if needed.
5. User grants notification permission if needed.
6. User starts pairing.
7. User enters pairing code and port via notification reply.
8. User authorizes ADB debugging when prompted.
9. App becomes ready.

### Install from file manager flow

1. User taps an APK in another app.
2. Android offers `anyapk` as a handler.
3. `InstallActivity` receives the APK.
4. User taps install.
5. App installs via local ADB.

### Install from built-in picker flow

1. User opens `MainActivity`.
2. Taps `Select APK to Install`.
3. Chooses an APK from storage.
4. `InstallActivity` is launched with the selected `Uri`.
5. Install proceeds through ADB.

### Self-update flow

1. User checks for updates.
2. App queries GitHub latest release.
3. App downloads release APK into cache.
4. App installs it via local ADB.

## External Dependencies and What They Do

- `libadb-android`
  - ADB client functionality on Android
- `conscrypt-android`
  - TLS/security provider support used by the ADB stack
- `sun-security-android`
  - certificate/key generation helpers used for ADB identity
- AndroidX lifecycle/coroutines/material libraries
  - UI and async execution support

## Current Architectural Shape

The codebase is small and centers around one main idea:

- `MainActivity` owns UX and setup
- `InstallActivity` owns install requests
- `AdbInstaller` owns ADB operations
- `AdbConnectionManager` owns persistent ADB identity
- pairing service/receiver own the notification-based pairing input
- update classes own GitHub release detection and self-update

## Notable Implementation Details

- The app talks only to `127.0.0.1`, so it is designed for self-install on the same device.
- Installation uses `cmd package install -S`, meaning APK bytes are streamed directly instead of relying on standard installer UI.
- Pairing is intentionally optimized for split-screen/notification entry rather than custom in-app forms.
- The update checker still points to the upstream repo directly, which matters if this project is later rebranded or forked.

## Open Questions / Things to Verify Later

- `README.md` and `USAGE.md` are not fully aligned with the current implementation in every detail.
- `UpdateChecker` contains a TODO for changing the GitHub repo target.
- Some manifest permissions and update-related code suggest features that may evolve further.
- The app currently stores ADB credentials in `SharedPreferences`; the source itself notes that Android KeyStore would be stronger for production use.
