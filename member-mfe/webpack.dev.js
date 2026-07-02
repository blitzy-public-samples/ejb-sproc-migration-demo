const path = require('path');
const commonFactory = require('./webpack.common.js');

const MFE_PORT = process.env.MFE_PORT || '5001';

module.exports = (env, argv) => {
  const common = commonFactory(env, argv);
  return {
    ...common,
    mode: 'development',
    devtool: 'eval-source-map',
    devServer: {
      port: Number(MFE_PORT),
      historyApiFallback: true,
      hot: false,
      static: { directory: path.resolve(__dirname, 'public') },
      headers: { 'Access-Control-Allow-Origin': '*' },
    },
  };
};
