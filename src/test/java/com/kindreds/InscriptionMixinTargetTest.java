package com.kindreds;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inscription button hangs off a mixin, and mixins fail quietly.
 *
 * <p>{@code InscriptionTableScreenMixin} carries {@code require = 0} on purpose: a base-mod rename
 * should cost the button, not the client. The price of that choice is that a rename produces no
 * error at all - the button simply stops appearing, and nobody finds out until they go looking for
 * it. This is what turns that silence back into a failing build.
 *
 * <p>The class is read out of the jar rather than loaded, because loading a screen class drags in
 * Minecraft's rendering hierarchy and fails outside a running game.
 */
class InscriptionMixinTargetTest {

    private static final String TARGET =
        "net/sevenstars/middleearth/gui/inscriptiontable/InscriptionTableScreen";

    /** Yarn maps Screen.init to method_25426, which is what the mixin actually injects into. */
    private static final String INIT = "method_25426";

    @Test
    void theTableScreenAndItsInitStillExist() throws Exception {
        Path jar = findBaseModJar();
        assertNotNull(jar, "Middle-earth is not on the test classpath - check build.gradle");

        List<String> methods = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile());
             InputStream in = archive.getInputStream(archive.getEntry(TARGET + ".class"))) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    methods.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }

        assertTrue(methods.contains(INIT),
            "InscriptionTableScreen no longer has " + INIT + " - the inscriptions button on the "
                + "table has silently stopped appearing. Its methods are now: " + methods);
    }

    private static Path findBaseModJar() throws Exception {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path candidate = Path.of(element);
            if (!Files.isRegularFile(candidate) || !element.endsWith(".jar")) {
                continue;
            }
            try (JarFile archive = new JarFile(candidate.toFile())) {
                if (archive.getEntry(TARGET + ".class") != null) {
                    return candidate;
                }
            } catch (Exception unreadable) {
                // Not our jar; keep looking.
            }
        }
        return null;
    }
}
