#!/usr/bin/env node

module.exports = function (context) {
  var path = require('path'),
    fs = require('fs'),
    projectRoot = context.opts.projectRoot,
    plugins = context.opts.plugins || [];


  // The plugins array will be empty during platform add
  if (plugins.length > 0 && plugins.indexOf('com.heytz.pushService') === -1) {
    return;
  }
  var ConfigParser = null;
  try {
    ConfigParser = context.requireCordovaModule('cordova-common').ConfigParser;
  } catch (e) {
    // fallback
    ConfigParser = context.requireCordovaModule('cordova-lib/src/configparser/ConfigParser');
  }

  var config = new ConfigParser(path.join(context.opts.projectRoot, "config.xml")),
    packageName = config.android_packageName() || config.packageName();

  // replace dash (-) with underscore (_)
  packageName = packageName.replace(/-/g, "_");

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
  var targetDir = path.join(projectRoot, "platforms", "android", "src", "heytz", "pushService");
  if (!fs.existsSync(targetDir)) {
    targetDir = path.join(projectRoot, "platforms", "android", "app", "src", "main", "java", "heytz", "pushService");
  }

  var targetFiles = ["Service.java"];
  console.log("com.heytz.pushService targetDir:", targetDir, "packageName:", packageName);

  if (['after_plugin_add', 'after_plugin_install'].indexOf(context.hook) === -1) {
    // remove it?
    targetFiles.forEach(function (f) {
      try {
        fs.unlinkSync(path.join(targetDir, f));
      } catch (err) { }
    });
  } else {
    // 递归创建目录 同步方法
    function mkdirsSync(dirname) {
      if (fs.existsSync(dirname)) {
        return true;
      } else {
        if (mkdirsSync(path.dirname(dirname))) {
          fs.mkdirSync(dirname);
          return true;
        }
      }
    }
    const mkdirsSyncResult = mkdirsSync(targetDir);
    console.log('create directory ', mkdirsSyncResult)
    console.log('targetFiles', targetFiles);
    // sync the content
    targetFiles.forEach(function (f) {
      var fileFullPath = path.join(context.opts.plugin.dir, 'src', 'android', f)
      var replaceFileFullPath = path.join(targetDir, f)
      console.log('fileFullPath', fileFullPath);
      console.log('replaceFileFullPath',replaceFileFullPath);
      fs.readFile(fileFullPath, { encoding: 'utf-8' }, function (err, data) {
        if (err) {
          throw err;
        }
        data = data.replace(/_____PACKAGE_NAME_____/ig, packageName);
        fs.writeFileSync(replaceFileFullPath, data);
      });
    });
  }
};
