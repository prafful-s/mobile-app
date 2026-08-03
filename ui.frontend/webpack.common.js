const path = require('path');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');

const SOURCE_ROOT = path.resolve(__dirname, 'src/main/webpack');

module.exports = {
    entry: {
        site: path.resolve(SOURCE_ROOT, 'site/main.js')
    },
    output: {
        filename: 'clientlib-[name]/js/[name].js',
        path: path.resolve(__dirname, 'dist'),
        clean: true
    },
    module: {
        rules: [
            {
                test: /\.s?css$/,
                use: [MiniCssExtractPlugin.loader, 'css-loader', 'sass-loader']
            }
        ]
    },
    plugins: [
        new MiniCssExtractPlugin({
            filename: 'clientlib-[name]/css/[name].css'
        })
    ],
    resolve: {
        extensions: ['.js', '.scss'],
        modules: [SOURCE_ROOT, 'node_modules']
    }
};
