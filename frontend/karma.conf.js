// Karma configuration for the Schadenflow frontend.
//
// CI (GitHub ubuntu-latest) has Chrome on PATH and leaves CHROME_BIN unset, so
// karma-chrome-launcher finds it normally. On a local machine without a system
// Chrome, we fall back to a Chrome-for-Testing wrapper (adds --no-sandbox for
// WSL) ONLY if it actually exists — so this file never changes CI behaviour.
const fs = require('fs');
const os = require('os');
const path = require('path');

const localChromeWrapper = path.join(os.homedir(), '.cache/chrome-for-testing/chrome-headless.sh');
if (!process.env.CHROME_BIN && fs.existsSync(localChromeWrapper)) {
  process.env.CHROME_BIN = localChromeWrapper;
}

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: {},
      clearContext: false,
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadless'],
    restartOnFileChange: true,
  });
};
