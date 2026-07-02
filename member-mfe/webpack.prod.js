/**
 * webpack.prod.js — Production build configuration for the `memberMfe` Module Federation remote.
 *
 * Consumed by `npm run build` ("webpack --config webpack.prod.js"). It reuses the shared/common
 * configuration and layers on production-only settings. Everything specific to the remote — the
 * Module Federation setup (remote name, `remoteEntry.js` filename, the exposed `./MemberApp`
 * module, and the shared React singletons), the output public path / unique name, the TypeScript
 * loader rule, and the HTML/Define plugins — lives ONCE in `webpack.common.js` and is inherited
 * here. This file deliberately re-declares NONE of it.
 *
 * Composition: the common configuration is a factory `(env, argv) => config`. We invoke it and
 * spread the result via a plain object spread (not a merge helper library), then add the
 * production overrides:
 *   - `mode: 'production'`   enables Webpack's built-in production optimizations (minification,
 *                            tree-shaking, deterministic ids, `NODE_ENV=production`).
 *   - `devtool: 'source-map'` emits full external source maps — safe for Module Federation and
 *                            keeps production stack traces debuggable.
 *
 * IMPORTANT: leave `optimization` at Webpack's production defaults. Custom runtime/split-chunk
 * overrides can relocate or fragment the Module Federation runtime and shared-scope
 * initialization, breaking the remote entry / shared-singleton negotiation. Only `mode` and
 * `devtool` are added below.
 *
 * With the full `src/**` present, the build emits `dist/remoteEntry.js` (the MF manifest the host
 * harness loads) and `dist/index.html` (from the common HTML plugin).
 *
 * @param {Record<string, unknown>|undefined} env  Webpack `--env` values, passed through to common.
 * @param {Record<string, unknown>|undefined} argv Webpack CLI argv, passed through to common.
 * @returns {import('webpack').Configuration} The fully composed production configuration.
 */
const commonFactory = require('./webpack.common.js');

module.exports = (env, argv) => ({
  ...commonFactory(env, argv),
  mode: 'production',
  devtool: 'source-map',
});
