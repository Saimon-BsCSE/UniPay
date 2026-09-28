package bd.edu.uiu.unipay.user;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile", description = "User profile information and updates for all roles")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Get authenticated user profile")
    @GetMapping("/profile")
    public UserDtos.UserProfileResponse getProfile(@AuthenticationPrincipal AppUserPrincipal principal) {
        return userService.getProfile(principal.getId());
    }

    @Operation(summary = "Update authenticated user profile (name, phone, password, stall details)")
    @PutMapping("/profile")
    public UserDtos.UserProfileResponse updateProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                                      @Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return userService.updateProfile(principal.getId(), request);
    }
}
