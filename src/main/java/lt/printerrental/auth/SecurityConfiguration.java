package lt.printerrental.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.security.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SecurityConfiguration {
  @Bean
  SigningKeyProvider signingKeys(
      @Value("${NEON_AUTH_JWKS_URL}") String jwks, java.time.Clock clock) {
    var http =
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    return new RemoteJwksSigningKeyProvider(java.net.URI.create(jwks), http, clock);
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${NEON_AUTH_BASE_URL}") String base, SigningKeyProvider keys) {
    return new NeonJwtDecoder(base, keys);
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      JwtDecoder decoder,
      UserJwtAuthenticationConverter converter,
      @Value("${AUTH_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}") String origins)
      throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isBlank()).toList());
    cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Serverless-Authorization"));
    cors.setExposedHeaders(List.of("Location", "Content-Disposition"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return http.cors(c -> c.configurationSource(source))
        .csrf(c -> c.disable()) // Only explicit Bearer tokens authenticate this stateless API; no
        // session cookies.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> {
              a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
              a.requestMatchers("/hello", "/health").permitAll();
              a.requestMatchers("/api/rental-requests/locations", "/api/rental-requests/locations/**")
                  .authenticated();
              a.requestMatchers("/api/repair-requests", "/api/repair-requests/**")
                  .authenticated()
                  .requestMatchers(HttpMethod.POST, "/api/rental-requests")
                  .authenticated()
                  .requestMatchers(
                      HttpMethod.GET,
                      "/api/rental-requests",
                      "/api/rental-requests/*",
                      "/api/contracts",
                      "/api/contracts/*",
                      "/api/contracts/*/accounting")
                  .authenticated()
                  .requestMatchers(
                      "/api/printers",
                      "/api/printers/**",
                      "/api/rental-requests/**",
                      "/api/contracts/**")
                  .hasAnyRole("ADMIN", "EMPLOYEE")
                  .anyRequest()
                  .denyAll();
            })
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
        .build();
  }
}
