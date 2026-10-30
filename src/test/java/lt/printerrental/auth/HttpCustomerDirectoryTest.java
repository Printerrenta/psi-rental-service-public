package lt.printerrental.auth;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import lt.printerrental.transport.*;

class HttpCustomerDirectoryTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test @SuppressWarnings("unchecked") void forwardsStaffJwtAndAcceptsOnlyTheRequestedCustomer() {
    var jwt=Jwt.withTokenValue("staff-jwt").header("alg","RS256").subject("employee").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    var client=mock(ServiceHttpClient.class);
    var response=(HttpResponse<byte[]>)mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"subject\":\"alice\",\"email\":\"alice@example.com\",\"name\":\"Alice\",\"role\":\"CUSTOMER\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(client.send("https://identity.test","/api/auth/customers/alice","GET",null,null,"Bearer staff-jwt")).thenReturn(response);
    var directory=new HttpCustomerDirectory("https://identity.test",client,new ObjectMapper());
    assertThat(directory.get("alice").subject()).isEqualTo("alice");
    when(response.body()).thenReturn("{\"subject\":\"other\",\"email\":\"other@example.com\",\"name\":\"Other\",\"role\":\"CUSTOMER\"}".getBytes());
    assertThatThrownBy(() -> directory.get("alice")).isInstanceOf(IllegalArgumentException.class);
    when(response.statusCode()).thenReturn(404);
    assertThatThrownBy(() -> directory.get("alice")).isInstanceOf(IllegalArgumentException.class);
    when(response.statusCode()).thenReturn(503);
    assertThatThrownBy(() -> directory.get("alice")).isInstanceOf(UpstreamUnavailableException.class);
  }
  @Test void noAuthenticatedStaffTokenFailsClosed() {
    var client=mock(ServiceHttpClient.class);
    assertThatThrownBy(() -> new HttpCustomerDirectory("https://identity.test",client,new ObjectMapper()).get("alice"))
        .isInstanceOf(UpstreamUnavailableException.class);
    verifyNoInteractions(client);
  }
}
