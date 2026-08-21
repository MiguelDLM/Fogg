# 01 — BLE Communication Layer

**Source files:**
- `decompiled_apk/sources/com/szabh/smable3/component/BleConnector.java`
- `decompiled_apk/sources/com/szabh/smable3/component/MessageFactory.java`
- `decompiled_apk/sources/com/szabh/smable3/component/BleParser.java`
- `decompiled_apk/sources/com/szabh/smable3/component/BleMessenger.java`
- `decompiled_apk/sources/com/szabh/smable3/component/BleCache.java`

---

## Table of Contents

1. [GATT Service and Characteristic UUIDs](#1-gatt-service-and-characteristic-uuids)
2. [Packet Frame Format](#2-packet-frame-format)
3. [Header Byte Flags](#3-header-byte-flags)
4. [CRC16 Algorithm](#4-crc16-algorithm)
5. [BLE Parser — Fragment Reassembly](#5-ble-parser--fragment-reassembly)
6. [BLE Messenger — Write Path](#6-ble-messenger--write-path)
7. [Connection Lifecycle](#7-connection-lifecycle)
8. [DFU Address Calculation](#8-dfu-address-calculation)
9. [MTU and Packet Sizing](#9-mtu-and-packet-sizing)
10. [Error Conditions](#10-error-conditions)

---

## 1. GATT Service and Characteristic UUIDs

### 1.1 Nordic UART Service (Primary Data Channel)

The device exposes a Nordic UART Service (NUS) for bidirectional data exchange.

| Role | UUID | Android Operation |
|------|------|------------------|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `discoverServices()` |
| TX (Write) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | `writeCharacteristic()` |
| RX (Notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Enable notifications via CCCD `0x2902` |

**Note:** "TX" and "RX" are named from the **phone's** perspective. The phone writes to `6e400002` (TX) and receives notifications from `6e400003` (RX).

### 1.2 MTK OTA Service (Firmware Update Channel)

Used exclusively during over-the-air firmware updates on MTK-chipset devices.

| Role | UUID |
|------|------|
| OTA Service | `c6a22905-f821-18bf-9704-0266f20e80fd` |
| OTA Data Service | `c6a2b98b-f821-18bf-9704-0266f20e80fd` |
| Flag Characteristic | `c6a22922-f821-18bf-9704-0266f20e80fd` |
| Data Characteristic | `c6a22924-f821-18bf-9704-0266f20e80fd` |
| MD5 Characteristic | `c6a22926-f821-18bf-9704-0266f20e80fd` |
| Meta Characteristic | `c6a22916-f821-18bf-9704-0266f20e80fd` |
| Size Characteristic | `c6a22920-f821-18bf-9704-0266f20e80fd` |

The MTK OTA service uses a fixed packet size of **180 bytes**. See `06-OTA-FIRMWARE.md` for the full OTA procedure.

---

## 2. Packet Frame Format

All application-level messages are wrapped in a fixed frame regardless of the transport (NUS or OTA). The frame is defined in `MessageFactory.java`.

### 2.1 Frame Diagram

```
 0        1        2        3        4        5        6        7        8       9+
 +--------+--------+--------+--------+--------+--------+--------+--------+--------+--------~
 | MAGIC  | HEADER |    LENGTH (BE)   |    CRC16 (BE)   |  CMD   |  KEY   |  FLAG  |  DATA ...
 | 0xAB   |        | high   | low     | high   | low    |        |        |        |
 +--------+--------+--------+--------+--------+--------+--------+--------+--------+--------~
```

### 2.2 Field Definitions

| Offset | Size (bytes) | Field | Type | Description |
|--------|-------------|-------|------|-------------|
| 0 | 1 | MAGIC | `uint8` | Always `0xAB`. Identifies start of frame. |
| 1 | 1 | HEADER | `uint8` | Flags and version. See Section 3. |
| 2–3 | 2 | LENGTH | `uint16` BE | Length of the data region: `payload_length + 3`. The `+ 3` accounts for CMD, KEY, FLAG bytes. |
| 4–5 | 2 | CRC16 | `uint16` BE | CRC16 computed over bytes `[6 .. end_of_packet]`. |
| 6 | 1 | CMD | `uint8` | `BleCommand` raw value = `mKey >>> 8`. |
| 7 | 1 | KEY | `uint8` | Key index = `mKey & 0xFF`. |
| 8 | 1 | FLAG | `uint8` | `BleKeyFlag` raw value. |
| 9+ | N | DATA | `byte[]` | Command payload. May be empty (N=0). |

**Minimum packet size: 9 bytes** (MAGIC + HEADER + LENGTH + CRC16 + CMD + KEY + FLAG, with N=0).

### 2.3 Length Field Calculation

```
LENGTH = (number of DATA bytes) + 3

Examples:
  - No data (N=0):   LENGTH = 3    → bytes [2:4] = 0x00 0x03
  - 4 bytes data:    LENGTH = 7    → bytes [2:4] = 0x00 0x07
  - 256 bytes data:  LENGTH = 259  → bytes [2:4] = 0x01 0x03
```

The receiver allocates a buffer of size `LENGTH + 6` to hold the complete message (6 = MAGIC + HEADER + LENGTH + CRC16 fixed overhead).

### 2.4 Example: SET TIME Packet (no payload)

```
Offset:  00  01  02  03  04  05  06  07  08
Bytes:   AB  01  00  03  XX  XX  02  01  00
         ^   ^   ^---^   ^---^   ^   ^   ^
         |   |    LEN     CRC16  CMD KEY FLAG
       MAGIC HDR  = 3            SET  01  UPDATE
```

CRC bytes (`XX XX`) depend on the data appended after offset 8.

---

## 3. Header Byte Flags

The HEADER byte at offset 1 encodes the protocol version and message direction flags.

```
  Bit:  7   6   5   4   3   2   1   0
        -   -   NAK RPL  -   -   -  VER
```

| Bit | Mask | Name | Description |
|-----|------|------|-------------|
| 0 | `0x01` | VERSION | Protocol version indicator. Set to `1` in all observed packets. |
| 4 | `0x10` | REPLY | Set by the **device** when responding to a host command. |
| 5 | `0x20` | NACK | Set by the **device** to indicate a negative acknowledgement / error. |
| 1–3, 6–7 | — | Reserved | Must be `0`. |

**Encoding rule (from `MessageFactory.java`):**

```java
header = (flags | 1);
// Outgoing host command:  flags = 0x00 → header = 0x01
// Device reply (ACK):     flags = 0x10 → header = 0x11
// Device reply (NACK):    flags = 0x30 → header = 0x31  (REPLY | NACK | VERSION)
```

---

## 4. CRC16 Algorithm

### 4.1 Specification

- **Input**: bytes from offset 6 to end of packet (CMD + KEY + FLAG + DATA)
- **Output**: `uint16`, placed big-endian at offsets 4–5
- **Algorithm**: table-driven CRC16, 256-entry lookup table
- **Source**: `MessageFactory.java` (static initialiser for `CRC16_TABLE`)

### 4.2 Update Formula

```
crc = 0x0000  (initial value)

for each byte b in input[6..end]:
    index = (crc XOR b) AND 0xFF
    crc   = CRC16_TABLE[index] XOR (crc >>> 8)

// crc is the final 16-bit checksum
```

`>>>` is Java's unsigned right-shift (zero-fill, not sign-extend).

### 4.3 CRC16 Lookup Table

The 256-entry table reproduced from `MessageFactory.java`:

```
Index:  0x00–0x0F
  0000, C0C1, C181, 0140, C301, 03C0, 0280, C241,
  C601, 06C0, 0780, C741, 0500, C5C1, C481, 0440,

Index:  0x10–0x1F
  CC01, 0CC0, 0D80, CD41, 0F00, CFC1, CE81, 0E40,
  0A00, CAC1, CB81, 0B40, C901, 09C0, 0880, C841,

Index:  0x20–0x2F
  D801, 18C0, 1980, D941, 1B00, DBC1, DA81, 1A40,
  1E00, DEC1, DF81, 1F40, DD01, 1DC0, 1C80, DC41,

Index:  0x30–0x3F
  1400, D4C1, D581, 1540, D701, 17C0, 1680, D641,
  D201, 12C0, 1380, D341, 1100, D1C1, D081, 1040,

Index:  0x40–0x4F
  F001, 30C0, 3180, F141, 3300, F3C1, F281, 3240,
  3600, F6C1, F781, 3740, F501, 35C0, 3480, F441,

Index:  0x50–0x5F
  3C00, FCC1, FD81, 3D40, FF01, 3FC0, 3E80, FE41,
  FA01, 3AC0, 3B80, FB41, 3900, F9C1, F881, 3840,

Index:  0x60–0x6F
  2800, E8C1, E981, 2940, EB01, 2BC0, 2A80, EA41,
  EE01, 2EC0, 2F80, EF41, 2D00, EDC1, EC81, 2C40,

Index:  0x70–0x7F
  E401, 24C0, 2580, E541, 2700, E7C1, E681, 2640,
  2200, E2C1, E381, 2340, E101, 21C0, 2080, E041,

Index:  0x80–0x8F
  A001, 60C0, 6180, A141, 6300, A3C1, A281, 6240,
  6600, A6C1, A781, 6740, A501, 65C0, 6480, A441,

Index:  0x90–0x9F
  6C00, ACC1, AD81, 6D40, AF01, 6FC0, 6E80, AE41,
  AA01, 6AC0, 6B80, AB41, 6900, A9C1, A881, 6840,

Index:  0xA0–0xAF
  7800, B8C1, B981, 7940, BB01, 7BC0, 7A80, BA41,
  BE01, 7EC0, 7F80, BF41, 7D00, BDC1, BC81, 7C40,

Index:  0xB0–0xBF
  B401, 74C0, 7580, B541, 7700, B7C1, B681, 7640,
  7200, B2C1, B381, 7340, B101, 71C0, 7080, B041,

Index:  0xC0–0xCF
  5000, 90C1, 9181, 5140, 9301, 53C0, 5280, 9241,
  9601, 56C0, 5780, 9741, 5500, 95C1, 9481, 5440,

Index:  0xD0–0xDF
  9C01, 5CC0, 5D80, 9D41, 5F00, 9FC1, 9E81, 5E40,
  5A00, 9AC1, 9B81, 5B40, 9901, 59C0, 5880, 9841,

Index:  0xE0–0xEF
  8801, 48C0, 4980, 8941, 4B00, 8BC1, 8A81, 4A40,
  4E00, 8EC1, 8F81, 4F40, 8D01, 4DC0, 4C80, 8C41,

Index:  0xF0–0xFF
  4400, 84C1, 8581, 4540, 8701, 47C0, 4680, 8641,
  8201, 42C0, 4380, 8341, 4100, 81C1, 8081, 4040,
```

### 4.4 Reference Implementation (Python)

```python
CRC16_TABLE = [
    0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
    0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
    0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
    0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
    0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
    0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
    0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
    0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
    0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
    0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
    0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
    0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
    0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
    0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
    0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
    0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
    0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
    0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
    0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
    0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
    0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
    0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
    0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
    0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
    0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
    0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
    0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
    0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
    0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
    0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
    0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
    0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
]

def crc16(data: bytes) -> int:
    """Compute CRC16 over data (bytes at packet offset 6 onward)."""
    crc = 0
    for b in data:
        crc = CRC16_TABLE[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return crc & 0xFFFF
```

---

## 5. BLE Parser — Fragment Reassembly

**Source:** `BleParser.java`

### 5.1 Overview

BLE characteristic notifications deliver data in MTU-sized chunks. The `BleParser` reassembles these fragments into complete application messages before dispatching them to command handlers.

### 5.2 Reassembly State Machine

```
State: WAITING_FOR_MAGIC
  On receive byte[0]:
    if byte[0] == 0xAB → allocate buffer, transition to ACCUMULATING
    else               → discard fragment, remain in WAITING_FOR_MAGIC

State: ACCUMULATING
  Target buffer size = uint16_BE(fragment[2:4]) + 6
    (LENGTH field value + 6-byte fixed overhead)
  Append incoming bytes to buffer
  if buffer_filled:
    validate CRC16
    dispatch to command handler
    transition to WAITING_FOR_MAGIC
```

### 5.3 Buffer Size Formula

```
buffer_size = LENGTH_field + 6

Where:
  LENGTH_field = bytes [2:4] interpreted as big-endian uint16
  6            = MAGIC(1) + HEADER(1) + LENGTH(2) + CRC16(2)

Example: LENGTH = 0x0003 (minimum, no payload)
  buffer_size = 3 + 6 = 9 bytes
```

### 5.4 Fragment Flow Diagram

```
Fragment 1 (MTU bytes):   [AB][01][00][1F][CRC_H][CRC_L][02][01][00][data...]
                           ^    ^   ^---^                 ^^^^^^^^^^^^^^^^^
                         MAGIC  HDR  LEN=31               CMD  KEY  FLAG  data...

Fragment 2 (remaining):   [...data continuation...]

BleParser buffer = 31 + 6 = 37 bytes
After accumulating 37 bytes → CRC check → dispatch
```

---

## 6. BLE Messenger — Write Path

**Source:** `BleMessenger.java`

### 6.1 Architecture

```
Application layer
      |
      v (enqueue)
+---------------------+
| LinkedBlockingQueue |  <-- Thread-safe message queue
+---------------------+
      |
      v (consumer thread)
+---------------------+
|  Semaphore (1)      |  <-- Ensures one write completes before next starts
+---------------------+
      |
      v (fragment loop)
+---------------------+
| Chunk into mPacketSize-byte fragments
| writeCharacteristic() on TX UUID
+---------------------+
      |
      v
   GATT transport
```

### 6.2 Key Parameters

| Parameter | Value | Source |
|-----------|-------|--------|
| MTK OTA packet size | 180 bytes | Hardcoded constant |
| Default packet size | `mtu - 3` | Calculated from negotiated MTU |
| Queue type | `LinkedBlockingQueue` | Unbounded, thread-safe |
| Concurrency | `Semaphore(1)` | One outstanding write at a time |

### 6.3 Write Procedure

```
procedure sendMessage(packet: byte[]):
  1. enqueue(packet) into LinkedBlockingQueue
  2. Consumer thread dequeues packet
  3. semaphore.acquire()
  4. for offset = 0; offset < packet.length; offset += mPacketSize:
       fragment = packet[offset : min(offset + mPacketSize, packet.length)]
       gatt.writeCharacteristic(TX_UUID, fragment)
       await onCharacteristicWrite callback
  5. semaphore.release()
```

---

## 7. Connection Lifecycle

**Source:** `BleConnector.java`

> **CORRECTION (verified against a live capture):** Steps 7–9 below do not
> match the real app. After enabling notifications and negotiating MTU, the app
> sends one fixed **SESSION CREATE** frame (`cmd=0x03 key=0x02 flag=0x20`,
> payload `FF FF FF FF`, constant CRC `B1 B2`) — no IDENTITY nonce, no
> bind/login. On the reply it pushes `TIME_ZONE`, then `TIME` (a **6-byte
> calendar**, not a Unix timestamp), then `HOUR_SYSTEM`, then reads settings.

### 7.1 Connection Steps

```
Step 1: SCAN
  Filter by device name (exact match) or BLE address
  Use BluetoothLeScanner with ScanFilter

Step 2: CONNECT
  bluetoothDevice.connectGatt(context, false, gattCallback)
  autoConnect = false (direct connect)

Step 3: DISCOVER SERVICES
  onConnectionStateChange(CONNECTED) →
    gatt.discoverServices()

Step 4: ENABLE NOTIFICATIONS
  onServicesDiscovered() →
    characteristic = gatt.getService(NUS_UUID).getCharacteristic(RX_UUID)
    gatt.setCharacteristicNotification(characteristic, true)
    descriptor = characteristic.getDescriptor(CCCD_UUID_0x2902)
    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)  // 0x0001
    gatt.writeDescriptor(descriptor)

Step 5: REQUEST MTU
  onDescriptorWrite() →
    gatt.requestMtu(512)
    // Effective payload MTU = negotiated_mtu - 3

Step 6: REQUEST CONNECTION PRIORITY
  onMtuChanged(mtu) →
    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

Step 7: IDENTITY BINDING
  Send BleKey.IDENTITY (0x0215) with BleKeyFlag.CREATE (0x20)
  Payload: random int32 (4 bytes, device binding nonce)

Step 8: TIME SYNC
  Send BleKey.TIME (0x0201) with BleKeyFlag.UPDATE (0x00)
  Payload: current Unix timestamp

Step 9: FIRMWARE VERSION QUERY
  Send BleKey.FIRMWARE_VERSION (0x0204) with BleKeyFlag.READ (0x10)
  Payload: empty

Step 10: READY
  Exchange complete. Device is ready for application commands.
```

### 7.2 Connection State Diagram

```
[IDLE]
  |
  | startScan()
  v
[SCANNING]
  |
  | device found
  v
[CONNECTING]
  |
  | onConnectionStateChange(CONNECTED)
  v
[DISCOVERING SERVICES]
  |
  | onServicesDiscovered()
  v
[ENABLING NOTIFICATIONS]
  |
  | onDescriptorWrite()
  v
[NEGOTIATING MTU]
  |
  | onMtuChanged()
  v
[HANDSHAKING]  ← IDENTITY + TIME + FIRMWARE_VERSION
  |
  | replies received
  v
[CONNECTED / READY]
  |
  | bluetoothDevice.disconnect() or link loss
  v
[DISCONNECTED]
  |
  | (optional reconnect loop)
  v
[CONNECTING] ...
```

### 7.3 CCCD UUID

The Client Characteristic Configuration Descriptor (CCCD) required to enable notifications:

```
UUID: 00002902-0000-1000-8000-00805f9b34fb
Value to write: 0x01 0x00  (ENABLE_NOTIFICATION_VALUE)
```

---

## 8. DFU Address Calculation

**Source:** `BleCache.java`

For JieLi, Nordic, and Goodix chipset platforms, the DFU (Device Firmware Update) BLE address is derived by adding 1 to the device's primary BLE address.

### 8.1 Algorithm

```
Input:  primary BLE address as string, e.g. "AA:BB:CC:DD:EE:FF"

Step 1: Remove colons → "AABBCCDDEEFF"
Step 2: Parse as 48-bit hex integer
Step 3: Add 1
Step 4: Reformat as 6 pairs of uppercase hex, colon-separated

Output: "AA:BB:CC:DD:EF:00"
```

### 8.2 Python Reference

```python
def dfu_address(ble_addr: str) -> str:
    """Compute DFU BLE address for JieLi/Nordic/Goodix devices."""
    raw = ble_addr.replace(":", "")
    value = int(raw, 16) + 1
    hex_str = f"{value:012X}"
    return ":".join(hex_str[i:i+2] for i in range(0, 12, 2))

# Example:
# dfu_address("AA:BB:CC:DD:EE:FF") → "AA:BB:CC:DD:EF:00"
# dfu_address("AA:BB:CC:DD:EE:01") → "AA:BB:CC:DD:EE:02"
```

### 8.3 Platform Applicability

| Chipset Platform | DFU Address = Primary + 1 |
|-----------------|--------------------------|
| JieLi | Yes |
| Nordic | Yes |
| Goodix | Yes |
| MTK | No (uses dedicated MTK OTA GATT service on same address) |

---

## 9. MTU and Packet Sizing

### 9.1 MTU Negotiation

The app requests MTU = **512** bytes during connection setup (Step 5 above). The actual negotiated MTU depends on the device firmware and OS limits.

```
Requested MTU:       512 bytes
Effective payload:   negotiated_mtu - 3
  (3-byte ATT header overhead: opcode[1] + handle[2])

Conservative fallback: 20 bytes (default BLE ATT MTU - 3)
```

### 9.2 Fragmentation

If a message frame exceeds `mPacketSize`, it is split into sequential fragments:

```
Total frame: 150 bytes
mPacketSize: 50 bytes

Fragment 1: frame[0:50]    (50 bytes)
Fragment 2: frame[50:100]  (50 bytes)
Fragment 3: frame[100:150] (50 bytes)

Each fragment is written as a separate characteristic write.
The BleParser on the watch reassembles using the LENGTH field.
```

### 9.3 MTK OTA Sizing

MTK OTA writes always use a fixed chunk size of **180 bytes**, independent of MTU negotiation.

---

## 10. Error Conditions

| Condition | Detection | Recovery |
|-----------|-----------|----------|
| CRC mismatch | Computed CRC != bytes[4:6] | Discard frame, log error, do not dispatch |
| Wrong magic byte | byte[0] != 0xAB | Discard fragment, reset parser state |
| NACK from device | HEADER bit5 (`0x20`) set | Re-send command or report error to application |
| MTU negotiation failure | `onMtuChanged(mtu < requested)` | Continue with smaller `mPacketSize` |
| GATT disconnect | `onConnectionStateChange(DISCONNECTED)` | Trigger reconnect flow from IDLE state |
| Write queue overflow | `LinkedBlockingQueue` at capacity | Drop oldest packet (implementation-defined) |

---

## Appendix: Byte-level Worked Example

### Sending `SET TIME` with Unix timestamp `1740398400` (2025-02-24 08:00:00 UTC)

**Step 1 — Encode timestamp as big-endian int32:**
```
1740398400 = 0x67BB6E80

Bytes: 67 BB 6E 80
```

**Step 2 — Build CMD/KEY/FLAG/DATA region (CRC input):**
```
CMD  = 0x02  (BleCommand.SET)
KEY  = 0x01  (BleKey.TIME low byte)
FLAG = 0x00  (BleKeyFlag.UPDATE)
DATA = 67 BB 6E 80

CRC input: [02 01 00 67 BB 6E 80]
```

**Step 3 — Compute CRC16:**
```
Initial crc = 0x0000

b=0x02: idx = (0x0000 ^ 0x02) & 0xFF = 0x02
        crc = TABLE[0x02] ^ (0x0000 >> 8)
            = 0xC181 ^ 0x0000 = 0xC181

b=0x01: idx = (0xC181 ^ 0x01) & 0xFF = 0x80
        crc = TABLE[0x80] ^ (0xC181 >> 8)
            = 0xA001 ^ 0x00C1 = 0xA0C0

... (continue for remaining bytes 00, 67, BB, 6E, 80)

Final CRC16: (computed value, e.g.) 0x1A3F  [implementor must compute]
```

**Step 4 — Compute LENGTH:**
```
DATA length = 4
LENGTH = 4 + 3 = 7 = 0x0007
```

**Step 5 — Assemble frame:**
```
Offset:  00  01  02  03  04  05  06  07  08  09  0A  0B  0C
         AB  01  00  07  1A  3F  02  01  00  67  BB  6E  80
         ^   ^   ^---^   ^---^   ^   ^   ^   ^-----------^
       MAGIC HDR  LEN=7  CRC16  CMD KEY FLAG  timestamp data
```

**Total frame length: 13 bytes**
