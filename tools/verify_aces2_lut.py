#!/usr/bin/env python3
"""Measure Fluorite's 65-cube ACES 2 LUT against the pinned OCIO transform.

This reproduces the startup GPU bake, RGBA16F quantisation, log2 shaper, and
hardware trilinear filtering on the CPU. It is a maintainer check rather than a
game/build dependency.

Requires PyOpenColorIO 2.5.2 and NumPy:
    python -m pip install OpenColorIO==2.5.2 numpy
    python tools/verify_aces2_lut.py
"""

from __future__ import annotations

import time

import numpy as np
import PyOpenColorIO as ocio

from verify_aces2_output import PRESETS, _group


LUT_SIZE = 65
SHAPER_MIN_EV = -16.0
SHAPER_MAX_EV = 16.0
RANDOM_SEED = 0xD138A
ORDINARY_START = 32_768
ORDINARY_END = ORDINARY_START + 16_384


def _unshape_axis() -> np.ndarray:
    axis = np.empty(LUT_SIZE, dtype=np.float32)
    axis[0] = 0.0
    axis[1:] = np.exp2(np.linspace(
        SHAPER_MIN_EV, SHAPER_MAX_EV, LUT_SIZE - 1, dtype=np.float32))
    return axis


def _processor(preset: str, hdr: bool) -> ocio.CPUProcessor:
    display = ("DISPLAY - CIE-XYZ-D65_to_REC.2100-PQ" if hdr
               else "DISPLAY - CIE-XYZ-D65_to_sRGB")
    return ocio.Config.CreateRaw().getProcessor(
        _group(preset, display)).getDefaultCPUProcessor()


def _apply(processor: ocio.CPUProcessor, values: np.ndarray) -> np.ndarray:
    # applyRGB mutates its NumPy argument. Always copy so reference evaluation
    # cannot accidentally replace the scene-linear vectors sampled from the LUT.
    result = np.array(values, dtype=np.float32, order="C", copy=True)
    processor.applyRGB(result)
    return np.clip(result, 0.0, 1.0)


def _bake(processor: ocio.CPUProcessor) -> np.ndarray:
    axis = _unshape_axis()
    r, g, b = np.meshgrid(axis, axis, axis, indexing="ij")
    inputs = np.stack((r, g, b), axis=-1).reshape(-1, 3)
    # Vulkan stores the runtime LUT as RGBA16F. Quantise RGB to float16 here so
    # this check includes storage precision rather than measuring interpolation alone.
    return _apply(processor, inputs).reshape(LUT_SIZE, LUT_SIZE, LUT_SIZE, 3) \
        .astype(np.float16).astype(np.float32)


def _shaper_index(values: np.ndarray) -> np.ndarray:
    minimum = np.exp2(SHAPER_MIN_EV)
    result = np.empty_like(values, dtype=np.float32)
    low = values <= minimum
    result[low] = np.clip(values[low] / minimum, 0.0, 1.0)
    safe = np.maximum(values[~low], minimum)
    t = ((np.clip(np.log2(safe), SHAPER_MIN_EV, SHAPER_MAX_EV)
          - SHAPER_MIN_EV) / (SHAPER_MAX_EV - SHAPER_MIN_EV))
    result[~low] = 1.0 + t * (LUT_SIZE - 2.0)
    return result


def _sample_trilinear(lut: np.ndarray, values: np.ndarray) -> np.ndarray:
    index = _shaper_index(values)
    lo = np.floor(index).astype(np.int32)
    hi = np.minimum(lo + 1, LUT_SIZE - 1)
    fraction = index - lo
    result = np.zeros_like(values, dtype=np.float32)
    for rx in (0, 1):
        ix = hi[:, 0] if rx else lo[:, 0]
        wx = fraction[:, 0] if rx else 1.0 - fraction[:, 0]
        for gy in (0, 1):
            iy = hi[:, 1] if gy else lo[:, 1]
            wy = fraction[:, 1] if gy else 1.0 - fraction[:, 1]
            for bz in (0, 1):
                iz = hi[:, 2] if bz else lo[:, 2]
                wz = fraction[:, 2] if bz else 1.0 - fraction[:, 2]
                result += lut[ix, iy, iz] * (wx * wy * wz)[:, None]
    return result


def _test_vectors() -> np.ndarray:
    rng = np.random.default_rng(RANDOM_SEED)

    # Independent log channels stress saturated colours and gamut compression.
    log_rgb = np.exp2(rng.uniform(
        SHAPER_MIN_EV, SHAPER_MAX_EV, size=(32_768, 3))).astype(np.float32)
    log_rgb[rng.random(log_rgb.shape) < 0.12] = 0.0

    # Dense ordinary scene values better represent Minecraft's common output.
    ordinary = rng.uniform(0.0, 16.0, size=(16_384, 3)).astype(np.float32)

    # Neutral ramps and primaries catch visible tone-scale and hue discontinuities.
    ramp = np.exp2(np.linspace(
        SHAPER_MIN_EV, SHAPER_MAX_EV, 4096, dtype=np.float32))
    neutrals = np.repeat(ramp[:, None], 3, axis=1)
    primaries = np.zeros((4096 * 3, 3), dtype=np.float32)
    for channel in range(3):
        primaries[channel * 4096:(channel + 1) * 4096, channel] = ramp

    anchors = np.array([
        [0.0, 0.0, 0.0], [0.18, 0.18, 0.18], [1.0, 1.0, 1.0],
        [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0],
        [65536.0, 65536.0, 65536.0],
    ], dtype=np.float32)
    return np.concatenate((log_rgb, ordinary, neutrals, primaries, anchors), axis=0)


def main() -> None:
    if ocio.__version__ != "2.5.2":
        raise SystemExit(f"PyOpenColorIO 2.5.2 required, found {ocio.__version__}")

    vectors = _test_vectors()
    worst = 0.0
    started = time.perf_counter()
    for key, preset in PRESETS:
        processor = _processor(preset, key != "sdr100")
        lut = _bake(processor)
        exact = _apply(processor, vectors)
        sampled = _sample_trilinear(lut, vectors)
        error = np.abs(sampled - exact).reshape(-1)
        ordinary_error = np.abs(
            sampled[ORDINARY_START:ORDINARY_END]
            - exact[ORDINARY_START:ORDINARY_END]).reshape(-1)
        maximum = float(np.max(error))
        worst = max(worst, maximum)
        print(f"{key:7s}: RMSE={np.sqrt(np.mean(error * error)):.6f} "
              f"P99={np.percentile(error, 99):.6f} "
              f"P99.9={np.percentile(error, 99.9):.6f} max={maximum:.6f} "
              f"ordinary-max={np.max(ordinary_error):.6f}")

    print(f"Validated {len(vectors)} vectors per preset in "
          f"{time.perf_counter() - started:.1f}s; worst encoded-channel error={worst:.6f}")


if __name__ == "__main__":
    main()
