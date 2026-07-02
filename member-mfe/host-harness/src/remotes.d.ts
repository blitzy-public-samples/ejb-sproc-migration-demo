/**
 * Ambient module declaration for the Module Federation remote consumed by this
 * dev-only host harness.
 *
 * The bare specifier 'memberMfe/MemberApp' is resolved at RUNTIME by Webpack
 * Module Federation (remote key `memberMfe` + exposed module `./MemberApp`, see
 * ../webpack.config.js `remotes`). TypeScript cannot resolve it from disk, so
 * this declaration provides the type of the default-exported React component.
 *
 * This file is OPTIONAL for the build (ts-loader runs with
 * { transpileOnly: true }); it exists purely for editor / type ergonomics.
 */
declare module 'memberMfe/MemberApp' {
  import type { ComponentType } from 'react';
  const MemberApp: ComponentType;
  export default MemberApp;
}
