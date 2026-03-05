# Stop-and-Wait Protocol Test Results

## Test Files Created
- **small_send.txt**: 86 bytes (1 DATA packet)
- **large_send.txt**: 5,093 bytes (~41 DATA packets)
- **empty_send.txt**: 0 bytes (edge case)

## Test Configuration
- Sender ACK Port: 4321
- Receiver Data Port: 1234
- Default Timeout: 1000 ms

## How to Run Tests

### Terminal 1 (Receiver):
```powershell
cd Receiver
java Receiver 127.0.0.1 4321 1234 <output_file> <RN>
```

### Terminal 2 (Sender) - Wait 2-3 seconds after starting receiver:
```powershell
cd Sender
java Sender 127.0.0.1 1234 4321 <input_file> 1000
```

## Test Matrix

| File Size | RN | Expected Behavior |
|-----------|----|--------------------|
| Small (86B) | 0 | ✓ No retransmissions |
| Small (86B) | 5 | Every 5th ACK dropped, some retransmissions |
| Large (5KB) | 0 | ✓ All ACKs received |
| Large (5KB) | 5 | Multiple ACK drops, retransmissions |
| Large (5KB) | 100 | Rare ACK drops |
| Empty (0B) | 0 | EOT sent with seq=1 immediately |

## Test Results

### Test 1: Small File, RN=0 ✓ PASSED
**Receiver Command:**
```
java Receiver 127.0.0.1 4321 1234 output_small_rn0.txt 0
```

**Sender Command:**
```
java Sender 127.0.0.1 1234 4321 ..\small_send.txt 1000
```

**Result:**
- Total Transmission Time: 0.02 seconds
- No timeouts or retransmissions
- File integrity: VERIFIED (no differences)
- Packets sent: SOT + 1 DATA + EOT = 3 packets

---

### Test 2: Small File, RN=5
**Receiver Command:**
```
java Receiver 127.0.0.1 4321 1234 output_small_rn5.txt 5
```

**Sender Command:**
```
java Sender 127.0.0.1 1234 4321 ..\small_send.txt 1000
```

**Notes:**
- ACK count: SOT(1), DATA(2), EOT(3)
- 5th ACK would be dropped but only 3 ACKs total
- Expected: No drops for this small file

---

### Test 3: Large File, RN=0
**Receiver Command:**
```
java Receiver 127.0.0.1 4321 1234 output_large_rn0.txt 0
```

**Sender Command:**
```
java Sender 127.0.0.1 1234 4321 ..\large_send.txt 1000
```

**Expected:**
- Packets: SOT + 41 DATA + EOT = 43 packets
- No retransmissions

---

### Test 4: Large File, RN=5
**Receiver Command:**
```
java Receiver 127.0.0.1 4321 1234 output_large_rn5.txt 5
```

**Sender Command:**
```
java Sender 127.0.0.1 1234 4321 ..\large_send.txt 1000
```

**Expected:**
- ACKs dropped at positions: 5, 10, 15, 20, 25, 30, 35, 40
- Approximately 8-9 retransmissions due to ACK loss
- File integrity should still be maintained

---

### Test 5: Empty File, RN=0
**Receiver Command:**
```
java Receiver 127.0.0.1 4321 1234 output_empty_rn0.txt 0
```

**Sender Command:**
```
java Sender 127.0.0.1 1234 4321 ..\empty_send.txt 1000
```

**Expected:**
- SOT (seq=0) → ACK
- EOT (seq=1) → ACK
- No DATA packets
- Output file should be 0 bytes

---

## File Integrity Verification

After each test, verify with:
```powershell
fc.exe /b <original_file> Receiver\<output_file>
```

Expected result: "FC: no differences encountered"

---

## Performance Summary Template

| Test | File Size | RN | Packets Sent | Retransmissions | Time (s) | Status |
|------|-----------|----|--------------|--------------------|----------|--------|
| 1 | Small (86B) | 0 | 3 | 0 | 0.02 | ✓ PASS |
| 2 | Small (86B) | 5 | 3 | 0 | TBD | - |
| 3 | Large (5KB) | 0 | 43 | 0 | TBD | - |
| 4 | Large (5KB) | 5 | 43 | ~8-9 | TBD | - |
| 5 | Large (5KB) | 100 | 43 | 0-1 | TBD | - |
| 6 | Empty (0B) | 0 | 2 | 0 | TBD | - |
