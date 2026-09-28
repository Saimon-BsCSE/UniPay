package bd.edu.uiu.unipay.bkash;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds all {@code bkash.*} entries from {@code application.yml} into a
 * single typed bean. Override at runtime via environment variables:
 * {@code BKASH_APP_KEY}, {@code BKASH_APP_SECRET}, {@code BKASH_USERNAME},
 * {@code BKASH_PASSWORD}, {@code BKASH_CALLBACK_URL}.
 */
@Configuration
@ConfigurationProperties(prefix = "bkash")
public class BkashProperties {

    private String baseUrl;
    private String appKey;
    private String appSecret;
    private String username;
    private String password;
    private String callbackUrl;

    public String getBaseUrl()                       { return baseUrl; }
    public void   setBaseUrl(String baseUrl)         { this.baseUrl = baseUrl; }

    public String getAppKey()                        { return appKey; }
    public void   setAppKey(String appKey)           { this.appKey = appKey; }

    public String getAppSecret()                     { return appSecret; }
    public void   setAppSecret(String appSecret)     { this.appSecret = appSecret; }

    public String getUsername()                      { return username; }
    public void   setUsername(String username)       { this.username = username; }

    public String getPassword()                      { return password; }
    public void   setPassword(String password)       { this.password = password; }

    public String getCallbackUrl()                   { return callbackUrl; }
    public void   setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank() && !"SANDBOX_APP_KEY".equalsIgnoreCase(appKey)
                && appSecret != null && !appSecret.isBlank() && !"SANDBOX_APP_SECRET".equalsIgnoreCase(appSecret)
                && username != null && !username.isBlank() && !"SANDBOX_USERNAME".equalsIgnoreCase(username)
                && password != null && !password.isBlank() && !"SANDBOX_PASSWORD".equalsIgnoreCase(password);
    }
}
