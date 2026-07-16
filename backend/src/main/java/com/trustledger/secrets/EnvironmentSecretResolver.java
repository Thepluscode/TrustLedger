package com.trustledger.secrets;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Supports {@code env://VARIABLE_NAME} references. Other secret managers can implement the same boundary. */
@Component
public class EnvironmentSecretResolver implements SecretResolver {

    private static final Pattern VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{2,127}");
    private final Environment environment;

    public EnvironmentSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolve(String secretReference) {
        if (secretReference == null || secretReference.isBlank()) {
            throw new IllegalArgumentException("Secret reference is required");
        }
        URI uri;
        try {
            uri = URI.create(secretReference.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid secret reference");
        }
        if (!"env".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported secret reference scheme");
        }
        String variable = uri.getHost();
        if (variable == null || variable.isBlank()) {
            variable = uri.getSchemeSpecificPart();
            while (variable != null && variable.startsWith("//")) variable = variable.substring(2);
        }
        if (variable == null || !VARIABLE.matcher(variable).matches()) {
            throw new IllegalArgumentException("Invalid environment secret reference");
        }
        String secret = environment.getProperty(variable);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Referenced secret is unavailable");
        }
        return secret;
    }
}