const isAndroid = () => {
  const userAgent = navigator.userAgent || navigator.vendor || window.opera;
  if (/android/i.test(userAgent)) return true;
  return false;
};

const isIos = () => {
  const userAgent = navigator.userAgent || navigator.vendor || window.opera;
  if (/iPad|iPhone|iPod/i.test(userAgent)) return true;
  return false;
};

const isMobile = () => window.cordova && (isAndroid() || isIos());

const getBrowserCamera = () => navigator.mediaDevices
  .getUserMedia({
    audio: false,
    video: {
      facingMode: 'user',
      width: 224,
      height: 224,
    },
  });

const readImageFile = (data, callback) => {
  // set file protocol
  const protocol = 'file://';
  let filepath = '';
  if (isAndroid()) {
    filepath = protocol + data.output.images.fullsize.file;
  } else {
    filepath = data.output.images.fullsize.file;
  }
  // read image from local file and assign to image element
  window.resolveLocalFileSystemURL(
    filepath,
    async (fileEntry) => {
      fileEntry.file(
        (file) => {
          const reader = new FileReader();
          reader.onloadend = async () => {
            const blob = new Blob([new Uint8Array(reader.result)], {
              type: 'image/png',
            });
            callback(window.URL.createObjectURL(blob));
          };
          reader.readAsArrayBuffer(file);
        },
        (err) => {
          console.log('read', err);
        },
      );
    },
    (error) => {
      console.log(error);
    },
  );
};

const getMobileCamera = async (callback) => {
  window.plugin.CanvasCamera.start(
    {
      canvas: {
        width: 224,
        height: 224,
      },
      capture: {
        width: 224,
        height: 224,
      },
      use: 'file',
      fps: 30,
      hasThumbnail: false,
      cameraFacing: 'front',
    },
    async (err) => {
      console.log('Something went wrong!', err);
    },
    async (stream) => readImageFile(stream, callback),
  );
};

const hybridFunctions = {
  isAndroid,
  isIos,
  isMobile,
  getBrowserCamera,
  getMobileCamera,
};

export default hybridFunctions;
