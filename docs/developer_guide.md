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
slangcPath=C:/path/to/slang/bin/slangc.exe
```

**You probably do not need this.** SDK 1.4.350.0 — the version CI installs — ships Slang 2026.8, which
compiles every world shader with `-warnings-as-errors all` and produces byte-identical reflection ABI.
Installing that SDK or newer and letting the `$VULKAN_SDK/Bin` fallback find `slangc` keeps your local
toolchain identical to CI's, which is worth more than pinning a newer Slang. Reach for the override only
if your SDK is genuinely behind 1.4.350.0.

Note that `compileShaders` passes `-warnings-as-errors all`, so a newer `slangc` can also fail the
build on diagnostics an older one never emitted — which is the other reason to match CI rather than
chase the latest Slang release.

## Shader 增量构建与路径诊断

`compileShaders` 按变体缓存已通过 `spirv-val` 的产物。依赖来自 Slang/glslang 的 depfile，缓存同时核对依赖内容、完整编译参数、工具二进制及 Slang 相邻编译库、输出 SHA-256。修改复用专用模块只重编实际依赖它的两个 raygen；共享 ABI 改动会重编全部受影响项。新增或删除源文件保守地使全部变体失效。编译/校验失败不会发布半套产物。

普通构建命令不变。需要检查每个缓存项时使用 `./gradlew compileShaders -PshaderCacheAudit`；`--rerun-tasks` 或 `-Pfluorite.shaderCache=false` 强制真实重编，仍执行原验证。缓存只省去未变化的编译工作，完整测试、OFF 字节对照和实际 RT 回归仍按改动范围执行。首次修改 buildSrc 可能触发其它生成资源重跑，这与日常 shader 增量构建分开记录。

路径诊断可用一个有界命令启动、归档并恢复窗口参数（Python 3.11+）：

```powershell
python -B -X utf8 tools/path-probe.py --output F:/MC/Shader/evidence/new-path-run --world restir-r1-smoke --readbacks 20000
```

输出目录必须是新目录。脚本保存原 config/options、源码哈希与 diff、实际模块哈希、运行日志和本轮更新的 frame.csv；首个失败或回读上限触发客户端正常保存退出。它保持 renderer 配置，要求 master/identity-check 已开、debug-view 为 0，并显式编译 probes；不会用 `-x compileShaders` 跳过依赖检查。正常退出后恢复窗口参数，保留运行期间用户修改的其它选项。若仍有客户端存活，恢复会延后并写 `RESTORE_PENDING.txt`，退出后可执行 `python tools/path-probe.py --restore <输出目录>`。

`result.json` 记录会话退出、模块匹配及失败日志，不代替累计数值门、论文估计量或视觉/性能验收。临时 shader 取证应由外层 `try/finally` 同时管理源码、产物与缓存回滚；不要在游戏或编译期间改源码。脚本的窗口恢复/启动前提测试为 `python -B -m unittest discover -s tools -p test_path_probe.py`，编译缓存测试为 `./gradlew :buildSrc:test`。

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
