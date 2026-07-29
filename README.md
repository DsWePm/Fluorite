# Fluorite

Fluorite is an experimental ray-traced renderer for Minecraft 26.2's Vulkan backend.
It replaces the vanilla world view with hardware ray tracing and NVIDIA DLSS
features while keeping Minecraft's familiar UI and gameplay intact.

Fluorite is a fork of **Caustica** by ComfyFluffy and contributors, renamed and
developed independently since July 2026. It keeps Caustica's LGPL-3.0-or-later
licence; see [LICENSE.md](LICENSE.md).

Fluorite is early software. Expect bugs, missing visual cases, and frequent
changes while the renderer is being built.

![Fluorite ray-traced Minecraft scene](docs/gallery/2026-07-09_21.25.14.jpg)

## Links

- [Gallery](docs/gallery.md)

Fluorite has no release channel of its own yet. The Discord, Modrinth and
CurseForge pages that used to be listed here belong to upstream Caustica and are
not this fork.

## Features

- Vulkan hardware path-traced world rendering
- DLSS Ray Reconstruction support
- DLSS Frame Generation support (experimental)
- HDR output
- Dynamic entity rendering in the ray-traced scene
- LabPBR-style material support
- OMM (Opacity Micro-Map) + SER (Shader Execution Reordering) optimizations

## Requirements

- **Vulkan graphics backend enabled**
- A GPU and driver with Vulkan ray tracing support
- NVIDIA RTX GPU and supported driver for DLSS features
- HDR-capable display and OS HDR mode for HDR output
- On Linux, an HDR-capable Wayland compositor and a native Wayland session for HDR output
- Install LabPBR resource pack like [SPBR](https://modrinth.com/resourcepack/spbr) for better visuals

## Installation

1. Install Fabric Loader or NeoForge for Minecraft `26.2`.
2. On Fabric, install Fabric API as well. NeoForge needs no extra dependency.
3. Put the matching Fluorite jar in your Minecraft `mods` folder.
4. Launch the game with the Vulkan graphics backend.
5. Open Video Settings to adjust Fluorite's renderer options.

## Usage Notes

- Fluorite is client-side only.
- DLSS Ray Reconstruction and Frame Generation require supported NVIDIA
  hardware and drivers.
- On Linux if Minecraft crashes on startup with stack overflow errors, try adding `-Xss2M` to the Java args to increase the stack size.
- Use Java args to improve performance. Minecraft Launcher default:
  `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC`
- Frame Generation is experimental and needs to be enabled by modifying the configuration file.
- HDR output requires an HDR swapchain and a correctly configured HDR display.
- When HDR is enabled on Linux, Fluorite selects GLFW's native Wayland backend automatically. X11/XWayland surfaces generally do not expose the required HDR10/PQ format.
- If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
  before using Fluorite again.

## Compatibility

Fluorite takes over the world renderer, so other mods that heavily modify world
rendering, shader pipelines, post-processing, or the Vulkan backend may conflict.
UI-only mods are more likely to work.

## Status

Fluorite is not a finished renderer yet. Current work focuses on visual
correctness, world coverage, stability, and making the SDR/HDR presentation
paths behave consistently.

## License

Fluorite's project-owned source code and documentation are licensed under the
GNU Lesser General Public License v3.0 or later. See [LICENSE.md](LICENSE.md),
[COPYING](COPYING), and [COPYING.LESSER](COPYING.LESSER).

Release artifacts may bundle NVIDIA DLSS/NGX SDK components under NVIDIA's own
license terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgements

Fluorite exists because of [Caustica](https://modrinth.com/mod/caustica) by
ComfyFluffy and its contributors, who wrote the renderer this fork is built on.

Parts of the work in this fork were written with the assistance of Anthropic's
Claude. Copyright in those contributions rests with the project's authors, not
with the tool.

## TODO List

- [ ] Nether/End sky, weather, volumetric fog/clouds
- [ ] NRD + FSR for non-NVIDIA GPUs
- [ ] LOD
- [ ] ReSTIR
