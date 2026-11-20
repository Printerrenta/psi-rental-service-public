package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.printerrental.transport.ServiceHttpClient;
import lt.printerrental.transport.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;

class HttpUserIdentityResolverTest {
  @Test
  void translatesUpstreamUnavailableToAuthenticationServiceException() {
    var client = mock(ServiceHttpClient.class);
    when(client.send(anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenThrow(new UpstreamUnavailableException());
    var resolver =
        new HttpUserIdentityResolver("http://identity.test", client, new ObjectMapper());
    var jwt = Jwt.withTokenValue("token").header("alg", "EdDSA").subject("alice").build();
    assertThatThrownBy(() -> resolver.resolve(jwt))
        .isInstanceOf(AuthenticationServiceException.class)
        .hasCauseInstanceOf(UpstreamUnavailableException.class);
  }
}
