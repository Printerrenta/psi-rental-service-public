package lt.printerrental.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lt.printerrental.transport.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class HttpCustomerDirectory implements CustomerDirectory {
  private final String url;
  private final ServiceHttpClient client;
  private final ObjectMapper mapper;
  public HttpCustomerDirectory(@Value("${IDENTITY_SERVICE_URL:}") String url,
      ServiceHttpClient client, ObjectMapper mapper) { this.url=url; this.client=client; this.mapper=mapper; }
  public AppUser get(String subject) {
    var auth=SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt) || url.isBlank()) throw new UpstreamUnavailableException();
    var path="/api/auth/customers/"+URLEncoder.encode(subject,StandardCharsets.UTF_8).replace("+","%20");
    var response=client.send(url,path,"GET",null,null,"Bearer "+jwt.getToken().getTokenValue());
    if (response.statusCode()==404) throw new IllegalArgumentException("Kliento paskyra nerasta.");
    if (response.statusCode()!=200) throw new UpstreamUnavailableException();
    AppUser customer;
    try { customer=mapper.readValue(response.body(),AppUser.class); }
    catch (Exception e) { throw new UpstreamUnavailableException(); }
    if (!subject.equals(customer.subject()) || customer.role()!=UserRole.CUSTOMER || customer.email()==null)
      throw new IllegalArgumentException("Pasirinkta paskyra nėra klientas.");
    return customer;
  }
}
