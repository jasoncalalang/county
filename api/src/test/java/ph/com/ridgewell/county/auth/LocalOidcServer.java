package ph.com.ridgewell.county.auth;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Test-only OIDC discovery/JWKS endpoint. Keys are fresh per run and never leave localhost. */
public final class LocalOidcServer implements QuarkusTestResourceLifecycleManager {
  private HttpServer server;
  private KeyPair signingKey;
  private KeyPair wrongKey;
  private String issuer;

  @Override
  public Map<String, String> start() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      signingKey = generator.generateKeyPair();
      wrongKey = generator.generateKeyPair();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      issuer = "http://127.0.0.1:" + server.getAddress().getPort();
      RSAPublicKey key = (RSAPublicKey) signingKey.getPublic();
      String jwks =
          "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"county-test\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
              + unsigned(key.getModulus())
              + "\",\"e\":\""
              + unsigned(key.getPublicExponent())
              + "\"}]}";
      server.createContext(
          "/.well-known/openid-configuration",
          exchange -> {
            String discovery =
                "{\"issuer\":\""
                    + issuer
                    + "\",\"jwks_uri\":\""
                    + issuer
                    + "/jwks\","
                    + "\"authorization_endpoint\":\""
                    + issuer
                    + "/authorize\",\"token_endpoint\":\""
                    + issuer
                    + "/token\","
                    + "\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
            byte[] body = discovery.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.start();
      return Map.of(
          "quarkus.oidc.auth-server-url",
          issuer,
          "quarkus.oidc.discovery-enabled",
          "true",
          "quarkus.oidc.jwks-path",
          issuer + "/jwks",
          "quarkus.oidc.token.issuer",
          issuer,
          "quarkus.oidc.authentication.user-info-required",
          "false",
          "quarkus.oidc.token.principal-claim",
          "sub");
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Cannot start test OIDC server", e);
    }
  }

  @Override
  public void inject(Object testInstance) {
    if (testInstance instanceof OidcVerificationTest test) test.server = this;
  }

  @Override
  public void stop() {
    if (server != null) server.stop(0);
  }

  public String token(
      String tokenIssuer,
      String audience,
      long expiresAt,
      boolean wrongSignature,
      boolean includeSubject) {
    try {
      long now = Instant.now().getEpochSecond();
      String payload =
          "{\"iss\":\""
              + tokenIssuer
              + "\",\"aud\":\""
              + audience
              + "\",\"iat\":"
              + (now - 3600)
              + ",\"exp\":"
              + expiresAt
              + (includeSubject ? ",\"sub\":\"oidc-owner\"" : "")
              + "}";
      String input =
          encode(
                  "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"county-test\"}"
                      .getBytes(StandardCharsets.UTF_8))
              + "."
              + encode(payload.getBytes(StandardCharsets.UTF_8));
      var signature = Signature.getInstance("SHA256withRSA");
      signature.initSign((wrongSignature ? wrongKey : signingKey).getPrivate());
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + encode(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String issuer() {
    return issuer;
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String unsigned(BigInteger number) {
    byte[] bytes = number.toByteArray();
    return encode(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
