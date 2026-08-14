#!/usr/bin/env python3
"""Regenerate and verify Fluorite's fixed ACES 2 shader modules and reference pixels.

Requires PyOpenColorIO 2.5.2. This is a maintainer check, not a game/build dependency:
    python -m pip install OpenColorIO==2.5.2
    python tools/verify_aces2_output.py
"""

from __future__ import annotations

import re
from pathlib import Path

import PyOpenColorIO as ocio


ROOT = Path(__file__).resolve().parents[1]
M_709_TO_XYZ_D65 = [
    0.4123907992659595, 0.3575843393838780, 0.1804807884018343, 0,
    0.2126390058715104, 0.7151686787677560, 0.0721923153607337, 0,
    0.0193308187155919, 0.1191947797946259, 0.9505321522496606, 0,
    0, 0, 0, 1,
]
PRESETS = [
    ("sdr100", "SDR-100nit-REC709"),
    ("hdr500", "HDR-500nit-REC2020"),
    ("hdr1000", "HDR-1000nit-REC2020"),
    ("hdr2000", "HDR-2000nit-REC2020"),
    ("hdr4000", "HDR-4000nit-REC2020"),
]
HEADER = """// SPDX-License-Identifier: Apache-2.0
// Generated from ACES v2.0.0+2025.04.04 with OpenColorIO 2.5.2.
// Fixed analytic ACES 2 Output Transform plus official 363-sample parameter tables.
// This is not a fitted curve or 3D LUT. See THIRD_PARTY_NOTICES.md.
"""


def _float(value: float) -> str:
    text = format(float(value), ".9g")
    return text + ".0" if "e" not in text.lower() and "." not in text else text


def _group(preset: str, display: str | None = None) -> ocio.GroupTransform:
    group = ocio.GroupTransform()
    matrix = ocio.MatrixTransform()
    matrix.setMatrix(M_709_TO_XYZ_D65)
    group.appendTransform(matrix)
    to_ap0 = ocio.BuiltinTransform()
    to_ap0.setStyle("UTILITY - ACES-AP0_to_CIE-XYZ-D65_BFD")
    to_ap0.setDirection(ocio.TRANSFORM_DIR_INVERSE)
    group.appendTransform(to_ap0)
    output = ocio.BuiltinTransform()
    output.setStyle("ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - " + preset + "_2.0")
    group.appendTransform(output)
    if display:
        encoding = ocio.BuiltinTransform()
        encoding.setStyle(display)
        group.appendTransform(encoding)
    return group


def _generated_module(key: str, preset: str) -> str:
    prefix = "fluorite_aces2_" + key + "_"
    function = "fluoriteAces2" + key.capitalize()
    processor = ocio.Config.CreateRaw().getProcessor(_group(preset)).getDefaultGPUProcessor()
    descriptor = ocio.GpuShaderDesc.CreateShaderDesc()
    descriptor.setLanguage(ocio.GPU_LANGUAGE_GLSL_4_0)
    descriptor.setFunctionName(function)
    descriptor.setResourcePrefix(prefix)
    descriptor.setAllowTexture1D(True)
    processor.extractGpuShaderInfo(descriptor)

    textures = list(descriptor.getTextures())
    if len(textures) != 2:
        raise AssertionError(f"{preset}: expected reach and cusp tables, got {len(textures)} textures")
    reach = list(textures[0].getValues())
    cusp = list(textures[1].getValues())
    if len(reach) != 363 or len(cusp) != 363 * 3:
        raise AssertionError(f"{preset}: official table dimensions changed")

    reach_name = prefix + "reach_m_table_0"
    cusp_name = prefix + "gamut_cusp_table_0"
    tables = "\nconst float " + reach_name + "[363] = float[363](\n    "
    tables += ", ".join(_float(value) for value in reach) + "\n);\n"
    tables += "const vec3 " + cusp_name + "[363] = vec3[363](\n    "
    tables += ",\n    ".join(
        "vec3(" + ",".join(_float(value) for value in cusp[i:i + 3]) + ")"
        for i in range(0, len(cusp), 3)
    ) + "\n);\n"

    shader = descriptor.getShaderText()
    shader = re.sub(
        r"// Declaration of all textures\s+uniform sampler1D [^;]+;\s+uniform sampler1D [^;]+;",
        "// Official ACES 2 parameter tables" + tables,
        shader,
        count=1,
    )
    shader = re.sub(
        r"texture\(" + re.escape(reach_name)
        + r"Sampler, \((i_lo|i_hi) \+ 0\.5\) / float \(363\)\)\.r",
        reach_name + r"[int(\1)]",
        shader,
    )
    shader = shader.replace(
        f"texture({cusp_name}Sampler, (float(i_hi) - 1.0 + 0.5) / float(363)).rgb",
        f"{cusp_name}[i_hi - 1]",
    ).replace(
        f"texture({cusp_name}Sampler, (float(i_hi) + 0.5) / float(363)).rgb",
        f"{cusp_name}[i_hi]",
    )
    if "texture(" in shader or "sampler1D" in shader:
        raise AssertionError(f"{preset}: generated resource access was not converted to fixed tables")
    return HEADER + shader


def _verify_reference_pixels() -> None:
    vectors = [
        ([0.18, 0.18, 0.18], [0.34918761, 0.34918812, 0.34918788]),
        ([1.0, 0.0, 0.0], [0.76986384, 0.09199714, 0.04260050]),
    ]
    sdr = ocio.Config.CreateRaw().getProcessor(_group(
        "SDR-100nit-REC709", "DISPLAY - CIE-XYZ-D65_to_sRGB")).getDefaultCPUProcessor()
    for source, expected in vectors:
        actual = sdr.applyRGB(source.copy())
        if any(abs(a - e) > 2e-6 for a, e in zip(actual, expected)):
            raise AssertionError(f"SDR reference mismatch: {source}: {actual} != {expected}")

    hdr = ocio.Config.CreateRaw().getProcessor(_group(
        "HDR-1000nit-REC2020", "DISPLAY - CIE-XYZ-D65_to_REC.2100-PQ")).getDefaultCPUProcessor()
    vectors = [
        ([0.18, 0.18, 0.18], [0.32983702, 0.32983717, 0.32983705]),
        ([1.0, 0.0, 0.0], [0.45211667, 0.25336164, 0.15173797]),
    ]
    for source, expected in vectors:
        actual = hdr.applyRGB(source.copy())
        if any(abs(a - e) > 2e-6 for a, e in zip(actual, expected)):
            raise AssertionError(f"HDR reference mismatch: {source}: {actual} != {expected}")


def main() -> None:
    if ocio.__version__ != "2.5.2":
        raise SystemExit(f"PyOpenColorIO 2.5.2 required, found {ocio.__version__}")
    for key, preset in PRESETS:
        expected = _generated_module(key, preset)
        path = ROOT / "shaders" / "display" / f"aces2_{key}.glsl"
        actual = path.read_text(encoding="utf-8")
        if actual != expected:
            raise AssertionError(f"{path.relative_to(ROOT)} differs from fixed official generation")
    _verify_reference_pixels()
    print("ACES 2 shader modules and OCIO reference pixels verified")


if __name__ == "__main__":
    main()
