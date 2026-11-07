// Immutable per-push-slot header. RtPathHistory writes this 48 B std430 layout after slot retirement.
struct PathHistoryHeader {
    uint64_t storeAddr;
    uint64_t stampsAddr;
    uint64_t terrainMaskAddr;
    uint64_t entityMaskAddr;
    uint terrainCount;
    uint entityCount;
    uint previousValid;
    uint confidenceCap;
};

PathHistoryHeader reuseHistoryRoot() {
    return DevicePtr<PathHistoryHeader>(worldPush.pathReservoirAddr)[0];
}

uint64_t reusePathStoreAddress() { return reuseHistoryRoot().storeAddr; }

bool reuseHistoryReady(PathHistoryHeader root) {
    return root.previousValid != 0u
            && DevicePtr<uint>(root.stampsAddr)[(worldPush.frameIndex ^ 1u) & 1u] == worldPush.frameIndex - 1u;
}

bool reuseUnchangedSurface(PathHistoryHeader root, uint objectId) {
    if (objectId == PATH_OBJECT_UNAVAILABLE || (objectId & PARTICLE_BIT) != 0u) return false;
    bool entity = (objectId & ENTITY_BIT) != 0u;
    uint index = objectId & ~(ENTITY_BIT | PARTICLE_BIT);
    uint count = entity ? root.entityCount : root.terrainCount;
    if (index >= count) return false;
    uint64_t address = entity ? root.entityMaskAddr : root.terrainMaskAddr;
    return (DevicePtr<uint>(address)[index >> 5u] & (1u << (index & 31u))) != 0u;
}

void reuseBeginPixel(uint2 pixel, uint2 dimensions) {
    if (worldPush.pathReservoirAddr == 0u) return;
    DevicePtr<PackedPathReservoir> store = DevicePtr<PackedPathReservoir>(reusePathStoreAddress());
    store[pathReservoirSlot(pixel.y * dimensions.x + pixel.x, dimensions.x * dimensions.y,
            worldPush.frameIndex & 1u)] = emptyPathReservoir();
}

void reusePublishFrame(uint2 pixel) {
    if (worldPush.pathReservoirAddr != 0u && all(pixel == uint2(0u, 0u)))
        DevicePtr<uint>(reuseHistoryRoot().stampsAddr)[worldPush.frameIndex & 1u] = worldPush.frameIndex;
}
