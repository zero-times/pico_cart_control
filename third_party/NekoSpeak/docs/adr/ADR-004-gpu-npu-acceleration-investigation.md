# ADR-004: GPU/NPU Acceleration Investigation - ExecuTorch, LiteRT, and QNN

**Status:** Reverted  
**Date:** 2026-01-30 to 2026-01-31  
**Decision Makers:** @siva-sub  

## Context

Following ADR-001 (NNAPI) and ADR-002 (QNN), a comprehensive investigation was conducted to enable GPU/NPU hardware acceleration for Pocket-TTS inference on Android. The goal was to reduce the ~5-8 second synthesis time using hardware acceleration.

## Investigation Summary

### Approaches Explored

| Approach | Platform | Status | Outcome |
|----------|----------|--------|---------|
| **ExecuTorch Vulkan** | GPU | ✅ Works | Too slow (47s vs 5s CPU) |
| **ExecuTorch QNN** | NPU | ❌ Blocked | Requires source build |
| **LiteRT (TFLite)** | GPU/NPU | ❌ Abandoned | Would need model migration |
| **ONNX + QNN (Fixed Shapes)** | NPU | ❌ Failed | ScatterND op not supported |

---

## Phase 1: ExecuTorch Vulkan GPU

### What Was Done

1. **Upgraded ExecuTorch** from 0.5.0 to 1.1.0 via Maven
2. **Added Vulkan AAR**: `executorch-android-vulkan:1.1.0`
3. **Created Zero-Dynamic Export**: 
   - Implemented `FixedPositionFlowLM` with static KV-cache positions
   - Created `ManualAttention` class (avoids unsupported SDPA)
   - Bundled 4 step positions: forward_0, forward_32, forward_64, forward_128
4. **Created Android Infrastructure**:
   - `ExecutorchWrapper.kt` - JNI interface for PTE loading
   - `PocketTtsVulkanEngine.kt` - Full TTS engine implementation
   - `DeviceCapabilities.kt` - GPU/Vulkan detection
   - `EngineLifecycleManager.kt` - Memory management

### Results

```
Device: Snapdragon 7+ Gen 3 (SM7675)
Vulkan Support: ✓ (tier=HIGH_END)
GPU Delegation: ✓ (vulkan=true)

Performance:
- ONNX CPU: ~5-8 seconds for 1.2s audio
- ExecuTorch Vulkan: ~47 seconds for 1.2s audio (10x SLOWER)
```

### Why Vulkan Was Slow

The Pocket-TTS model architecture (autoregressive transformer) is not well-suited for mobile GPU:
- Small batch sizes (batch=1)
- Memory-bound attention operations
- Frequent CPU↔GPU data transfers for KV-cache
- Mobile GPU optimized for parallel compute, not sequential generation

---

## Phase 2: ExecuTorch QNN (NPU)

### What Was Done

1. **Verified QNN SDK installed**: v2.42.0 at `/home/siva/SDKs/qairt/qairt/2.42.0.251225`
2. **Attempted to import QnnPartitioner**

### Blocker

```python
from executorch.backends.qualcomm.partition.qnn_partitioner import QnnPartitioner
# ModuleNotFoundError: No module named 'executorch.backends.qualcomm._py_qnn'
```

The ExecuTorch pip package does **not** include the native Python bindings (`PyQnnManagerAdaptor`) required for QNN partitioning. This requires building ExecuTorch from source.

### Device Compatibility

SM7675 (Snapdragon 7+ Gen 3) is **NOT** in the officially supported QNN device list:
- Supported: SM8450, SM8475, SM8550, SM8650, SM8750 (8-series only)
- 7-series chips have Hexagon DSP but may not be validated for QNN HTP

---

## Phase 3: LiteRT (TensorFlow Lite)

### What Was Explored

1. **Simpler API**: `CompiledModel.create()` with `Accelerator.GPU` / `Accelerator.NPU`
2. **Maven Package**: `com.google.ai.edge.litert:litert:2.1.0`
3. **PyTorch Conversion**: `litert_torch.convert()` → `.tflite`

### Why Abandoned

- Would require migrating from ONNX to TFLite format
- Same dynamic shape issues apply
- Device compatibility uncertain for SM7675
- Significant effort for unproven benefit

