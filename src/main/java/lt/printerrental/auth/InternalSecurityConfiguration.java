package lt.printerrental.auth;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

@Configuration
public class InternalSecurityConfiguration {
  @Bean
  @Order(0)
  SecurityFilterChain internalSecurity(
      HttpSecurity http,
      @Value("${INTERNAL_AUTH_MODE:deny}") String mode,
      @Value("${INTERNAL_AUDIENCE:}") String audience,
      @Value("${INTERNAL_CALLER_EMAILS:}") String emails,
      @Value("${PUBSUB_EMULATOR_HOST:}") String emulator)
      throws Exception {
    http.securityMatcher("/internal/**")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (mode.equals("deny")) {
      http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
    } else if (mode.equals("emulator")) {
      if (System.getenv("K_SERVICE") != null || emulator.isBlank())
        throw new IllegalStateException(
            "Unauthenticated internal endpoints require a local Pub/Sub emulator");
      http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
    } else if (mode.equals("google")) {
      var allowed =
          Arrays.stream(emails.split(","))
              .map(String::strip)
              .filter(s -> !s.isBlank())
              .collect(Collectors.toUnmodifiableSet());
      if (audience.isBlank() || allowed.isEmpty())
        throw new IllegalStateException("Internal Google audience and callers are required");
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(Duration.ofSeconds(5));
      requestFactory.setReadTimeout(Duration.ofSeconds(10));
      var decoder =
          NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
              .restOperations(new RestTemplate(requestFactory))
              .build();
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),
              jwt -> {
                var tokenAudience = jwt.getAudience();
                var email = jwt.getClaimAsString("email");
                boolean valid =
                    tokenAudience != null
                        && tokenAudience.contains(audience)
                        && email != null
                        && allowed.contains(email)
                        && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
                return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
              }));
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
    } else {
      throw new IllegalStateException("Unsupported INTERNAL_AUTH_MODE: " + mode);
    }
    return http.build();
  }
}
