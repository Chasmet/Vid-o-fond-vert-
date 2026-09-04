package com.chasmet.fondvertstudio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public final class ChecksumVerifierTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void verifiesStandardSha256File() throws Exception {
        File file = temporaryFolder.newFile("app.apk");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        String expected = "ba7816bf8f01cfea414140de5dae2223"
                + "b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, ChecksumVerifier.sha256(file));
        ChecksumVerifier.verifySha256(file, expected + "  Fond-Vert-Studio.apk\n");
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsWrongHash() throws Exception {
        File file = temporaryFolder.newFile("app.apk");
        ChecksumVerifier.verifySha256(file,
                "0000000000000000000000000000000000000000000000000000000000000000");
    }
}
