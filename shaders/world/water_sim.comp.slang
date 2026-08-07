// One explicit leapfrog step of the 2D linear wave equation, on a world-anchored height field (M12).
//
// WHY A HEIGHT FIELD AND NOT PARTICLES OR A FULL FLUID. What this has to produce is ripples that spread,
// reflect off blocks and interfere -- all of which the linear wave equation gives exactly, on a grid, in
// a handful of ALU per cell. A ripple is a small perturbation of a flat surface, which is precisely the
// regime the linearisation is valid in; the things it cannot do (breaking, overturning, splashes that
// leave the surface) are not representable in a height field at all, so nothing is lost by choosing the
// cheap solver over an expensive one that also cannot do them.
//
// THE SCHEME. d2h/dt2 = c^2 * laplacian(h), discretised as
//
//   h_next = 2*h_cur - h_prev + (c*dt/dx)^2 * (sum of four neighbours - 4*h_cur)
//
// which is second-order accurate in both time and space and costs one multiply-add per neighbour. Three
// buffers rotate through prev/cur/next rather than two, because leapfrog needs BOTH previous states and
// writing next over prev in place would corrupt the neighbours a later thread still has to read.
//
// THE CFL CONDITION IS NOT A TUNING PARAMETER. Explicit leapfrog on a 2D grid is stable only while
// c*dt/dx <= 1/sqrt(2); past it the scheme does not merely get inaccurate, it amplifies every step and
// the field explodes within a second. The courant number is computed on the CPU and clamped there, and
// this shader takes it as given -- see RtWater.courant, which is where the clamp and its reasoning live.
//
// DAMPING is a per-step multiply rather than a term in the equation. Real water loses ripple energy to
// viscosity and surface tension, fastest at the shortest wavelengths; a uniform multiply is not that, it
// is a blunt approximation that also happens to keep the numerical scheme from ringing forever on the
// grid's own Nyquist frequency. Named as an approximation rather than dressed up as physics.

module water_sim;

import world_common;

public static const int WATER_SIM_DIM = 256;

// h at t (read), h at t-dt (read), h at t+dt (written). Bound as separate images rather than one array
// so the rotation is a descriptor write on the CPU rather than an index the shader has to be told.
[[vk::binding(0, 0)]] Texture2D<float> heightCur;
[[vk::binding(1, 0)]] Texture2D<float> heightPrev;
[[vk::binding(2, 0)]] RWTexture2D<float> heightNext;
// 0 where a block occupies the cell, 1 where there is open water. Baked by water_obstacle.comp.
[[vk::binding(3, 0)]] Texture2D<float> obstacle;
// The same value as heightNext, in a FIXED image the shading always samples.
//
// Written twice rather than rotating what binding 18 points at, and that is a deliberate trade of one
// store per cell against a class of bug. Rotating the shading's view would mean rewriting a descriptor
// in the world pipeline's ring every frame, on sets that an in-flight frame may still be reading -- the
// kind of hazard that shows as an occasional wrong-frame ripple and is nearly impossible to attribute.
// 65k extra stores is nothing; the descriptor churn is not worth it.
[[vk::binding(4, 0)]] RWTexture2D<float> heightDisplay;

struct WaterSimPush {
    float courant2;      // (c*dt/dx)^2, already clamped to the CFL limit on the CPU
    float damping;       // per-step energy retention, 1 = lossless
    float spongeWidth;   // cells of absorbing boundary at the domain edge
    uint  impulseCount;
    uint64_t impulseAddr; // packed WaterImpulse records
};
[[vk::push_constant]] WaterSimPush pc;

// An impulse is a displacement dropped into the field, not a force: the wave equation's state IS the
// surface, so the way to make a splash is to move the surface.
public struct WaterImpulse {
    public float2 cell;    // position in grid cells, fractional
    public float  radius;  // cells
    public float  amount;  // metres of displacement at the centre, already clamped by the CPU
};

