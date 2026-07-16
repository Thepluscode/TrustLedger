package com.trustledger.secrets;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentSecretResolverTest {

    @Test
    void resolvesOnlyValidEnvReferences() {
        MockEnvironment environment = new MockEnvironment().withProperty("PAYSTACK_TEST_KEY", "sk_test_not-real");
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(environment);

        assertEquals("sk_test_not-real", resolver.resolve("env://PAYSTACK_TEST_KEY"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("vault://PAYSTACK_TEST_KEY"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("env://bad-name"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("env://MISSING_KEY"));
    }
}