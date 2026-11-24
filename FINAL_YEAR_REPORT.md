# Mesh Net — Final Year Project Report Reference

**Project Title:** Mesh Net: Decentralized Peer-to-Peer Offline Communication Over Bluetooth Low Energy (BLE)  
**Platform:** Android Mobile Application (Kotlin / Jetpack Compose) + Companion Web Distribution Portal (HTML/CSS/JS)  
**Package / Application ID:** `com.aistudio.bluewave.p2pbtq`  
**Version:** `1.0` (`minSdk 24` / Android 7.0+ — `targetSdk 36` / Android 16)

---

## 1. Abstract & Problem Statement

Modern messaging platforms depend almost entirely on centralized servers, cellular base stations, or Wi-Fi/internet infrastructure. During natural disasters, power grid failures, rural expeditions, crowded events with congested cell towers, or network blackouts, conventional messaging applications fail completely.

**Mesh Net** solves this problem by creating a zero-infrastructure, decentralized, peer-to-peer (P2P) mesh messaging system built exclusively on **Bluetooth Low Energy (BLE)**. Nearby Android smartphones automatically discover one another, establish dual-role GATT links without manual Bluetooth pairing, and relay messages across multiple hops (store-and-forward) while protecting private direct messages with **ECDH + AES-256-GCM** end-to-end encryption.

---

## 2. System Architecture Overview

Mesh Net consists of two tightly scoped components:

1. **Mobile Application (Android — Kotlin / Jetpack Compose):**
   - Implements the **MVVM + Repository** architecture (`MainActivity` -> `BlueWaveApp` -> `MeshConsoleScreen` / `MeshPeersPanel` -> `BlueWaveViewModel` -> `BlueWaveRepository`).
   - Drives a custom **Dual-Role BLE Mesh Engine** (`BleMeshManager`) that acts simultaneously as a BLE Peripheral (GATT Server + Advertiser) and a BLE Central (Scanner + GATT Client).
   - Stores chat history and peer keys locally via **Room (SQLite)** and node identity/preferences via **Jetpack DataStore**.
2. **Companion Distribution Web App (`website/`):**
   - A lightweight static landing and APK distribution portal (`index.html`, `styles.css`, `app.js`) providing offline architecture documentation, IRC-style command references, sideload instructions, and direct `MeshNet-v1.0.apk` download with a verifiable **SHA-256** checksum.

### High-Level Data Flow

```text
[Compose UI: MeshConsoleScreen / MeshPeersPanel]
          │  StateFlow<BlueWaveUiState> / User Actions
          ▼
[ViewModel: BlueWaveViewModel]
          │  Slash Command Parser & Room Routing
          ▼
[Repository: BlueWaveRepository] ◄──────────────► [Room DB + DataStore]
          │  Encrypt/Decrypt DMs (MeshCrypto)
          ▼
[BLE Mesh Engine: BleMeshManager]
   ├── GATT Server + BLE Advertiser (Receives writes, sends Notify chunks)
   ├── BLE Scanner + GATT Client  (Scans UUID, connects, requests MTU 512)
   ├── MeshPacketFramer           (Fragments & reassembles binary chunks)
   └── Multi-Hop Relay + Buffer   (Deduplication cache + Store-and-Forward)
```

---

## 3. Frameworks, Libraries & Technologies Used

### 3.1 Mobile Application Stack

