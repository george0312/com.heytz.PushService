#!/usr/bin/env node

module.exports = function (context) {
  var path = context.requireCordovaModule('path'),
    fs = context.requireCordovaModule('fs'),
    shell = context.requireCordovaModule('shelljs'),
    projectRoot = context.opts.projectRoot,
    ConfigParser = context.requireCordovaModule('cordova-common').ConfigParser,
    // ConfigParser = context.requireCordovaModule('cordova-lib/src/configparser/ConfigParser'),
    config = new ConfigParser(path.join(context.opts.projectRoot, "config.xml")),
    packageName = config.android_packageName() || config.packageName();

  console.info("Running android-install.Hook: " + context.hook + ", Package: " + packageName + ", Path: " + projectRoot + ".");

  if (!packageName) {
    console.error("Package name could not be found!");
    return;
  }

  // android platform available?
  if (context.opts.cordova.platforms.indexOf("android") === -1) {
    console.info("Android platform has not been added.");
    return;
  }

  var targetDir = path.join(projectRoot, "platforms", "android", "src", "com", "heytz", "pushService"),
  targetFile = path.join(targetDir, "Service.java");

  console.log(targetDir);

  if (['after_plugin_add', 'after_plugin_install', 'after_platform_add'].indexOf(context.hook) === -1) {
    try {
      fs.unlinkSync(targetFile);
    } catch (err) {}
  } else {
    // create directory
    shell.mkdir('-p', targetDir);

    // sync the content
    fs.readFile(path.join(context.opts.plugin.dir, 'src', 'android', 'Service.java'), { encoding: 'utf-8' }, function (err, data) {
      if (err) {
        throw err;
      }
      data = data.replace(/_____PACKAGE_NAME_____/ig, packageName);
      fs.writeFileSync(targetFile, data);
    });
  }
};
