package bd.edu.uiu.unipay.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(String userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadForUser(@Param("userId") String userId);
}
