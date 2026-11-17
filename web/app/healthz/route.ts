// Probe target for the web Deployment. It must not call the API: web stays ready when api is down.
export function GET() {
  return new Response("ok", { headers: { "content-type": "text/plain" } });
}