| Layer | Technology / Framework | Version / Spec | Purpose in Mesh Net |
| :--- | :--- | :--- | :--- |
| **Language** | Kotlin + Coroutines & Flow | Kotlin `2.4.21`, Coroutines `1.10.2` | Asynchronous reactive streams, non-blocking BLE I/O, structured concurrency (`SupervisorJob`, `Dispatchers.IO`, `Mutex`, `CompletableDeferred`) |
| **UI Framework** | Jetpack Compose + Material 3 | Compose BOM `2024.09.00` | Declarative UI, adaptive side-pane/bottom-sheet layout (`BoxWithConstraints`), Light/Dark dynamic color themes (`MeshNetTheme`), `WindowInsets.safeDrawing` & IME handling |
| **Architecture** | Android Jetpack ViewModel + Lifecycle | `2.8.7` | State management (`StateFlow`, `collectAsStateWithLifecycle`) and lifecycle-aware BLE state refresh (`ON_RESUME`) |
| **Bluetooth Layer** | Android BLE Framework (`android.bluetooth.*`) | `minSdk 24` – `targetSdk 36` | `BluetoothLeAdvertiser`, `BluetoothLeScanner`, `BluetoothGatt`, `BluetoothGattServer`, ATT MTU negotiation (`requestMtu(512)`), `TRANSPORT_LE` |
| **Serialization** | Moshi + KSP Codegen | `1.15.2` (KSP `2.3.5`) | Fast reflection-free JSON serialization/deserialization of `MeshPacket` payloads (`@JsonClass(generateAdapter = true)`) |
| **Cryptography** | Java Cryptography Architecture (`javax.crypto`, `java.security`) | JCA Native (`secp256r1`, AES-GCM) | Ephemeral/persistent P-256 **ECDH** key agreement, **SHA-256** KDF & peer ID derivation, **AES-256-GCM** authenticated encryption with AAD |
| **Local Database** | AndroidX Room (SQLite) | `2.7.0` | Stores room/DM messages (`MessageEntity`, indexed by `roomId, timestamp`) and verified peer public keys (`PeerEntity`) |
| **Preferences** | Jetpack DataStore (Preferences) | `1.1.7` | Persists node keypair, nickname, joined `#channels`, haptic ping toggle, and Light/Dark/System theme choice |
| **Typography & Assets** | Bundled TTF Fonts + Adaptive Icons | `Plus Jakarta Sans`, `JetBrains Mono` | Clean native messaging readability (`Plus Jakarta Sans`) and monospace identifiers (`JetBrains Mono`), plus adaptive launcher icons across `mdpi`–`xxxhdpi` |
| **Testing & Build** | Gradle KTS, AGP, JUnit 4, Robolectric | AGP `9.1.1`, JUnit `4.13.2`, Robolectric `4.16.1` | Protocol fragmentation/crypto unit tests (`BluetoothProtocolTest`), Robolectric instrumentation-free Android tests, debug APK builds |

### 3.2 Web Application Stack (`website/`)

| Component | Technology | Purpose |
| :--- | :--- | :--- |
| **Structure** | Semantic HTML5 | Landing page sections (Hero, Live UI Preview, Architecture Grid, Slash Commands, Sideload Guide) |
| **Styling** | CSS3 (Custom Properties, Grid, Flexbox) | Responsive desktop/mobile layout matching the mobile app's Cobalt & Graphite visual system |
| **Interactivity** | Vanilla JavaScript (ES6+) | One-click SHA-256 checksum copy via `navigator.clipboard.writeText` |
| **Security** | Content Security Policy (`<meta>` CSP) | Restricts `default-src`, `img-src`, and `script-src` to `'self'`, eliminating third-party script execution |
| **Delivery** | Direct Binary Download (`<a>` `download`) | Serves `MeshNet-v1.0.apk` (~24 MB) directly with published SHA-256 integrity verification |

---

## 4. Deep Dive: Pure Bluetooth Mesh Protocol & Security

### 4.1 Zero-Pairing Dual-Role BLE Architecture
Traditional Bluetooth Classic requires manual device discovery, pairing PIN/confirmation dialogs, and bonding. Mesh Net bypasses pairing altogether using BLE GATT:
- **GATT Service UUID:** `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`
- **Mesh Characteristic UUID:** `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` (supports `WRITE`, `WRITE_NO_RESPONSE`, `NOTIFY`, `READ`)
- **CCCD Descriptor:** `00002902-0000-1000-8000-00805f9b34fb` (enables GATT notifications)

Every device simultaneously:
1. **Advertises** the Mesh Service UUID (`AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY`, `ADVERTISE_TX_POWER_HIGH`, `setIncludeDeviceName(false)` for privacy).
2. **Scans** strictly for peers advertising the exact Mesh Service UUID (`ScanFilter` + `SCAN_MODE_LOW_LATENCY`).
3. **Connects as GATT Client**, requests `MTU = 512`, discovers services, and subscribes to notifications.
4. **Hosts a GATT Server**, accepting incoming writes and pushing notifications to subscribed neighbors.

### 4.2 Custom Binary Framing (`MeshPacketFramer`)
Because BLE ATT packets vary in payload capacity (`20` bytes default up to `480` bytes after MTU negotiation), `MeshPacketFramer` splits serialized UTF-8 JSON packets into framed binary chunks:
- **5-Byte Binary Header:**
  - `Byte 0`: Magic byte `0xB1` (frame validation)
  - `Bytes 1–2`: 16-bit payload tag (`contentHashCode & 0xFFFF`)
  - `Byte 3`: Zero-based chunk index
  - `Byte 4`: Total chunk count
- **DoS & Memory-Exhaustion Guards:**
  - Maximum `64` chunks per packet (`MAX_CHUNKS = 64`)
  - Maximum `512` bytes per frame (`MAX_FRAME_BYTES = 512`)
  - Maximum `32 KB` assembled message size (`MAX_ASSEMBLED_BYTES = 32 * 1024`)
  - Maximum `32` concurrent partial reassembly buffers (`MAX_PENDING_BUFFERS = 32`) with `15-second` stale buffer pruning.

