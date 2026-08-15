# Third-Party Notices

Fluorite's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Fluorite's license.

## NVIDIA DLSS / NGX SDK

Fluorite can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Fluorite does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `fluorite/natives/windows-x64/nvngx_dlssd.dll`
- `fluorite/natives/windows-x64/nvngx_dlssg.dll`
- `fluorite/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `fluorite/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Fluorite's `ngxshim` native library is project-owned glue code and follows
Fluorite's project license unless otherwise noted.

## ACES 2 Output Transform and OpenColorIO

Fluorite's optional ACES 2 display mode contains generated shader forms of the
Academy Color Encoding System 2.0 Output Transform. The implementation is fixed
to the official `v2.0.0+2025.04.04` release:

- ACES source: <https://github.com/aces-aswf/aces/tree/v2.0.0%2B2025.04.04>
- `aces-core` commit: `2d7af39344725aaa8ac3bf1746693c9a1d6c4792`
- `aces-output` commit: `aab74723f76728c37345ed01e51ebb24fb1f2f1f`
- License: [Apache License 2.0](https://github.com/aces-aswf/aces/blob/v2.0.0%2B2025.04.04/LICENSE)

The checked-in GLSL was extracted from the corresponding built-in transforms by
OpenColorIO 2.5.2 and retains the analytic ACES 2 transform together with its
official 363-sample reach and gamut-cusp parameter tables. It is not an ACES
fitted curve. No opaque or third-party LUT asset is distributed: Fluorite's fast
mode bakes five project-owned 65-cube approximations from those same analytic
modules into transient GPU images at renderer startup. OpenColorIO is copyright
the OpenColorIO contributors and is available under the
[BSD 3-Clause license](https://github.com/AcademySoftwareFoundation/OpenColorIO/blob/v2.5.2/LICENSE).
OpenColorIO is a maintainer-only regeneration/reference dependency and is not
loaded or distributed by the game runtime.

Run `python tools/verify_aces2_output.py` with PyOpenColorIO 2.5.2 to regenerate
the five fixed modules in memory and compare them, plus representative SDR and
HDR pixels, against the checked-in implementation. Run
`python tools/verify_aces2_lut.py` to reproduce the startup bake, RGBA16F
quantisation, log shaper, and trilinear sampling on the CPU and report its
approximation error against the same fixed transform.

## HDR Multi Nebulae 1

The End environment includes a modified form of **HDR Multi Nebulae 1**, created
by **TonyS / Space Spheremaps**:

- Source: <https://www.spacespheremaps.com/hdr-spheremaps/>
- Direct build source: <https://www.spacespheremaps.com/wp-content/uploads/HDR_multi_nebulae_1.hdr>
- Pinned source SHA-256: `dad11594feb1658d939db6d267f5b20a30e436fcbf61d5283421bd85fd393d90`
- Site licensing terms: <https://www.spacespheremaps.com/about/>
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)

The source site's terms state that attribution is appreciated but not required;
Fluorite provides attribution anyway. They also request that the images not be
used for AI image training or scraping. Fluorite does not use the image for
either purpose.

Fluorite's deterministic offline build decodes the 10000×5000 Radiance RGBE
source, performs a solid-angle-weighted area resize to 4096×2048, generates an
energy-preserving mip chain, and packs the result as R11G11B10 KTX2. No AI image
processing is used. This notice does not imply endorsement by the creator.

The 10K mother asset is not stored in Fluorite's Git repository. The build
downloads it into an ignored local Gradle cache only when absent and rejects any
download whose SHA-256 differs from the value above. A builder may instead
supply the same pinned bytes with `-PendHdrSource=<path>`.

## High-cloud shape sources

Fluorite's high-cloud renderer derives shape-only optical-depth textures from
two CC0 sources. Neither source's RGB colour nor baked lighting is sampled by the
game; Fluorite's current sun and sky Radiance relight both high-cloud sheets.

### Clouds with Transparency

The lower high-cloud sheet uses modified alpha shapes from **Clouds with
Transparency**, created by **WickedInsignia**:

- Source page: <https://opengameart.org/content/clouds-with-transparency>
- Direct build source: <https://opengameart.org/sites/default/files/fx_cloudalphas.zip>
- Pinned source SHA-256: `83514a391c765819bc159b7f9ab61ec9f06c0caa2b5865af20a5bd3b2a14cca4`
- License: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/)

The deterministic build reads the ten 2048x2048 alpha images, downsamples them
in transmittance space, converts alpha to optical depth, normalises their
integrated optical depth, generates Beer-preserving mipmaps, and packs a ten-layer 1024x1024
R8_UNORM KTX2 array. The original RGB channels are discarded.

The source assets are not stored in Fluorite's Git repository. Gradle downloads
them into an ignored cache only when absent and rejects bytes whose SHA-256 does
not match the values above. Builders may provide the same pinned bytes with
`-PhighCloudPatchSource=<path>`.
