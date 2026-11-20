package lt.printerrental.transport;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ServiceHttpClient {
  private static final Logger LOG = LoggerFactory.getLogger(ServiceHttpClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private final ServiceCredentials credentials;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public ServiceHttpClient(ServiceCredentials credentials) {
    this.credentials = credentials;
  }

  public HttpResponse<byte[]> send(
      String serviceUrl,
      String path,
      String method,
      byte[] body,
      String contentType,
      String bearer) {
    String baseUrl = serviceUrl == null ? "" : normalizeBaseUrl(serviceUrl);
    String safePath = stripQuery(path);
    try {
      String normalizedPath = path.startsWith("/") ? path : "/" + path;
      var request =
          HttpRequest.newBuilder(URI.create(baseUrl + normalizedPath)).timeout(REQUEST_TIMEOUT);
      if (contentType != null) request.header("Content-Type", contentType);
      if (bearer != null) request.header("Authorization", bearer);
      var cloud = credentials.identityToken(baseUrl);
      if (cloud != null) request.header("X-Serverless-Authorization", "Bearer " + cloud);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn("Upstream HTTP {} {}{} interrupted", method, baseUrl, safePath, error);
      throw new UpstreamUnavailableException();
    } catch (Exception error) {
      LOG.warn(
          "Upstream HTTP {} {}{} failed: {}",
          method,
          baseUrl,
          safePath,
          error.getMessage(),
          error);
      throw new UpstreamUnavailableException();
    }
  }

  private static String normalizeBaseUrl(String serviceUrl) {
    int end = serviceUrl.length();
    while (end > 0 && serviceUrl.charAt(end - 1) == '/') {
      end--;
    }
    return serviceUrl.substring(0, end);
  }

  private static String stripQuery(String path) {
    if (path == null) return "";
    int query = path.indexOf('?');
    return query >= 0 ? path.substring(0, query) : path;
  }
}