### 4.3 Multi-Hop Flooding & Store-and-Forward Routing
- **Packet Types:** `ANNOUNCE` (heartbeat every 20s + on link ready), `PUBLIC` (`#mesh`), `CHANNEL` (`#general`, `#sos`, custom `#topics`), `DM_ENC` (encrypted private message), `PING` (tactile alert).
- **Loop Prevention:** Every node maintains a bounded FIFO `LinkedHashSet` of the last `500` seen packet UUIDs (`MAX_SEEN_PACKETS = 500`). Duplicate or self-originated packets are dropped immediately.
- **Hop Forwarding:** Relayable packets start with `ttl = 5` and `hopCount = 0`. When a node receives a non-local packet with `ttl > 1` (and not a `DM_ENC` addressed to itself), it decrements `ttl`, increments `hopCount`, and forwards it to all connected neighbors except `sourceAddress`.
- **Store-and-Forward:** Up to `30` recent packets (`STORE_FORWARD_MAX = 30`) from the last `5 minutes` (`STORE_FORWARD_WINDOW_MS`) are buffered in memory and automatically replayed when a new BLE neighbor finishes GATT handshake (`onNeighborLinkReady`).

### 4.4 End-to-End Cryptography (`MeshCrypto`)
- **Identity Generation:** Each node generates an Elliptic Curve keypair on curve `secp256r1` (NIST P-256).
- **Cryptographic Peer ID:** Derived as the first 4 bytes (8 hex characters) of `SHA-256(X.509 PublicKey)`.
- **Anti-Spoofing Key Verification & Pinning:** Before accepting any peer announcement or message, `MeshCrypto.verifyPeerPublicKey()` validates the ASN.1 X.509 key, ensures it sits on a 256-bit EC curve, and verifies `derivePeerId(pubBytes) == packet.senderId`. Known keys are pinned in Room (Trust On First Use) and mismatched key updates are rejected.
- **Authenticated Encryption for DMs:**
  1. `ECDH` computes the raw shared secret between sender private key and recipient public key (rejecting all-zero invalid secrets).
  2. `SHA-256("BlueWave-ECDH-v1:" || rawShared)` derives a 256-bit AES key.
  3. **AES-256-GCM** encrypts the plaintext using a fresh 12-byte `SecureRandom` IV and a 128-bit authentication tag, binding **AAD = `"senderId->targetPeerId"`** so relay nodes cannot tamper with or swap sender/recipient metadata.

### 4.5 Privacy & Permissions Model
- **Android 12+ (`API 31+`):** Requests only `BLUETOOTH_SCAN` (marked with `android:usesPermissionFlags="neverForLocation"`), `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE`.
- **Android 7–11 (`API 24–30`):** Requests `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION` (required by the legacy Android OS BLE scan API).
- **Zero-Telemetry / Ephemeral Design:** No accounts, phone numbers, servers, analytics, or cloud backups (`android:allowBackup="false"`). Users can execute **Panic Wipe** (UI menu or `/wipe`) at any time to delete all messages, clear peer directories, flush BLE caches, and rotate to a fresh keypair.

---

## 5. Features Implemented

- **Offline `#channels` & Public Broadcasts:** Pre-configured `#mesh`, `#general`, `#sos`, plus custom `#channel` joining via UI dialog or `/join #topic`.
- **End-to-End Encrypted Direct Messages:** Tap any peer in the Peers sheet or any `@handle` in chat to start an encrypted `dm:<peerId>` session.
- **IRC-Style Slash Commands:** `/nick`, `/join`, `/msg` (or `/dm`), `/who`, `/ping`, `/clear`, `/wipe`, `/help` with interactive chip autocomplete when typing `/`.
- **Live BLE Telemetry & Signal Quality:** Shows advertising/scanning state, direct link count, relayed packet counter, and RSSI signal bars (`dBm`) per peer.
- **Adaptive & Polished Native UI:** Light, Dark, and System theme modes; responsive tablet/foldable split-pane view (`>= 840dp`); grouped chat bubbles with long-press clipboard copy; keyboard-aware scroll & `ImeAction.Send` composer.

---

## 6. Limitations & Technical Constraints

