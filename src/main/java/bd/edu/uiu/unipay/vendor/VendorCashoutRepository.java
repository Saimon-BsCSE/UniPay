package bd.edu.uiu.unipay.vendor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorCashoutRepository extends JpaRepository<VendorCashout, String> {

    List<VendorCashout> findByVendor_UserIdOrderByCreatedAtDesc(String vendorId);
}
