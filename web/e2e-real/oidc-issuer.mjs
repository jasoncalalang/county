// A local OpenID Connect issuer for the real-stack tests. It stands in for Google: the API
// verifies ID tokens against this issuer's discovery document and JWKS, exactly as it verifies
// Google's, so signature, issuer, audience, expiry and subject-to-tenant resolution all run.
// Keys are generated fresh on every start and never leave localhost.
// POST /mint {sub, name?, email?} returns a signed ID token. It is a test-only endpoint.
import { createServer } from "node:http";
import { createPrivateKey, createSign, generateKeyPairSync } from "node:crypto";

const port = Number(process.env.OIDC_PORT ?? 3298);
const issuer = `http://127.0.0.1:${port}`;
const audience = process.env.OIDC_AUDIENCE ?? "county-e2e-real";
const kid = "county-e2e-real";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid, use: "sig", alg: "RS256" };
const signer = createPrivateKey(privateKey.export({ format: "pem", type: "pkcs8" }));

const b64 = (value) => Buffer.from(value).toString("base64url");

function mint({ sub, name, email }) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64(JSON.stringify({ alg: "RS256", typ: "JWT", kid }));
  const payload = b64(
    JSON.stringify({ iss: issuer, aud: audience, sub, name, email, iat: now - 60, exp: now + 3600 }),
  );
  const signature = createSign("SHA256").update(`${header}.${payload}`).sign(signer).toString("base64url");
  return `${header}.${payload}.${signature}`;
}

function send(response, status, body) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
}

createServer(async (request, response) => {
  if (request.url === "/.well-known/openid-configuration") {
    return send(response, 200, {
      issuer,
      jwks_uri: `${issuer}/jwks`,
      authorization_endpoint: `${issuer}/authorize`,
      token_endpoint: `${issuer}/token`,
      response_types_supported: ["code"],
      subject_types_supported: ["public"],
      id_token_signing_alg_values_supported: ["RS256"],
    });
  }
  if (request.url === "/jwks") return send(response, 200, { keys: [jwk] });
  if (request.url === "/mint" && request.method === "POST") {
    let raw = "";
    for await (const chunk of request) raw += chunk;
    const claims = JSON.parse(raw || "{}");
    if (!claims.sub) return send(response, 400, { error: "sub is required" });
    return send(response, 200, { idToken: mint(claims) });
  }
  return send(response, 404, { error: "not found" });
}).listen(port, "127.0.0.1", () => console.log(`oidc issuer on ${issuer}`));
