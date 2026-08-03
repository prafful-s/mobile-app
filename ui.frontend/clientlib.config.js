const path = require('path');

const BUILD_DIR = path.join(__dirname, 'dist');
const CLIENTLIB_DIR = path.join(
    __dirname, '..', 'ui.apps', 'src', 'main', 'content', 'jcr_root', 'apps', 'contentbridge', 'clientlibs'
);

module.exports = {
    context: BUILD_DIR,
    clientLibRoot: CLIENTLIB_DIR,
    libs: [
        {
            name: 'clientlib-site',
            categories: ['contentbridge.site'],
            allowProxy: true,
            serializationFormat: 'xml',
            jsProcessor: ['default:none', 'min:none'],
            cssProcessor: ['default:none', 'min:none'],
            assets: {
                js: [{ cwd: 'clientlib-site/js', src: '**/*.js', dest: '.' }],
                css: [{ cwd: 'clientlib-site/css', src: '**/*.css', dest: '.' }]
            }
        }
    ]
};
