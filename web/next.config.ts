import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The image runs `node server.js` from the standalone output.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  // In the cluster the Ingress sends /api to county-api and web never sees it (ADR 0010).
  // Locally there is no Ingress, so COUNTY_DEV_API_ORIGIN lets the dev and e2e servers stand in.
  async rewrites() {
    const origin = process.env.COUNTY_DEV_API_ORIGIN;
    return origin ? [{ source: "/api/:path*", destination: `${origin}/api/:path*` }] : [];
  },
};

export default nextConfig;