/**
 * How much of the wave survives this cell, for the absorbing boundary.
 *
 * <p>WITHOUT THIS THE DOMAIN IS A BATHTUB. A finite grid with no boundary treatment reflects everything
 * that reaches its edge, so every ripple comes back, crosses the ones still going out, and within a few
 * seconds the whole field is standing-wave hash that looks nothing like water. The reference
 * implementation's own notes call this out as the step most often skipped.
 *
 * <p>A sponge rather than a one-cell absorbing condition because the exact non-reflecting boundary for
 * this equation is only exact for waves arriving perpendicular; a graded absorber over several cells
 * takes the oblique ones too, at the cost of a band around the edge where amplitude is not physical.
 * That band is inside the fade the sampler applies anyway, so nothing visible sits in it.
 */
float spongeRetention(int2 id) {
    float edge = float(min(min(id.x, id.y), min(WATER_SIM_DIM - 1 - id.x, WATER_SIM_DIM - 1 - id.y)));
    float t = saturate(edge / max(pc.spongeWidth, 1.0));
    // Quadratic ramp: gentle at the inner lip so there is no reflection off the sponge itself, hard at
    // the outermost cells so nothing survives to bounce.
    return t * t * (3.0 - 2.0 * t);
}

/**
 * The neighbour's height, with a NEUMANN condition at obstacles.
 *
 * <p>A block face is a wall the water cannot flow through, which for this equation means the normal
 * derivative of the height vanishes there. Implemented by reflecting the centre value back rather than
 * reading the cell inside the block -- so the wall behaves as a mirror, which is what a ripple bouncing
 * off a shoreline actually does. Reading the block's cell as zero instead would model a hole that
 * swallows waves, and shorelines would absorb rather than reflect.
 */
float neighbour(int2 id, int2 offset, float centre) {
    int2 q = id + offset;
    if (any(q < int2(0, 0)) || any(q >= int2(WATER_SIM_DIM, WATER_SIM_DIM))) {
        return centre;
    }
    return obstacle[q] < 0.5 ? centre : heightCur[q];
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id3: SV_DispatchThreadID) {
    int2 id = int2(id3.xy);
    if (any(id >= int2(WATER_SIM_DIM, WATER_SIM_DIM))) {
        return;
    }
    // A cell inside a block holds no water and must stay exactly zero: it is the wall the Neumann
    // condition above reflects off, and letting it carry a value would make the wall a source.
    if (obstacle[id] < 0.5) {
        heightNext[id] = 0.0;
        heightDisplay[id] = 0.0;
        return;
    }

    float centre = heightCur[id];
    float laplacian = neighbour(id, int2(1, 0), centre)
                    + neighbour(id, int2(-1, 0), centre)
                    + neighbour(id, int2(0, 1), centre)
                    + neighbour(id, int2(0, -1), centre)
                    - 4.0 * centre;
    float next = 2.0 * centre - heightPrev[id] + pc.courant2 * laplacian;
    next *= pc.damping * spongeRetention(id);

    // Impulses land on the NEXT state rather than being integrated as a force, because the state of this
    // equation is the surface itself: displacing it is what a splash is. Added after the step so an
    // impulse is a fresh displacement rather than something the previous frame's velocity has already
    // half-cancelled.
    if (pc.impulseCount > 0u) {
        ConstPtr<WaterImpulse> impulses = ConstPtr<WaterImpulse>(pc.impulseAddr);
        for (uint i = 0u; i < pc.impulseCount; ++i) {
            WaterImpulse imp = impulses[i];
            float2 d = float2(id) + 0.5 - imp.cell;
            float r2 = dot(d, d);
            float rr = max(imp.radius, 0.5);
            if (r2 < rr * rr) {
                // A smooth bump, not a spike. A single-cell delta contains every wavelength the grid can
                // represent including its Nyquist, which the leapfrog scheme carries forever as a
                // checkerboard that never propagates anywhere.
                float falloff = 1.0 - sqrt(r2) / rr;
                next += imp.amount * falloff * falloff * (3.0 - 2.0 * falloff);
            }
        }
    }

    heightNext[id] = next;
    heightDisplay[id] = next;
}
