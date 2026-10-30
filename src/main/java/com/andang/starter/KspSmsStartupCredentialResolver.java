package com.andang.starter;

import com.andang.CredentialClient;
import com.andang.entity.CipherLifecycleResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动期冷启动自愈：
 * 1. 优先通过 cipher 生命周期判断当前实例是否可用
 * 2. 首次拿到不可用实例时，按 renew / reapply 规则先自愈
 * 3. 最终把最新可用明文返回给 EnvironmentPostProcessor 注入 Spring Environment
 */
@Slf4j
class KspSmsStartupCredentialResolver {

    interface CredentialOperations {
        void ensureLogin();

        String getCredential(String label);

        CipherLifecycleResult fetchCipher(String label);

        void renewCredential(long id, String label, long renewTtlSec);

        void reapplyCredential(String label);

        String decryptCipherLifecycle(CipherLifecycleResult lifecycle);
    }

    static KspSmsStartupCredentialResolver fromClient(CredentialClient client, KspSmsProperties properties) {
        return new KspSmsStartupCredentialResolver(new ClientAdapter(client), properties);
    }

    private final CredentialOperations operations;
    private final KspSmsProperties properties;

    KspSmsStartupCredentialResolver(CredentialOperations operations, KspSmsProperties properties) {
        this.operations = operations;
        this.properties = properties;
    }

    String resolveDecryptedContent(String label) {
        operations.ensureLogin();

        if (!isLifecycleStartupEnabled()) {
            return operations.getCredential(label);
        }

        CipherLifecycleResult lifecycle;
        try {
            lifecycle = operations.fetchCipher(label);
        } catch (Exception e) {
            log.warn("KSP SMS startup fetchCipher failed; fallback to direct credential retrieval");
            log.debug("KSP SMS startup fetchCipher failure", e);
            return operations.getCredential(label);
        }

        if (!isFetchCipherSuccess(lifecycle)) {
            log.warn("KSP SMS startup fetchCipher returned non-zero code; fallback to direct credential retrieval");
            return operations.getCredential(label);
        }

        if (hasUsableCipher(lifecycle)) {
            return operations.decryptCipherLifecycle(lifecycle);
        }

        String action = resolveCipherAction(lifecycle);
        if ("reapply".equals(action)) {
            operations.reapplyCredential(label);
            return decryptAfterRefetch(label, "startup reapply");
        }

        if ("renew".equals(action)) {
            long renewTtlSec = properties.getRefresh() != null ? properties.getRefresh().getRenewTtlSeconds() : 0L;
            if (renewTtlSec > 0L) {
                long id = lifecycle.getId() != null ? lifecycle.getId() : 0L;
                try {
                    operations.renewCredential(id, label, renewTtlSec);
                    return decryptAfterRefetch(label, "startup renew");
                } catch (Exception renewEx) {
                    log.warn("KSP SMS startup renew failed; fallback to reapply");
                    log.debug("KSP SMS startup renew failure", renewEx);
                }
            } else {
                log.warn("KSP SMS startup renew_ttl <= 0; fallback to reapply");
            }

            operations.reapplyCredential(label);
            return decryptAfterRefetch(label, "startup renew fallback reapply");
        }

        throw new IllegalStateException("startup fetchCipher returned unusable credential with no recovery action");
    }

    private String decryptAfterRefetch(String label, String actionDesc) {
        CipherLifecycleResult lifecycle = operations.fetchCipher(label);
        if (!isFetchCipherSuccess(lifecycle)) {
            throw new IllegalStateException(actionDesc + " refetch failed");
        }
        if (!hasUsableCipher(lifecycle)) {
            throw new IllegalStateException(actionDesc + " still has no usable cipher");
        }
        String decrypted = operations.decryptCipherLifecycle(lifecycle);
        if (decrypted == null) {
            throw new IllegalStateException(actionDesc + " decrypt returned null");
        }
        return decrypted;
    }

    private boolean isLifecycleStartupEnabled() {
        return properties != null
                && properties.getRefresh() != null
                && properties.getRefresh().isLifecycleDriven();
    }

    private static boolean isFetchCipherSuccess(CipherLifecycleResult lifecycle) {
        return lifecycle != null && (lifecycle.getCode() == null || lifecycle.getCode() == 0);
    }

    private static String resolveCipherAction(CipherLifecycleResult lifecycle) {
        if (lifecycle == null) {
            return "use";
        }
        String status = lifecycle.getStatus() != null ? lifecycle.getStatus().trim() : "";
        if ("Disabled".equalsIgnoreCase(status)) {
            return "";
        }

        String next = lifecycle.getNextAction();
        if (next != null && !next.trim().isEmpty()) {
            String normalized = next.trim().toLowerCase();
            if ("use".equals(normalized) || "renew".equals(normalized) || "reapply".equals(normalized)) {
                return normalized;
            }
        }

        if (Boolean.TRUE.equals(lifecycle.getNeedReapply())) {
            return "reapply";
        }
        if (Boolean.FALSE.equals(lifecycle.getCanRenew())) {
            return "reapply";
        }
        if (!hasUsableCipher(lifecycle)) {
            return "renew";
        }
        return "use";
    }

    private static boolean hasUsableCipher(CipherLifecycleResult lifecycle) {
        if (lifecycle == null) {
            return false;
        }
        String cipherKey = lifecycle.getCipherKey();
        String ciphertext = lifecycle.getCiphertext();
        return cipherKey != null && !cipherKey.isEmpty()
                && ciphertext != null && !ciphertext.isEmpty();
    }

    private static final class ClientAdapter implements CredentialOperations {
        private final CredentialClient client;

        private ClientAdapter(CredentialClient client) {
            this.client = client;
        }

        @Override
        public void ensureLogin() {
            client.ensureLogin();
        }

        @Override
        public String getCredential(String label) {
            return client.getCredential(label);
        }

        @Override
        public CipherLifecycleResult fetchCipher(String label) {
            return client.fetchCipher(label);
        }

        @Override
        public void renewCredential(long id, String label, long renewTtlSec) {
            client.renewCredential(id, label, renewTtlSec);
        }

        @Override
        public void reapplyCredential(String label) {
            client.reapplyCredential(label);
        }

        @Override
        public String decryptCipherLifecycle(CipherLifecycleResult lifecycle) {
            return client.decryptCipherLifecycle(lifecycle);
        }
    }
}
