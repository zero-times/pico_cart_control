# ADR-005: Fix SIGBUS Crash on 32-bit ARMv7 for INT8 ONNX Models

## Status
**Accepted** - 2026-02-02

## Context
Users reported a native crash (SIGBUS, code BUS_ADRALN) when loading INT8 quantized ONNX models on 32-bit ARMv7 (armeabi-v7a) Android devices. The crash occurred during `OrtSession.createSession()` before any inference was executed.

**Error signature:**
```
Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0xc41dcc11
```

The fault address ending in `0x...1` confirmed misaligned memory access (not 4-byte aligned).

## Investigation

### Root Cause
ONNX Runtime uses **memory mapping (mmap)** when loading models via file path. On 32-bit ARMv7:
- INT8 quantized tensors may have unaligned data offsets in the model file
- ARMv7 requires 4-byte alignment for 32-bit memory accesses
- mmap preserves the file's byte layout, exposing unaligned addresses
- Accessing misaligned memory triggers SIGBUS (BUS_ADRALN)

### Why 64-bit ARM (arm64-v8a) works
64-bit ARM handles unaligned memory access gracefully in hardware, so the same mmap approach works without issues.

### Why FP32 models worked
FP32 models have naturally aligned tensor data (4-byte floats), while INT8 quantization can introduce 1-byte aligned data that violates ARMv7 requirements.

## Decision
Implement **architecture-aware model loading**:
- On 32-bit ARM: Load model as byte array (`createSession(byte[])`) which allocates on the heap with proper alignment
- On 64-bit: Continue using efficient mmap-based file path loading (`createSession(String)`)

```kotlin
private fun is32BitArm(): Boolean {
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
    return abi == "armeabi-v7a" || abi == "armeabi"
}

private fun loadModel(dir: File, name: String, options: OrtSession.SessionOptions): OrtSession {
    val modelFile = File(dir, name)
    return if (is32BitArm()) {
        val modelBytes = modelFile.readBytes()
        ortEnv!!.createSession(modelBytes, options)
    } else {
        ortEnv!!.createSession(modelFile.absolutePath, options)
    }
}
```

## Consequences

### Positive
- Resolves SIGBUS crash on 32-bit ARMv7 devices
- No performance impact on 64-bit devices (still uses mmap)
- Simple, well-understood fix pattern

### Negative
- Increased memory usage on 32-bit devices (model bytes copied to Java heap)
- For 72MB INT8 model, this adds ~72MB heap allocation during load

### Trade-off Accepted
The memory cost is acceptable because:
1. 32-bit ARMv7 devices are increasingly rare (most devices since 2015 are 64-bit)
2. A working app with higher memory usage is better than a crashing app
3. Memory is freed after session creation completes

## Files Changed
- `PocketTtsEngine.kt` - Primary fix with `is32BitArm()` helper
- `KokoroEngine.kt` - Same pattern for consistency
- `PiperEngine.kt` - Same pattern for consistency  
- `GtcrnDenoiser.kt` - Same pattern for consistency

## References
- [ONNX Runtime GitHub - Memory alignment issues](https://github.com/microsoft/onnxruntime/issues)
- ARM Architecture Reference Manual - Memory alignment requirements
