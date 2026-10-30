package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** R2b-8: real GPU witnesses and an independent angle-based double oracle. */
final class RtPathFresnelStabilityTest {
    private record V(float x, float y, float z) {
        V add(V b) { return new V(x+b.x,y+b.y,z+b.z); }
        V sub(V b) { return new V(x-b.x,y-b.y,z-b.z); }
        V mul(float s) { return new V(x*s,y*s,z*s); }
        float dot(V b) { return x*b.x+y*b.y+z*b.z; }
        V unit() { return mul((float)(1/Math.sqrt(dot(this)))); }
    }
    private record D(double x,double y,double z) {
        D add(D b) { return new D(x+b.x,y+b.y,z+b.z); }
        D mul(double s) { return new D(x*s,y*s,z*s); }
        double dot(D b) { return x*b.x+y*b.y+z*b.z; }
        D cross(D b) { return new D(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        D unit() { return mul(1/Math.sqrt(dot(this))); }
        V floats() { return new V((float)x,(float)y,(float)z); }
    }
    private record Witness(V view,V outgoing,float sourceCos,float evaluatedCos) {}
    private static float fifth(float m) { float m2=m*m; return m2*m2*m; }
    private static float complement(V view,V outgoing) {
        V v=view.unit(), l=outgoing.unit(), difference=v.sub(l), sum=v.add(l);
        return Math.clamp(.25f*difference.dot(difference)/(1+.5f*(float)Math.sqrt(sum.dot(sum))),0,1);
    }
    private static double oracle(V view,V outgoing,double f0) {
        D v=new D(view.x,view.y,view.z).unit(), l=new D(outgoing.x,outgoing.y,outgoing.z).unit();
        D cross=v.cross(l);
        double angle=Math.atan2(Math.sqrt(cross.dot(cross)),v.dot(l));
        double m=2*Math.pow(Math.sin(angle/4),2);
        return f0+(1-f0)*Math.pow(m,5);
    }
    private static double error(double a,double b) { return a==0 && b==0 ? 0 : Math.abs(a-b)/Math.max(Math.abs(a),Math.abs(b)); }
    private static Path root() {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle"))) p=p.getParent();
        return Objects.requireNonNull(p);
    }
    private static List<Witness> witnesses() throws Exception {
        List<Witness> rows=new ArrayList<>();
        try(var input=Objects.requireNonNull(RtPathFresnelStabilityTest.class.getResourceAsStream("/rt-path-fresnel-witness.csv"))) {
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if(line.startsWith("#") || line.isBlank()) continue;
                float[] a=new float[8]; String[] words=line.split(",");
                for(int i=0;i<8;i++) a[i]=Float.parseFloat(words[i]);
                rows.add(new Witness(new V(a[0],a[1],a[2]),new V(a[3],a[4],a[5]),a[6],a[7]));
            }
        }
        return rows;
    }
    @Test void capturedCosinesReproduceTheOriginalNineteenPercentFailure() throws Exception {
        List<Witness> rows=witnesses(); assertEquals(25,rows.size());
        double maximum=0;
        for(Witness w:rows) {
            double e=error(fifth(Math.clamp(1-w.sourceCos,0,1)),fifth(Math.clamp(1-w.evaluatedCos,0,1)));
            assertTrue(e>.01); maximum=Math.max(maximum,e);
        }
        assertEquals(.19168067,maximum,2e-6);
    }
    @Test void stableFormulaMatchesTheAngleOracleOnAllCapturedDirections() throws Exception {
        for(Witness w:witnesses()) assertTrue(error(fifth(complement(w.view,w.outgoing)),oracle(w.view,w.outgoing,0))<.001);
    }
    @Test void normalizedDirectionalLimitsPreserveZeroF0AndTheGrazingLimit() {
        V up=new V(0,0,3), down=new V(0,0,-2);
        assertEquals(0,complement(up,up)); assertEquals(1,complement(up,down));
        assertEquals(0,fifth(complement(up,up))); assertEquals(1,fifth(complement(up,down)));
    }
    @Test void colouredF0AndBothAngularLimitsMatchAnIndependentDoubleOracle() {
        SplittableRandom random=new SplittableRandom(20261031);
        for(int i=0;i<5000;i++) {
            double z=random.nextDouble(-1,1),phi=random.nextDouble(2*Math.PI), r=Math.sqrt(1-z*z);
            D v=new D(r*Math.cos(phi),r*Math.sin(phi),z);
            D tangent=v.cross(Math.abs(z)<.9 ? new D(0,0,1) : new D(1,0,0)).unit();
            double angle=Math.exp(random.nextDouble(Math.log(.001),Math.log(Math.PI-.002)));
            V a=v.floats(),b=v.mul(Math.cos(angle)).add(tangent.mul(Math.sin(angle))).floats();
            float power=fifth(complement(a,b));
            for(float f0:new float[]{0,.04f,.9f}) {
                float actual=f0+(1-f0)*power;
                assertTrue(error(actual,oracle(a,b,f0))<.001);
            }
        }
    }
    @Test void actualSamplerAndEvaluatorShareTheHelperWhileTheOffTracerStaysUntouched() throws Exception {
        String helper=Files.readString(root().resolve("shaders/world/path_reuse_fresnel.slang"));
        for(String term:new String[]{"float3 v = normalize(view);","float3 l = normalize(outgoing);",
                "0.25 * dot(difference, difference)","1.0 + 0.5 * sqrt(dot(sum, sum))",
                "clamp(numerator / denominator, 0.0, 1.0)","f0 + (1.0 - f0) * (m2 * m2 * m)"})
            assertTrue(helper.contains(term),term);
        assertFalse(helper.contains("rndf(") || helper.contains("traceRadiance(") || helper.contains("worldPush"));
        String trace=Files.readString(root().resolve("shaders/world/path_reuse_trace.slang")).replace("\r\n","\n");
        assertTrue(trace.contains("#include \"path_reuse_fresnel.slang\""));
        assertTrue(trace.contains("reuseFresnelSchlickDirections(v, l, F0)"));
        assertTrue(trace.contains("reuseFresnelSchlickDirections(v, l,\n"));
        assertTrue(trace.contains("fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0)"));
        String eval=Files.readString(root().resolve("shaders/world/path_reuse_eval.slang")).replace("\r\n","\n");
        assertTrue(eval.contains("reuseFresnelSchlickDirections(c.v, wi, c.F0)"));
        assertTrue(eval.contains("reuseFresnelSchlickDirections(c.v, wi,\n"));
        assertFalse(Files.readString(root().resolve("shaders/world/world.rgen.slang")).contains("reuseFresnel"));
    }
}
