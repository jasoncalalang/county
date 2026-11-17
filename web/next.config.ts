import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The image runs `node server.js` from the standalone output.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
};

export default nextConfig;
