package app.scholarai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.filter.ForwardedHeaderFilter;

class ProductionProxyTest {
  @Test
  void productionSessionCookieStaysSecureBehindHttpContainerConnection() {
    var request = new MockHttpServletRequest();
    request.setSecure(false);
    request.setServerName("scholarai-java.vercel.app");
    var response = new MockHttpServletResponse();
    new SharedSessionConfig()
        .cookieSerializer()
        .writeCookieValue(new CookieSerializer.CookieValue(request, response, "test-session"));
    String header = response.getHeader("Set-Cookie");
    assertThat(header).contains("Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void forwardedHttpsPreservesSecureLoginRedirect() throws Exception {
    var request = new MockHttpServletRequest();
    request.setScheme("http");
    request.setServerName("scholarai-java.vercel.app");
    request.setServerPort(8080);
    request.setRequestURI("/login");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "scholarai-java.vercel.app");
    request.addHeader("X-Forwarded-Port", "443");
    var response = new MockHttpServletResponse();
    new ForwardedHeaderFilter()
        .doFilter(
            request,
            response,
            (wrapped, reply) -> {
              assertThat(wrapped.isSecure()).isTrue();
              ((jakarta.servlet.http.HttpServletResponse) reply).sendRedirect("/dashboard");
            });
    assertThat(response.getRedirectedUrl())
        .isEqualTo("https://scholarai-java.vercel.app/dashboard");
  }
}
