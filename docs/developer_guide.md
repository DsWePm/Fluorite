# Developer Guide

## Shader toolchain requirements

The build shells out to `glslangValidator`, `spirv-val` and `slangc`. Each is resolved in this order:

1. `-P<name>Path=<file-or-dir>`, or the `<NAME>` environment variable
2. `$VULKAN_SDK/Bin/<name>`
3. `PATH`

**`slangc` must be at least Slang 2026.14.** The world shaders use the four-parameter
`Ptr<T, Access, AddressSpace, Layout>` form, and that layout argument is load-bearing — without it
`WorldPush.sunDir` lands at byte offset 200 instead of the Java writer's 208 and the shader silently
reads garbage (see the comment at the top of `shaders/world/world_common.slang`). An older `slangc`
does not merely warn about this; it cannot parse `world_common.slang` at all:

```
world_common.slang(10): error 39999: too many arguments to call (got 4, expected 3)
```

This also breaks `generateShaderRecords`, which runs `slangc --reflection-json` over the same module,
so nothing compiles — not even the Java sources, since the `WorldPushData` / `MaterialHeaderData`
records are generated from that reflection.

The Vulkan SDK's bundled `slangc` lags the standalone Slang releases. SDK 1.4.341.1 ships Slang 2026.1,
which is too old; CI pins SDK 1.4.350.0. If your SDK's copy is behind, download a standalone build from
<https://github.com/shader-slang/slang/releases> and point the build at it, e.g. in
`~/.gradle/gradle.properties`:

```properties
slangcPath=C:/tools/slang-2026.14/bin/slangc.exe
```

Note that `compileShaders` passes `-warnings-as-errors all`, so a newer `slangc` can also fail the
build on diagnostics an older one never emitted.

## Windows

1. Install the Vulkan SDK from <https://vulkan.lunarg.com/sdk/home>.
   The installer sets `VULKAN_SDK` automatically. See the toolchain note above if its bundled
   `slangc` turns out to be older than Slang 2026.14.
2. Download the DLSS SDK from <https://github.com/NVIDIA/DLSS/releases>.
   Extract it, then set `DLSS_SDK` to the folder you extracted.

   To set it permanently for your Windows user account, run PowerShell with:

   ```powershell
   [Environment]::SetEnvironmentVariable("DLSS_SDK", "C:\path\to\dlss-sdk", "User")
   ```

   Restart your terminal after setting it. To set it only for the current
   PowerShell session, use:

   ```powershell
   $env:DLSS_SDK = "C:\path\to\dlss-sdk"
   ```

3. Configure and build the native shim:

```powershell
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release --config Release
```

4. Run the client:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC"
.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

## Linux

Set `DLSS_SDK` and `VULKAN_SDK` before configuring CMake:

```bash
export DLSS_SDK=/path/to/dlss-sdk
export VULKAN_SDK=/path/to/vulkan-sdk
```

`DLSS_SDK` must contain the NGX headers and static library. `VULKAN_SDK` must
contain Vulkan headers.

Then configure and build the native shim:

```bash
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

On NixOS, enter the development shell from `flake.nix` instead of setting up
the toolchain by hand:

```bash
nix develop
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

## Native Bundling

Gradle bundles NGX natives for the current host platform by default:

```bash
./gradlew build
```

Release builds that already have both platform shims available can request a
cross-platform native bundle:

```bash
./gradlew build -PngxPlatforms=windows-x64,linux-x64
```

Run the Vulkan RT/DLSS-RR client with:

```bash
JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC' nvidia-offload ./gradlew runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```
