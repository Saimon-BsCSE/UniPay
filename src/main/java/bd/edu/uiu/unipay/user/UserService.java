package bd.edu.uiu.unipay.user;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.vendor.StallCategory;
import bd.edu.uiu.unipay.vendor.VendorProfile;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final VendorProfileRepository vendorProfileRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       VendorProfileRepository vendorProfileRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.vendorProfileRepository = vendorProfileRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserDtos.UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));

        String stallName = null;
        String stallCategory = null;
        if (user.getRole() == Role.VENDOR) {
            Optional<VendorProfile> vp = vendorProfileRepository.findById(userId);
            if (vp.isPresent()) {
                stallName = vp.get().getStallName();
                stallCategory = vp.get().getStallCategory() != null ? vp.get().getStallCategory().name() : null;
            }
        }

        return new UserDtos.UserProfileResponse(
                user.getUserId(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getRole().name(),
                stallName,
                stallCategory,
                user.getAvatarUrl(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null
        );
    }

    @Transactional
    public UserDtos.UserProfileResponse updateProfile(String userId, UserDtos.UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));

        String trimmedPhone = request.phoneNumber().trim();
        if (!trimmedPhone.equals(user.getPhoneNumber())) {
            Optional<User> existing = userRepository.findByPhoneNumber(trimmedPhone);
            if (existing.isPresent() && !existing.get().getUserId().equals(userId)) {
                throw ApiException.conflict("Phone number " + trimmedPhone + " is already in use by another account.");
            }
            user.setPhoneNumber(trimmedPhone);
        }

        user.setFullName(request.fullName().trim());

        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl().trim());
        }

        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            if (request.newPassword().trim().length() < 6) {
                throw ApiException.badRequest("New password must be at least 6 characters.");
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword().trim()));
        }

        userRepository.save(user);

        String stallName = null;
        String stallCategory = null;
        if (user.getRole() == Role.VENDOR) {
            VendorProfile vp = vendorProfileRepository.findById(userId)
                    .orElseGet(() -> new VendorProfile(user, request.stallName() != null ? request.stallName().trim() : user.getFullName(), StallCategory.OTHER));

            if (request.stallName() != null && !request.stallName().isBlank()) {
                vp.setStallName(request.stallName().trim());
            }
            if (request.stallCategory() != null && !request.stallCategory().isBlank()) {
                try {
                    vp.setStallCategory(StallCategory.valueOf(request.stallCategory().trim().toUpperCase()));
                } catch (IllegalArgumentException ignore) {
                    // keep current category if invalid
                }
            }
            vendorProfileRepository.save(vp);
            stallName = vp.getStallName();
            stallCategory = vp.getStallCategory() != null ? vp.getStallCategory().name() : null;
        }

        return new UserDtos.UserProfileResponse(
                user.getUserId(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getRole().name(),
                stallName,
                stallCategory,
                user.getAvatarUrl(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null
        );
    }
}
