/**
 * Module Federation entry point — ASYNC BOUNDARY.
 *
 * This file must contain nothing but a dynamic import of `./bootstrap`.
 * The dynamic `import()` defers all React/application code so Webpack's
 * Module Federation runtime can negotiate the shared singletons (`react`,
 * `react-dom`) BEFORE any React code executes. Omitting this boundary yields
 * the runtime error: "Shared module is not available for eager consumption".
 */
import('./bootstrap');

export {};
