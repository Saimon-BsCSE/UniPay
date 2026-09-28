package bd.edu.uiu.unipay.config;

import jakarta.annotation.PreDestroy;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;

/**
 * Starts an H2 TCP Server when the 'h2' profile is active, allowing external tools
 * such as IntelliJ IDEA's Database tool window to connect directly to the in-memory
 * database (jdbc:h2:tcp://localhost:9092/mem:unipay).
 */
@Configuration
@Profile("h2")
public class H2TcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServerConfig.class);
    private Server server;

    @Bean
    public Server h2TcpServer() {
        try {
            this.server = Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", "9092", "-ifNotExists").start();
            log.info("H2 TCP Server running on port 9092. External DB tools can connect to: jdbc:h2:tcp://localhost:9092/mem:unipay");
            return this.server;
        } catch (SQLException e) {
            log.warn("H2 TCP Server could not start on port 9092 (it may already be running): {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void stopH2TcpServer() {
        if (server != null && server.isRunning(false)) {
            server.stop();
            log.info("H2 TCP Server stopped.");
        }
    }
}
