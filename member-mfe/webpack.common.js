const path = require('path');
const webpack = require('webpack');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const { ModuleFederationPlugin } = webpack.container;
const deps = require('./package.json').dependencies;

const MFE_REST_BASE_URL =
  process.env.MFE_REST_BASE_URL || 'http://localhost:8080/kitchensink/rest';
const MFE_PORT = process.env.MFE_PORT || '5001';

module.exports = (env, argv) => ({
  entry: path.resolve(__dirname, 'src/index.tsx'),
  output: { publicPath: 'auto', uniqueName: 'memberMfe', clean: true },
  resolve: { extensions: ['.tsx', '.ts', '.jsx', '.js'] },
  module: {
    rules: [
      { test: /\.tsx?$/, use: 'ts-loader', exclude: /node_modules/ },
    ],
  },
  plugins: [
    new webpack.DefinePlugin({
      'process.env.MFE_REST_BASE_URL': JSON.stringify(MFE_REST_BASE_URL),
      'process.env.MFE_PORT': JSON.stringify(MFE_PORT),
    }),
    new ModuleFederationPlugin({
      name: 'memberMfe',
      filename: 'remoteEntry.js',
      exposes: { './MemberApp': path.resolve(__dirname, 'src/App') },
      shared: {
        react: { singleton: true, requiredVersion: deps.react },
        'react-dom': { singleton: true, requiredVersion: deps['react-dom'] },
      },
    }),
    new HtmlWebpackPlugin({ template: path.resolve(__dirname, 'public/index.html') }),
  ],
});
