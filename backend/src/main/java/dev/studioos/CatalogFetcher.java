package dev.studioos;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** User supplied catalog URLs: HTTPS, public hosts, bounded bodies, no redirects or credentials. */
@Component
public class CatalogFetcher {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  static URI validateUrl(String raw) throws Exception {
    if (raw == null || raw.length() > 2048) throw new IllegalArgumentException("HTTPS URL이 필요합니다");
    URI uri = URI.create(raw);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
      throw new IllegalArgumentException("인증 정보 없는 HTTPS 기본 포트 URL만 지원합니다");
    for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
      byte[] b = a.getAddress(); int first = b[0] & 255;
      if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
          || a.isMulticastAddress() || (b.length == 16 && (first & 254) == 252)
          || (b.length == 4 && (first == 0 || first >= 224 || (first == 100 && (b[1]&255)>=64 && (b[1]&255)<=127))))
        throw new IllegalArgumentException("공개 인터넷 호스트만 사용할 수 있습니다");
    }
    return uri;
  }
  public byte[] fetch(String url) throws Exception {
    var request = HttpRequest.newBuilder(validateUrl(url)).timeout(Duration.ofSeconds(12))
        .header("Accept", "application/json").GET().build();
    // Limited subscriber cancels oversized responses while the request timeout bounds the whole body.
    var response = client.send(request, info -> new LimitedBody(2097152));
    if (response.statusCode() != 200) throw new IllegalArgumentException("카탈로그 응답 HTTP " + response.statusCode());
    return response.body();
  }
  private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private final int limit; private java.util.concurrent.Flow.Subscription subscription;
    LimitedBody(int limit) { this.limit = limit; }
    public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription = s; s.request(1); }
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (bytes.size() + buffer.remaining() > limit) {
          subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("응답은 2 MiB 이하만 허용됩니다")); return;
        }
        byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    public void onError(Throwable t) { result.completeExceptionally(t); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
