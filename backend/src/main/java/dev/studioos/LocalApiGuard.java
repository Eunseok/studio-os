package dev.studioos;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalApiGuard extends OncePerRequestFilter {
  private final Set<String> origins;

  public LocalApiGuard(@Value("${studio.allowed-origins}") String origins) {
    this.origins = Set.of(origins.split(","));
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String host = req.getServerName();
    String origin = req.getHeader("Origin");
    boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
    if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(host)
        || (origin != null && !origins.contains(origin))
        || (mutation && !"1".equals(req.getHeader("X-Studio-Client")))) {
      res.sendError(403, "Local Studio client required");
      return;
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Cache-Control", "no-store");
    chain.doFilter(req, res);
  }
}
