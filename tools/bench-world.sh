#!/usr/bin/env bash
# Restore the benchmark world from its pristine master.
#
# Run this before EVERY capture. Minecraft writes the player's position back into level.dat on exit, so
# a world that has been played in is a world whose camera has moved — and the camera dominates trace
# cost far more than any change being measured. Two captures taken from a world that drifted between
# them are measuring the view, not the code.
#
# That is not hypothetical. The M4 baseline numbers were taken at "a fixed spawn point" that was never
# recorded anywhere, so they could not be reproduced later and the milestone gate built on them had to
# be restated as a same-session ratio. This script is what makes the absolute numbers mean something
# again: same world, same spawn, same look direction, every time.
#
# Usage:
#   tools/bench-world.sh [name]          restore run/saves/<name> from run/bench-master/<name>
#   tools/bench-world.sh --adopt [name]  make the CURRENT run/saves/<name> the new master
#
# `name` defaults to "bench".
#
# Then capture with:
#   ./gradlew :fabric:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>
#
# WHY THE NAME IS A PARAMETER: a capture only measures code that the scene actually runs. The original
# "bench" world has no significant body of water, so it says nothing about the water medium — the
# enclosed-scattering path is gated on being inside water and simply never executes there. A feature
# needs a world that exercises it, and pretending otherwise produces a number that looks like evidence
# of no regression. Keep one world per thing being measured; "bench-water" is the one for M9.
#
# The master lives under run/, which is not tracked: a 20 MB world does not belong in git, and it is
# per-machine anyway. Create it once with --adopt.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "${1:-}" = "--adopt" ]; then
    name="${2:-bench}"
else
    name="${1:-bench}"
fi
master="$here/run/bench-master/$name"
live="$here/run/saves/$name"

if [ "${1:-}" = "--adopt" ]; then
    if [ ! -d "$live" ]; then
        echo "no world at $live to adopt" >&2
        exit 1
    fi
    rm -rf "$master"
    mkdir -p "$(dirname "$master")"
    cp -r "$live" "$master"
    rm -f "$master/session.lock"
    echo "adopted $live as the master"
    exit 0
fi

if [ ! -d "$master" ]; then
    echo "no master at $master — create one with: tools/bench-world.sh --adopt" >&2
    exit 1
fi

rm -rf "$live"
cp -r "$master" "$live"
rm -f "$live/session.lock"
echo "restored $live from master"
