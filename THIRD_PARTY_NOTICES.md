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