---

## Phase 4: Fixed-Shape ONNX for QNN

### What Was Done

Created `export_onnx_fixed_shapes.py` to export ONNX models with FIXED dimensions:

```python
# Fixed positions: 0, 32, 64, 128, 256
# No dynamic axes - all shapes static
torch.onnx.export(
    step_model,
    (sequence, k_cache, v_cache),
    "flow_lm_step_N.onnx",
    dynamic_axes=None,  # KEY: No dynamic shapes
)
```

### Export Results

```
flow_lm_step_0.onnx      + .data = 101 MB
flow_lm_step_32.onnx     + .data = 101 MB
flow_lm_step_64.onnx     + .data = 101 MB
flow_lm_step_128.onnx    + .data = 101 MB
flow_lm_step_256.onnx    + .data = 101 MB
Total: ~505 MB
```

### QNN Conversion Failure

```bash
$ qnn-onnx-converter --input_network flow_lm_step_0.onnx --output_path flow_lm_step_0

ValueError: calculateShape: Unable to calculate ReshapeOp output shape 
for op Reshape_post_node_select. 524288 != -411566080
```

**Root Cause:** The KV-cache update pattern:
```python
new_k_cache = k_cache.clone()
new_k_cache[:, pos:pos+1] = k  # Slice assignment
```

PyTorch exports this as `ScatterND` op, which QNN's ONNX converter cannot handle.

---

## Files Created (To Be Removed)

### Android Infrastructure
```
app/src/main/java/com/nekospeak/tts/engine/executorch/ExecutorchWrapper.kt
app/src/main/java/com/nekospeak/tts/engine/pocket/PocketTtsVulkanEngine.kt
app/src/main/java/com/nekospeak/tts/engine/ExecuTorchEngine.kt
app/src/main/java/com/nekospeak/tts/engine/EngineLifecycleManager.kt
app/src/main/java/com/nekospeak/tts/utils/DeviceCapabilities.kt
app/src/main/assets/pocket/pte/
```

### Export Scripts
```
models/pocket-tts-vulkan/
models/pocket-tts-qnn/
```

### Modified Files (To Be Reverted)
```
app/build.gradle.kts (ExecuTorch dependencies)
app/src/main/java/com/nekospeak/tts/engine/EngineFactory.kt
app/src/main/java/com/nekospeak/tts/data/ModelRepository.kt
app/src/main/java/com/nekospeak/tts/ui/screens/SettingsScreen.kt
```

---

## Decision

**Reverted all GPU/NPU acceleration work.**

### Rationale

1. **Performance**: Vulkan GPU is 10x slower than CPU for this model architecture
2. **Compatibility**: SM7675 not in official NPU supported device lists
3. **Complexity**: QNN requires source builds and model restructuring
4. **Effort vs Benefit**: 20+ hours investment for uncertain improvement
5. **Current State**: ONNX CPU with XNNPACK (5-8s) is acceptable for TTS

### Lessons Learned

1. **Autoregressive models don't benefit from mobile GPU**: Small batch sequential generation doesn't parallelize well
2. **Dynamic shapes are fundamental blockers**: NNAPI and QNN require static compilation
3. **ScatterND is problematic**: In-place tensor updates don't convert well to NPU ops
4. **ExecuTorch Vulkan targets batch inference**: Not suitable for autoregressive text generation

---

## Recommendations for Future

If GPU/NPU acceleration becomes critical:

1. **Wait for improvements**: ExecuTorch Vulkan and QNN support is rapidly evolving
2. **Consider non-autoregressive TTS**: Models like FastSpeech2/VITS have better parallelization
3. **Use Piper TTS**: Designed for fast CPU inference, may be faster overall
4. **Quantize aggressively**: INT4/INT8 on CPU may yield better results than GPU

## References

- [ExecuTorch Vulkan Backend](https://docs.pytorch.org/executorch/main/backends/vulkan/vulkan-overview.html)
- [ExecuTorch Qualcomm Backend](https://docs.pytorch.org/executorch/main/backends-qualcomm.html)
- [LiteRT CompiledModel API](https://ai.google.dev/edge/litert/next/android_kotlin)
- [QNN ONNX Converter](https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/overview.html)
