package ph.com.ridgewell.county.api;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The verified Google subject of an owner's request. */
final class Subjects {
  private Subjects() {}

  static String of(SecurityIdentity identity) {
    String s = identity.getPrincipal() instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    if (s == null || s.isBlank()) throw new Problem(401, "Sign in required");
    return s;
  }
}
