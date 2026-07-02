const path = require('path');
const webpack = require('webpack');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const { ModuleFederationPlugin } = webpack.container;

// MFE_PORT is the origin of the remote's remoteEntry.js (default 5001).
const MFE_PORT = process.env.MFE_PORT || '5001';
// HOST_PORT is this dev-only host's own port; it MUST differ from MFE_PORT so
// that loading the remote exercises a true cross-origin Module Federation fetch.
const HOST_PORT = process.env.HOST_PORT || '5000';

module.exports = {
  entry: path.resolve(__dirname, 'src/index.tsx'),
  mode: 'development',
  devtool: 'eval-source-map',
  output: {
    publicPath: 'auto',
    clean: true,
  },
  resolve: {
    extensions: ['.tsx', '.ts', '.jsx', '.js'],
  },
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        // transpileOnly:true skips type-checking so the bare-specifier remote
        // import ('memberMfe/MemberApp'), which tsc cannot resolve, compiles fine.
        use: { loader: 'ts-loader', options: { transpileOnly: true } },
        exclude: /node_modules/,
      },
    ],
  },
  devServer: {
    port: Number(HOST_PORT),
    historyApiFallback: true,
    hot: false,
  },
  plugins: [
    new ModuleFederationPlugin({
      name: 'hostHarness',
      remotes: {
        // Resolve the remote at runtime from the dev server's origin.
        memberMfe: `memberMfe@http://localhost:${MFE_PORT}/remoteEntry.js`,
      },
      shared: {
        // MUST match the remote's singleton declarations so a single React
        // instance is shared between host and remote.
        react: { singleton: true },
        'react-dom': { singleton: true },
      },
    }),
    new HtmlWebpackPlugin({
      template: path.resolve(__dirname, 'public/index.html'),
    }),
  ],
};