### 6.1 Pure Bluetooth Communication Limitations
1. **Short Physical Range & Obstruction Sensitivity:** BLE operates on the `2.4 GHz` ISM band, yielding ~10–30 m indoors and ~50–100 m outdoors line-of-sight. Concrete walls, metal structures, and human bodies absorb 2.4 GHz RF energy, causing rapid RSSI drops.
2. **Low Throughput & Multi-Hop Latency:** BLE GATT writes and notifications (20–480 bytes per frame) are optimized for small control/text payloads rather than high-bandwidth streams. Sending images, audio, or large files across multiple hops would saturate links and cause high packet loss.
3. **Device Concurrent Connection Limits:** Most mobile Bluetooth controllers cap simultaneous active BLE connections at **4 to 7 peers** and restrict background advertising/scanning intervals.
4. **OS Background & OEM Battery Optimization:** Without a persistent Android `ForegroundService` (`connectedDevice` type), Android Doze mode and aggressive vendor battery managers (e.g., MIUI, ColorOS, OneUI) pause BLE scanning and advertising when the screen is turned off.
5. **Android OS Scan Throttling & Legacy Location Requirement:** On Android 7+, starting and stopping scans more than 5 times in 30 seconds triggers OS throttling; on Android 11 and below, BLE scanning fails unless system Location Services are enabled.
6. **Hardware Diversity:** Some low-end Android devices support BLE Central scanning but lack hardware support for BLE Peripheral Advertising (`bluetoothLeAdvertiser == null`), limiting them to client-only connections.
7. **Managed Flooding Overhead:** Flooding with `TTL = 5` guarantees simplicity without routing tables, but in very dense crowds it can cause duplicate packet transmissions across shared neighbors before deduplication filters them.

### 6.2 Mobile Application (`Mesh Net`) Limitations
1. **Text-Only Messaging:** Supports UTF-8 text, slash commands, and tactile pings; multimedia attachments (photos, voice notes) are intentionally excluded to respect BLE bandwidth constraints.
2. **Android-Only Ecosystem:** Built against Android's `BluetoothGatt` / `BluetoothGattServer` APIs; cross-OS communication with iOS would require matching CoreBluetooth peripheral background service handling and binary framing compatibility.
3. **Channel Messages Are Not End-to-End Encrypted:** While `dm:<peerId>` messages use `ECDH + AES-256-GCM`, public `#mesh` and `#channel` broadcasts are transmitted in cleartext so intermediate nodes can inspect channels and relay packets.
4. **Static Ephemeral ECDH vs. Forward-Secret Ratcheting:** DMs derive a shared key from each node's current identity keypair (rotating only on Panic Wipe) rather than using an ephemeral Double Ratchet protocol per message; additionally, peer IDs use 32-bit prefixes (`8` hex chars) and **Trust On First Use (TOFU)** without out-of-band QR key-fingerprint verification.
5. **Unencrypted Local Room Storage:** Room SQLite persists messages in plain text inside app-private storage (relying on Android File-Based Encryption, `allowBackup="false"`, and manual Panic Wipe rather than SQLCipher passphrase locking).
6. **Debug Keystore Signing:** The sideload APK is currently signed with a development debug certificate rather than a production Google Play release signing key.

### 6.3 Web Application (`website/`) Limitations
1. **No In-Browser Bluetooth Mesh Participation:** Web browsers cannot join the BLE mesh network directly. The **Web Bluetooth API** only exposes Central (client) functionality in select Chromium browsers and provides **no GATT Server / BLE Peripheral Advertising API**, and is unsupported in Firefox and iOS Safari. Therefore, the web app serves purely as a distribution and documentation portal.
2. **Static Metadata & Checksum Updates:** Version tags, APK file size, and SHA-256 checksums are manually maintained in `index.html` rather than dynamically served from a CI/CD release backend.
3. **Android Sideload Friction:** Installing APKs outside Google Play requires users to allow *"Install unknown apps"* in Android settings and bypass Play Protect unknown-publisher prompts.
4. **Desktop-to-Phone Transfer Step:** Desktop visitors must transfer the downloaded `.apk` to an Android phone via USB cable, local network share, or cloud storage (no auto-generated mobile QR code handoff yet).

---

## 7. Future Enhancements

- **Foreground Mesh Service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`):** Keep BLE scanning/advertising alive reliably while the screen is locked or during emergencies.
- **Double Ratchet / Ephemeral Pre-Keys & QR Safety Numbers:** Add forward secrecy for DMs and visual QR/fingerprint comparison to eliminate TOFU impersonation risk.
- **Channel Pre-Shared Keys (PSKs):** Allow password/key-protected private `#channels` using symmetric AES-GCM group keys.
- **Adaptive Routing & Compression:** Replace pure flooding with neighbor-table routing (e.g., distance-vector / AODV-style hints) and apply Zstd/Deflate payload compression to cut BLE frame counts.
- **SQLCipher Encrypted Database:** Encrypt the local Room database at rest with an Android Keystore-backed key.
- **Cross-Platform iOS & Wi-Fi Direct / Nearby Upgrade Path:** Add iOS CoreBluetooth parity and optional Wi-Fi Direct / Wi-Fi Aware fallback when large payloads or higher throughput are required.
