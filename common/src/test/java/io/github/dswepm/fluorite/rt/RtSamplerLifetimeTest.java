package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every long-lived sampler {@link RtComposite} creates must also be destroyed.
 *
 * <p>This is a whole-class invariant rather than a check on one handle, because the leak it was written
 * for could not be seen by looking at any single sampler. {@code lutSampler} was created lazily, bound
 * to nine descriptor slots, and never released — and the reason that survived review is that a leaked
 * sampler has no symptom. Nothing renders wrong, nothing throws, nothing logs; the handle is simply not
 * given back, once per device recreation. The only way it shows up is by counting.
 *
 * <p>So the test counts. It is deliberately mechanical: any field matching {@code private long *Sampler}
 * must have its name appear inside a {@code vkDestroySampler} call somewhere in the file. That is a
 * weaker claim than "is destroyed on every path" — the JVM cannot execute Vulkan, so reachability is
 * out of reach here — but it is exactly strong enough to catch the failure that actually happened,
 * which is a destroy that was never written at all. It also generalises: the next sampler added to this
 * class is covered the moment it is declared, with nobody having to remember this file exists.
 */
final class RtSamplerLifetimeTest {
    private static final Pattern FIELD =
            Pattern.compile("^\\s*private(?:\\s+static)?\\s+long\\s+(\\w*[Ss]ampler)\\s*;", Pattern.MULTILINE);

    @Test
    void everySamplerFieldIsAlsoDestroyed() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        List<String> fields = new ArrayList<>();
        Matcher m = FIELD.matcher(composite);
        while (m.find()) {
            fields.add(m.group(1));
        }

        // If this trips, the regex stopped matching the declarations rather than the class losing its
        // samplers -- a silently empty candidate list would make every assertion below vacuously pass.
        assertTrue(fields.size() >= 6,
                "expected RtComposite to declare at least six sampler fields, found " + fields);

        List<String> leaked = new ArrayList<>();
        for (String field : fields) {
            Pattern destroy = Pattern.compile("vkDestroySampler\\s*\\([^;]*\\b" + Pattern.quote(field) + "\\b");
            if (!destroy.matcher(composite).find()) {
                leaked.add(field);
            }
        }

        if (!leaked.isEmpty()) {
            fail("RtComposite creates these samplers but never calls vkDestroySampler on them: " + leaked
                    + ". A leaked sampler has no visible symptom -- add the destroy next to the others in "
                    + "the cleanup block rather than waiting for a review to count handles again.");
        }
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(relativePath));
    }
}
