package bd.edu.uiu.unipay.splitpay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SplitRequestRepository extends JpaRepository<SplitRequest, String> {

    @Query("select r from SplitRequest r join fetch r.bill b join fetch b.creator join fetch r.participant where r.participant.userId = :participantId order by r.createdAt desc")
    List<SplitRequest> findByParticipantWithDetails(@Param("participantId") String participantId);

    @Query("select r from SplitRequest r join fetch r.bill b join fetch b.creator join fetch r.participant where r.requestId = :requestId")
    Optional<SplitRequest> findByIdWithDetails(@Param("requestId") String requestId);

    long countByParticipant_UserIdAndStatus(String participantId, SplitRequestStatus status);

    List<SplitRequest> findByBill_BillId(String billId);
}
