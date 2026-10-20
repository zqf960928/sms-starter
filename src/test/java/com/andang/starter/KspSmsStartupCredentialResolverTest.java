package com.andang.starter;

import com.andang.entity.CipherLifecycleResult;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class KspSmsStartupCredentialResolverTest {

    @Test
    public void testFallbackToDirectGetCredentialWhenFetchCipherUnavailable() {
        StubOperations operations = new StubOperations();
        operations.fetchCipherFailure = new RuntimeException("cipher api unavailable");
        operations.directContent = "{\"username\":\"demo\"}";

        KspSmsStartupCredentialResolver resolver = new KspSmsStartupCredentialResolver(operations, buildProperties(300L, true));
        String result = resolver.resolveDecryptedContent("mysql-sh");

        assertEquals("{\"username\":\"demo\"}", result);
        assertEquals(list("ensureLogin", "fetchCipher:mysql-sh", "getCredential:mysql-sh"), operations.calls);
    }

    @Test
    public void testUseExistingCipherWhenStartupCredentialIsAlreadyUsable() {
        StubOperations operations = new StubOperations();
        operations.fetchCipherResults.add(usableLifecycle("encrypted"));
        operations.decryptedContent = "{\"username\":\"fresh\"}";

        KspSmsStartupCredentialResolver resolver = new KspSmsStartupCredentialResolver(operations, buildProperties(300L, true));
        String result = resolver.resolveDecryptedContent("mysql-sh");

        assertEquals("{\"username\":\"fresh\"}", result);
        assertEquals(list("ensureLogin", "fetchCipher:mysql-sh", "decryptCipherLifecycle"), operations.calls);
    }

    @Test
    public void testReapplyWhenFirstCredentialNeedsReapply() {
        StubOperations operations = new StubOperations();
        operations.fetchCipherResults.add(unusableLifecycle("reapply", false, true, 10L));
        operations.fetchCipherResults.add(usableLifecycle("encrypted-after-reapply"));
        operations.decryptedContent = "{\"username\":\"reapplied\"}";

        KspSmsStartupCredentialResolver resolver = new KspSmsStartupCredentialResolver(operations, buildProperties(300L, true));
        String result = resolver.resolveDecryptedContent("mysql-sh");

        assertEquals("{\"username\":\"reapplied\"}", result);
        assertEquals(list(
                "ensureLogin",
                "fetchCipher:mysql-sh",
                "reapplyCredential:mysql-sh",
                "fetchCipher:mysql-sh",
                "decryptCipherLifecycle"
        ), operations.calls);
    }

    @Test
    public void testRenewThenDecryptWhenFirstCredentialIsUnusable() {
        StubOperations operations = new StubOperations();
        operations.fetchCipherResults.add(unusableLifecycle("renew", true, false, 66L));
        operations.fetchCipherResults.add(usableLifecycle("encrypted-after-renew"));
        operations.decryptedContent = "{\"username\":\"renewed\"}";

        KspSmsStartupCredentialResolver resolver = new KspSmsStartupCredentialResolver(operations, buildProperties(600L, true));
        String result = resolver.resolveDecryptedContent("mysql-sh");

        assertEquals("{\"username\":\"renewed\"}", result);
        assertEquals(list(
                "ensureLogin",
                "fetchCipher:mysql-sh",
                "renewCredential:66:mysql-sh:600",
                "fetchCipher:mysql-sh",
                "decryptCipherLifecycle"
        ), operations.calls);
    }

    @Test
    public void testRenewFailureFallsBackToReapply() {
        StubOperations operations = new StubOperations();
        operations.fetchCipherResults.add(unusableLifecycle("renew", true, false, 88L));
        operations.fetchCipherResults.add(usableLifecycle("encrypted-after-fallback"));
        operations.renewFailure = new RuntimeException("renew failed");
        operations.decryptedContent = "{\"username\":\"fallback\"}";

        KspSmsStartupCredentialResolver resolver = new KspSmsStartupCredentialResolver(operations, buildProperties(300L, true));
        String result = resolver.resolveDecryptedContent("mysql-sh");

        assertEquals("{\"username\":\"fallback\"}", result);
        assertEquals(list(
                "ensureLogin",
                "fetchCipher:mysql-sh",
                "renewCredential:88:mysql-sh:300",
                "reapplyCredential:mysql-sh",
                "fetchCipher:mysql-sh",
                "decryptCipherLifecycle"
        ), operations.calls);
    }

    private static KspSmsProperties buildProperties(long renewTtlSeconds, boolean lifecycleDriven) {
        KspSmsProperties properties = new KspSmsProperties();
        properties.setRefresh(new KspSmsProperties.Refresh());
        properties.getRefresh().setRenewTtlSeconds(renewTtlSeconds);
        properties.getRefresh().setLifecycleDriven(lifecycleDriven);
        return properties;
    }

    private static CipherLifecycleResult usableLifecycle(String ciphertext) {
        CipherLifecycleResult lifecycle = new CipherLifecycleResult();
        lifecycle.setCode(0);
        lifecycle.setStatus("Active");
        lifecycle.setNextAction("use");
        lifecycle.setCipherKey("cipher-key");
        lifecycle.setCiphertext(ciphertext);
        return lifecycle;
    }

    private static CipherLifecycleResult unusableLifecycle(String nextAction, boolean canRenew, boolean needReapply, long id) {
        CipherLifecycleResult lifecycle = new CipherLifecycleResult();
        lifecycle.setCode(0);
        lifecycle.setStatus("Active");
        lifecycle.setNextAction(nextAction);
        lifecycle.setCanRenew(canRenew);
        lifecycle.setNeedReapply(needReapply);
        lifecycle.setId(id);
        return lifecycle;
    }

    private static List<String> list(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static class StubOperations implements KspSmsStartupCredentialResolver.CredentialOperations {
        private final Deque<CipherLifecycleResult> fetchCipherResults = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private RuntimeException fetchCipherFailure;
        private RuntimeException renewFailure;
        private String directContent;
        private String decryptedContent;

        @Override
        public void ensureLogin() {
            calls.add("ensureLogin");
        }

        @Override
        public String getCredential(String label) {
            calls.add("getCredential:" + label);
            return directContent;
        }

        @Override
        public CipherLifecycleResult fetchCipher(String label) {
            calls.add("fetchCipher:" + label);
            if (fetchCipherFailure != null) {
                throw fetchCipherFailure;
            }
            return fetchCipherResults.removeFirst();
        }

        @Override
        public void renewCredential(long id, String label, long renewTtlSec) {
            calls.add("renewCredential:" + id + ":" + label + ":" + renewTtlSec);
            if (renewFailure != null) {
                throw renewFailure;
            }
        }

        @Override
        public void reapplyCredential(String label) {
            calls.add("reapplyCredential:" + label);
        }

        @Override
        public String decryptCipherLifecycle(CipherLifecycleResult lifecycle) {
            calls.add("decryptCipherLifecycle");
            return decryptedContent;
        }
    }
}
