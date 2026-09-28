package bd.edu.uiu.unipay.splitpay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SplitBillRepository extends JpaRepository<SplitBill, String> {

    @Query("select b from SplitBill b left join fetch b.requests r left join fetch r.participant left join fetch b.creator where b.creator.userId = :creatorId order by b.createdAt desc")
    List<SplitBill> findByCreatorWithRequests(@Param("creatorId") String creatorId);

    @Query("select b from SplitBill b left join fetch b.requests r left join fetch r.participant left join fetch b.creator where b.billId = :billId")
    Optional<SplitBill> findByIdWithRequests(@Param("billId") String billId);
}
