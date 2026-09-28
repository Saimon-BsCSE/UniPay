package bd.edu.uiu.unipay.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByUserIdOrPhoneNumber(String userId, String phoneNumber);
}
