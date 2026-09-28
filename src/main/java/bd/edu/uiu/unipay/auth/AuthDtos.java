package bd.edu.uiu.unipay.auth;

import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.vendor.StallCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request/response contracts of the authentication endpoints.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9-]{3,50}$",
                    message = "University ID must be 3-50 letters/digits/hyphens")
            String userId,

            @NotBlank
            @Size(max = 100)
            String fullName,

            @NotBlank
            @Pattern(regexp = "^01[3-9]\\d{8}$", message = "must be an 11-digit BD mobile number")
            String phoneNumber,

            @NotBlank
            @Size(min = 6, max = 72, message = "must be at least 6 characters")
            String password,

            @NotNull
            Role role,

            /** Required only when role = VENDOR. */
            @Size(max = 100)
            String stallName,

            /** Required only when role = VENDOR. */
            StallCategory stallCategory) {
    }

    public record LoginRequest(
            @NotBlank String userId,
            @NotBlank String password) {
    }

    public record UserDto(String userId, String fullName, String phoneNumber, String role, String avatarUrl) {
        public UserDto(String userId, String fullName, String phoneNumber, String role) {
            this(userId, fullName, phoneNumber, role, null);
        }

        public static UserDto from(User user) {
            return new UserDto(user.getUserId(), user.getFullName(),
                    user.getPhoneNumber(), user.getRole().name(), user.getAvatarUrl());
        }
    }

    public record AuthResponse(String token, UserDto user) {
    }
}
