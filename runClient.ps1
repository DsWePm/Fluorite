$ErrorActionPreference = "Stop"

$candelaRoot = $PSScriptRoot

Push-Location $candelaRoot
try {
	.\gradlew.bat --stop
	$env:JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC'
	# Task path is qualified since the split into fabric/ and neoforge/ modules; use :neoforge:runClient
	# for the other loader.
	.\gradlew.bat :fabric:runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
	Pop-Location
}
