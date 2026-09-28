package bd.edu.uiu.unipay.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserProfileResponse(
            String userId,
            String fullName,
            String phoneNumber,
            String role,
            String stallName,
            String stallCategory,
            String avatarUrl,
            String createdAt) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Full name cannot be blank")
            @Size(max = 100, message = "Full name must not exceed 100 characters")
            String fullName,

            @NotBlank(message = "Phone number cannot be blank")
            @Pattern(regexp = "^01[3-9]\\d{8}$", message = "Must be an 11-digit BD mobile number (e.g. 017XXXXXXXX)")
            String phoneNumber,

            @Size(min = 6, max = 100, message = "New password must be at least 6 characters")
            String newPassword,

            String stallName,
            String stallCategory,

            String avatarUrl) {
    }
}
