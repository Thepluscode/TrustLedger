package com.trustledger.api;

import com.trustledger.api.ApiViews.ApiKeyView;
import com.trustledger.api.ApiViews.CreatedApiKeyView;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.ApiKeyService;
import com.trustledger.app.ApiKeyService.Created;
import com.trustledger.persistence.entity.ApiKeyEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Developer API keys (§19). All operations require API_KEY_MANAGE and are tenant-scoped. The
 * plaintext secret is returned exactly once (create/rotate) and is never readable afterwards — the
 * list only ever exposes the public prefix.
 */
@RestController
@RequestMapping("/api/v1/developer/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;
    private final AccessControlService access;

    public ApiKeyController(ApiKeyService apiKeys, AccessControlService access) {
        this.apiKeys = apiKeys;
        this.access = access;
    }

    public record CreateRequest(String name, String scope) {}

    @GetMapping
    public List<ApiKeyView> list() {
        access.require(Permission.API_KEY_MANAGE);
        return apiKeys.list(CurrentUser.tenantId()).stream().map(ApiKeyController::view).toList();
    }

    @PostMapping
    public CreatedApiKeyView create(@RequestBody CreateRequest body) {
        access.require(Permission.API_KEY_MANAGE);
        Created created = apiKeys.create(CurrentUser.tenantId(), CurrentUser.userId(), body.name(), body.scope());
        return created(created);
    }

    @PostMapping("/{id}/rotate")
    public CreatedApiKeyView rotate(@PathVariable UUID id) {
        access.require(Permission.API_KEY_MANAGE);
        return created(apiKeys.rotate(CurrentUser.tenantId(), CurrentUser.userId(), id));
    }

    @PostMapping("/{id}/revoke")
    public ApiKeyView revoke(@PathVariable UUID id) {
        access.require(Permission.API_KEY_MANAGE);
        return view(apiKeys.revoke(CurrentUser.tenantId(), CurrentUser.userId(), id));
    }

    private static CreatedApiKeyView created(Created c) {
        ApiKeyEntity k = c.key();
        return new CreatedApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getScope(), c.secret());
    }

    private static ApiKeyView view(ApiKeyEntity k) {
        return new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getScope(), k.getCreatedBy(),
            k.getCreatedAt(), k.getLastUsedAt(), k.getRotatedAt(), k.getRevokedAt(), k.isRevoked());
    }
}
