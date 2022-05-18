[![NPM Version][npm-image]][npm-url]
[![NPM Downloads][downloads-image]][downloads-url]
[![Codacy Badge][codacy-image]][codacy-url]

# Cordova CanvasCamera plugin

## Plugin's Purpose
The purpose of the plugin is to capture video to preview camera in a web page's canvas element.
Allows to select front or back camera and to control the flash.

## Working Demo
Having trouble using CanvasCamera Plugin ? [Check our working demo here](https://github.com/VirtuoWorks/CanvasCameraDemo).

## Supported Platforms
- iOS
- Android

## Dependencies
[Cordova][cordova] will check all dependencies and install them if they are missing.

## Installation
The plugin can either be installed into the local development environment or cloud based through [PhoneGap Build][PGB].

### Adding the Plugin to your project
Through the [Command-line Interface][CLI]:

```bash
cordova plugin add https://github.com/VirtuoWorks/CanvasCameraPlugin.git && cordova prepare
```

### Removing the Plugin from your project
Through the [Command-line Interface][CLI]:

```bash
cordova plugin remove com.virtuoworks.cordova-plugin-canvascamera
```

## TypeScript/Angular 2 support
The CanvasCamera plugin type definition has been added to the DefinitelyTyped repository (see commit [here](https://github.com/DefinitelyTyped/DefinitelyTyped/commit/7f7f502db804112161ef06e712275591d8c4a835)) thanks to a benevolent [contributor](https://github.com/VirtuoWorks/CanvasCameraPlugin/issues/8).

If you wish to install the type definition file :

```bash
npm install --save @types/cordova-plugin-canvascamera
```

You can check this [NPM](https://www.npmjs.com/package/@types/cordova-plugin-canvascamera) page for more informations about this type definition.

## Using the plugin (JavaScript)
The plugin creates the object ```window.plugin.CanvasCamera``` with the following methods:

### Plugin initialization
The plugin and its methods are not available before the *deviceready* event has been fired.
Call `initialize` with a reference to the canvas object used to preview the video and a second, optional, reference to a thumbnail canvas.

```javascript
document.addEventListener('deviceready', function () {

    // Call the initialize() function with canvas element reference
    var objCanvas = document.getElementById('canvas');
    window.plugin.CanvasCamera.initialize(objCanvas);
    // window.plugin.CanvasCamera is now available

}, false);
```

### `start`
Start capturing video as images from camera to preview camera on web page.<br>
`capture` callback function will be called with image data (image file url) each time the plugin takes an image for a frame.<br>

```javascript
window.plugin.CanvasCamera.start(options);
```

This function starts a video capturing session, then the plugin takes each frame as a JPEG image and gives its url to web page calling the `capture` callback function with the image url(s).<br>
The `capture` callback function will draw the image inside a canvas element to display the video.


#### Example
```javascript
var options = {
    cameraFacing: 'front',
};
window.plugin.CanvasCamera.start(options);
```
### `flashMode`
Set flash mode for camera.<br>

```javascript
window.plugin.CanvasCamera.flashMode(true);
```

### `cameraPosition`
Change input camera to 'front' or 'back' camera.

```javascript
window.plugin.CanvasCamera.cameraPosition('front');
```

### Options
Optional parameters to customize the settings.

```javascript
{
    width: 352,
    height: 288,
    canvas: {
      width: 352,
      height: 288
    },
    capture: {
      width: 352,
      height: 288
    },
    fps: 30,
    use: 'file',
    flashMode: false,
    thumbnailRatio: 1/6,
    cameraFacing: 'front' // or 'back',
    onBeforeDraw: function(frame){
      // do something before drawing a frame
      // frame.image; // HTMLImageElement
      // frame.element; // HTMLCanvasElement
    },
    onAfterDraw: function(frame){
      // do something after drawing a frame
      // frame.image.src; // file path or base64 data URI
      // frame.element.toDataURL(); // requested base64 data URI
    }
}

```
- `width` : **Number**, optional, default : `352`, width in pixels of the video to capture **and** the output canvas width in pixels.
- `height` : **Number**, optional, default : `288`, height in pixels of the video to capture **and** the output canvas height in pixels.

- `capture.width` : **Number**, optional, default : `352`, width in pixels of the video to capture.
- `capture.height` : **Number**, optional, default : `288`, height in pixels of the video to capture.

- `canvas.width` : **Number**, optional, default : `352`, output canvas width in pixels.
- `canvas.height` : **Number**, optional, default : `288`, output canvas height in pixels.

- `fps` : **Number**, optional, default : `30`, desired number of frames per second.
- `cameraFacing` : **String**, optional, default : `'front'`, `'front'` or `'back'`.
- `flashMode` : **Boolean**, optional, default : `false`, a boolean to set flash mode on/off.
- `thumbnailRatio` : **Number**, optional, default : `1/6`, a ratio used to scale down the thumbnail.

- `use` : **String**, optional, default : `file`, `file` to use files for rendering (lower CPU / higher storage) or `data` to use base64 jpg data for rendering (higher cpu / lower storage).

- `onBeforeDraw` : **Function**, optional, default : `null`, callback executed before a frame has been drawn. `frame` contains the canvas element, the image element, the tracking data, ...
- `onAfterDraw` : **Function**, optional, default : `null`,  callback executed after a frame has been drawn. `frame` contains the canvas element, the image element, the tracking data, ...

## Usage

### Full size video only
```javascript
let fullsizeCanvasElement = document.getElementById('fullsize-canvas');

CanvasCamera.initialize(fullsizeCanvasElement);

let options:CanvasCamera.CanvasCameraOptions = {
    cameraFacing: 'back',
    onAfterDraw: function(frame) {
      // do something with each frame
      // frame.image.src; // file path or base64 data URI
      // frame.element.toDataURL(); // requested base64 data URI
    }
};

CanvasCamera.start(options);
```

### With thumbnail video
```javascript
let fullsizeCanvasElement = document.getElementById('fullsize-canvas');
let thumbnailCanvasElement = document.getElementById('thumbnail-canvas');

CanvasCamera.initialize(fullsizeCanvasElement, thumbnailCanvasElement);

let options:CanvasCamera.CanvasCameraOptions = {
    cameraFacing: 'front',
    fps: 15,
    thumbnailRatio: 1/6,
    onAfterDraw: function(frame) {
      // do something with each frame of the fullsize canvas element only
      // frame.image.src; // file path or base64 data URI
      // frame.element.toDataURL(); // requested base64 data URI
    }
};

CanvasCamera.start(options);
```

## Contributing

1. Fork it
2. Create your feature branch (`git checkout -b my-new-feature`)
3. Commit your changes (`git commit -am 'Added some feature'`)
4. Push to the branch (`git push origin my-new-feature`)
5. Create new Pull Request

## License

This software is released under the [MIT License][mit-license].

[cordova]: https://cordova.apache.org
[PGB]: http://docs.phonegap.com/phonegap-build/
[CLI]: http://cordova.apache.org/docs/en/latest/guide/cli/index.html
[mit-license]: https://opensource.org/licenses/MIT
[npm-image]: https://img.shields.io/npm/v/com.virtuoworks.cordova-plugin-canvascamera.svg
[npm-url]: https://www.npmjs.com/package/com.virtuoworks.cordova-plugin-canvascamera
[downloads-image]: https://img.shields.io/npm/dm/com.virtuoworks.cordova-plugin-canvascamera.svg
[downloads-url]: https://www.npmjs.com/package/com.virtuoworks.cordova-plugin-canvascamera
[codacy-image]: https://api.codacy.com/project/badge/Grade/dcccd741d63d4b0ea51ae3ccb2cd7d89
[codacy-url]: https://www.codacy.com/app/VirtuoWorks/CanvasCameraPlugin?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=VirtuoWorks/CanvasCameraPlugin&amp;utm_campaign=Badge_Grade
