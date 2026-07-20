package com.trustledger.api;

import com.trustledger.api.ApiViews.InvitedUserView;
import com.trustledger.api.ApiViews.UserView;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.UserService;
import com.trustledger.app.UserService.Invited;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Team management (§17.3). All operations require USER_MANAGE; the OWNER guards live in UserService.
 * The list never returns password hashes; invite returns a one-time temp password.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final AccessControlService access;

    public UserController(UserService userService, AccessControlService access) {
        this.userService = userService;
        this.access = access;
    }

    public record InviteRequest(String email, String role) {}
    public record ChangeRoleRequest(String role) {}

    @GetMapping
    public List<UserView> list() {
        access.require(Permission.USER_MANAGE);
        return userService.list(CurrentUser.tenantId()).stream().map(UserController::view).toList();
    }

    @PostMapping("/invite")
    public InvitedUserView invite(@RequestBody InviteRequest body) {
        access.require(Permission.USER_MANAGE);
        Invited invited = userService.invite(CurrentUser.tenantId(), CurrentUser.role(), CurrentUser.userId(),
            body.email(), body.role());
        return new InvitedUserView(invited.user().getId(), invited.user().getEmail(), invited.user().getRole(),
            invited.temporaryPassword());
    }

    @PatchMapping("/{id}/role")
    public UserView changeRole(@PathVariable UUID id, @RequestBody ChangeRoleRequest body) {
        access.require(Permission.USER_MANAGE);
        return view(userService.changeRole(CurrentUser.tenantId(), CurrentUser.role(), CurrentUser.userId(), id, body.role()));
    }

    private static UserView view(UserEntity u) {
        return new UserView(u.getId(), u.getEmail(), u.getRole(), u.getCreatedAt());
    }
}
