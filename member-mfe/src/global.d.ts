/**
 * Ambient global declarations for the member-mfe remote.
 *
 * `@types/node` is intentionally NOT a dependency of this project, yet
 * `src/config/env.ts` reads `process.env.MFE_REST_BASE_URL` and
 * `process.env.MFE_PORT`. Webpack's DefinePlugin replaces those exact member
 * expressions with string literals at build time (see webpack.common.js).
 *
 * This minimal ambient declaration exists so the code type-checks under
 * `strict` mode without adding Node type definitions. It declares only the
 * narrow surface the app actually uses.
 */
declare const process: {
  env: Record<string, string | undefined>;
};

export {};
