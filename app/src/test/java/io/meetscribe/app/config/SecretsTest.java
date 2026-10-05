package io.meetscribe.app.config;

import com.sun.jna.Native;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretsTest {
    @Test void supportsEnvironmentReferencesWithoutAcceptingPlaintext() {
        assertEquals("", Secrets.resolve(""));
        String name = System.getenv().keySet().stream().filter(k -> k.matches("[A-Za-z0-9_.-]+") && !System.getenv(k).isBlank()).findFirst().orElseThrow();
        assertEquals(System.getenv(name), Secrets.resolve("{env:" + name + "}"));
        Exception error = assertThrows(IllegalArgumentException.class, () -> Secrets.resolve("secret-value"));
        assertFalse(error.getMessage().contains("secret-value"));
        assertThrows(IllegalArgumentException.class, () -> Secrets.resolve("{env:MEETSCRIBE_TEST_ABSENT_728492740}"));
        assertThrows(IllegalArgumentException.class, () -> Secrets.resolve(null));
    }
    @Test void localOnlyJvmPolicyCannotBeDisabledByConfiguration() {
        String old = System.getProperty("meetscribe.localOnly");
        try { System.setProperty("meetscribe.localOnly", "true"); assertTrue(Secrets.enforcedLocalOnly()); }
        finally { if (old == null) System.clearProperty("meetscribe.localOnly"); else System.setProperty("meetscribe.localOnly", old); }
    }
    @Test void credentialStructMatchesWindowsAbiLayoutWithoutOpeningVault() {
        CredentialLayout c = new CredentialLayout();
        assertEquals(Native.POINTER_SIZE == 8 ? 80 : 52, c.size());
        assertEquals(Native.POINTER_SIZE == 8 ? 40 : 28, c.blobOffset());
    }
    public static class CredentialLayout extends Secrets.Credential {
        public int blobOffset() { return fieldOffset("blob"); }
    }
}
