/** @type {import('next').NextConfig} */
const nextConfig = {
  // Pin the workspace root so Next doesn't infer a parent lockfile (this dir, resolved at runtime).
  turbopack: { root: __dirname },
};

module.exports = nextConfig;
