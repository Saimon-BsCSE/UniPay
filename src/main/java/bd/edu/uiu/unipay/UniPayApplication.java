package bd.edu.uiu.unipay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

/**
 * UniPay — Enclosed Campus Money Management &amp; Real-Time Digital Wallet.
 * <p>Advanced OOP (CSE 3118 / CSE 2118) lab project,
 * Department of CSE, United International University.</p>
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class UniPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniPayApplication.class, args);
    }
}

