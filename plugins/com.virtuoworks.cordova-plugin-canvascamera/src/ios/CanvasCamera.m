/**
 * CanvasCamera.js
 * PhoneGap iOS and Android Cordova Plugin to capture Camera streaming into an HTML5 Canvas.
 *
 * VirtuoWorks <contact@virtuoworks.com>.
 *
 * MIT License
 */

#import "CanvasCamera.h"

#pragma mark - CanvasCamera Private Constants
static NSString *const TAG                   = @"CanvasCamera";
static BOOL const LOGGING                    = NO;

#pragma mark - CanvasCamera Private Interface

@interface CanvasCamera ()

// Protected Access (inherited from CDVPlugin)
@property (readwrite, assign) BOOL hasPendingOperation;

// Private Access

@property (readwrite, assign) AVCaptureFlashMode flashMode;

@property (readwrite, assign) NSInteger fileId;
@property (readwrite, strong) NSString *appPath;
@property (readwrite, strong) NSArray *fileNames;

@property (readwrite, strong) dispatch_queue_t sessionQueue;

@property (readwrite, nonatomic, strong) AVCaptureDevice *captureDevice;
@property (readwrite, nonatomic, strong) AVCaptureSession *captureSession;
@property (readwrite, nonatomic, strong) AVCaptureDeviceInput *captureDeviceInput;
@property (readwrite, nonatomic, strong) AVCaptureVideoDataOutput *captureVideoDataOutput;

@property (readwrite, nonatomic, strong) NSString *callbackId;

@end

#pragma mark - CanvasCamera Implementation

@implementation CanvasCamera

@synthesize hasPendingOperation;

#pragma mark - CanvasCamera Instance Inherited Methods

- (void)pluginInitialize {
    self.fileId = 0;
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][pluginInitialize] File id initialized to 0...");
    self.fileNames = @[@"fullsize",@"thumbnail"];
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][pluginInitialize] File names initialized to 'fullsize' and 'thumbnail'...");
    self.appPath = [self getAppPath];
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][pluginInitialize] Writable temporary folder for image file caching created ...");
    self.captureSession = [[AVCaptureSession alloc] init];
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][pluginInitialize] Capture session initialized...");
    self.sessionQueue = dispatch_queue_create("canvas_camera_capture_session_queue", DISPATCH_QUEUE_SERIAL);
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][pluginInitialize] Capture session queue created...");
}

- (void)onAppTerminate {
    [self deleteCachedImageFiles];
}

- (void)onMemoryWarning {
    [self deleteCachedImageFiles];
}

- (NSURL*) urlTransformer:(NSURL*)url
{
    NSURL* urlToTransform = url;

    // for backwards compatibility - we check if this property is there
    SEL sel = NSSelectorFromString(@"urlTransformer");
    if ([self.commandDelegate respondsToSelector:sel]) {
        // grab the block from the commandDelegate
        NSURL* (^urlTransformer)(NSURL*) = ((id(*)(id, SEL))objc_msgSend)(self.commandDelegate, sel);
        // if block is not null, we call it
        if (urlTransformer) {
            urlToTransform = urlTransformer(url);
        }
    }

    return urlToTransform;
}

#pragma mark - CanvasCamera Instance Public Methods

- (void)initDefaultOptions {}

- (void)parseAdditionalOptions:(NSDictionary *) options {}

- (void)addPluginResultDataOutput:(NSMutableDictionary *) output ciImage:(CIImage *) ciImage rotated:(BOOL) rotated {}

- (NSString *)filenameSuffix {
    return [TAG lowercaseString];
}

- (void)startCapture:(CDVInvokedUrlCommand *)command {

    // init parameters - default values
    [self initDefaults];

    // parse options
    @try {
        if ((command.arguments).count > 0) {
            [self parseOptions:(command.arguments)[0]];
        }
    } @catch (NSException *exception) {
        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCapture] Options parsing error : %@", exception.reason);
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_JSON_EXCEPTION messageAsDictionary:[self getPluginResultMessage:exception.reason]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        self.callbackId = nil;
        return;
    }

    self.callbackId = command.callbackId;

    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCapture] Starting async startCapture thread...");

    self.hasPendingOperation = YES;

    __weak CanvasCamera* weakSelf = self;

    [self.commandDelegate runInBackground:^{
        if([weakSelf startCamera]) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCapture] Capture started !");
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
            [pluginResult setKeepCallbackAsBool:YES];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            weakSelf.hasPendingOperation = NO;
        } else {
            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCapture] Unable to start capture.");
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:[weakSelf getPluginResultMessage:@"Unable to start capture."]];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:weakSelf.callbackId];
            weakSelf.hasPendingOperation = NO;
            weakSelf.callbackId = nil;
        }
    }];

}

- (void)stopCapture:(CDVInvokedUrlCommand *)command {

    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCapture] Starting async stopCapture thread...");

    self.hasPendingOperation = YES;

    __weak CanvasCamera* weakSelf = self;

    [self.commandDelegate runInBackground:^{
        CDVPluginResult *pluginResult = nil;
        @try {
            [weakSelf stopCamera];
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCapture] Capture stopped.");
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[weakSelf getPluginResultMessage:@"Capture stopped."]];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            weakSelf.hasPendingOperation = NO;
        } @catch (NSException *exception) {
            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][stopCapture] Could not stop capture : %@", exception.reason);
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:[weakSelf getPluginResultMessage:exception.reason]];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            weakSelf.hasPendingOperation = NO;
        }
    }];
}

- (void)flashMode:(CDVInvokedUrlCommand *)command {

    // parse options
    @try {
        if ((command.arguments).count > 0) {
            self.flashMode = [(command.arguments)[0] boolValue];
        }
    } @catch (NSException *exception) {
        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][flashMode] Options parsing error : %@", exception.reason);
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_JSON_EXCEPTION messageAsDictionary:[self getPluginResultMessage:exception.reason]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCapture] Starting async flashMode thread...");

    self.hasPendingOperation = YES;

    __weak CanvasCamera* weakSelf = self;

    [self.commandDelegate runInBackground:^{
        CDVPluginResult *pluginResult = nil;
        if (weakSelf.isPreviewing && weakSelf.captureDevice) {
            if([self initOptimalFlashMode:weakSelf.captureDevice flashMode:weakSelf.flashMode]) {
                if (self.callbackId) {
                    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][flashMode] Flash mode applied !");
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[weakSelf getPluginResultMessage:@"OK"]];
                    [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                    weakSelf.hasPendingOperation = NO;
                } else {
                    if (LOGGING) NSLog(@"[WARNING][CanvasCamera][flashMode] Could not set flash mode. No capture callback available !");
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_INVALID_ACTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Could not set flash mode. No capture callback available !"]];
                    [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                    weakSelf.hasPendingOperation = NO;
                }
            } else {
                if (LOGGING) NSLog(@"[WARNING][CanvasCamera][flashMode] Could not set flash mode. This capture device has no flash or torch !");
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_INVALID_ACTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Could not set flash mode. This capture device has no flash or torch !"]];
                [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                weakSelf.hasPendingOperation = NO;
            }
        } else {
            if (LOGGING) NSLog(@"[WARNING][CanvasCamera][flashMode] Could not set flash mode. No capture device available !");
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Couldn not set flash mode. No capture device available !"]];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            weakSelf.hasPendingOperation = NO;
        }
    }];
}

- (void)cameraPosition:(CDVInvokedUrlCommand *)command {

    // parse options
    @try {
        if ((command.arguments).count > 0) {
            self.devicePosition = [self devicePosition:(command.arguments)[0]];
        }
    } @catch (NSException *exception) {
        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][cameraPosition] Options parsing error : %@", exception.reason);
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_JSON_EXCEPTION messageAsDictionary:[self getPluginResultMessage:exception.reason]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][cameraPosition] Starting async cameraPosition thread...");

    self.hasPendingOperation = YES;

    __weak CanvasCamera* weakSelf = self;

    [self.commandDelegate runInBackground:^{
        CDVPluginResult *pluginResult = nil;
        if (weakSelf.isPreviewing && weakSelf.captureDevice) {
            if ([weakSelf startCamera]) {
                if (weakSelf.callbackId) {
                    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][cameraPosition] Camera switched !");
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[weakSelf getPluginResultMessage:@"OK"]];
                    [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                    weakSelf.hasPendingOperation = NO;
                } else {
                    if (LOGGING) NSLog(@"[ERROR][CanvasCamera][cameraPosition] Could not switch position. No capture callback available !");
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Could not switch position. No capture callback available !"]];
                    [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                    weakSelf.hasPendingOperation = NO;
                }
            } else {
                if (LOGGING) NSLog(@"[ERROR][CanvasCamera][cameraPosition] Could not switch position. Could not restart camera !");
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Could not switch position. Could not restart camera !"]];
                [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                weakSelf.hasPendingOperation = NO;
            }
        } else {
            if (LOGGING) NSLog(@"[WARNING][CanvasCamera][cameraPosition] Could not switch position. No capture device available !");
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsDictionary:[weakSelf getPluginResultMessage:@"Could not switch position. No capture device available !"]];
            [weakSelf.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            weakSelf.hasPendingOperation = NO;
        }
    }];
}

#pragma mark - Canvas Camera Instance Private Methods

- (BOOL)startCamera {
    [self stopCamera];

    self.captureDevice = [self getDeviceWithPosition:self.devicePosition];

    if (self.captureDevice) {
        NSError *error = nil;
        self.captureDeviceInput = [AVCaptureDeviceInput deviceInputWithDevice:self.captureDevice error:&error];
        if (self.captureDeviceInput) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCamera] Capture device input initialized.");

            if (!self.captureSession) {
                self.captureSession = [[AVCaptureSession alloc] init];
            }

            [self.captureSession beginConfiguration];

            [self initSessionParameters:self.captureSession];

            if ([self.captureSession canAddInput:self.captureDeviceInput]) {
                [self.captureSession addInput:self.captureDeviceInput];
                if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCamera] Capture device input added.");
            } else {
                [self.captureSession commitConfiguration];
                if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCamera] Could not add capture device input");
                [self stopCamera];
                return NO;
            }

            self.captureVideoDataOutput = [[AVCaptureVideoDataOutput alloc] init];

            self.captureVideoDataOutput.videoSettings = [NSDictionary dictionaryWithObject:[NSNumber numberWithInt:kCVPixelFormatType_32BGRA] forKey:(id)kCVPixelBufferPixelFormatTypeKey];

            [self.captureVideoDataOutput setAlwaysDiscardsLateVideoFrames:YES];

            if (!self.sessionQueue) {
                self.sessionQueue = dispatch_queue_create("canvas_camera_capture_session_queue", DISPATCH_QUEUE_SERIAL);
            }

            [self.captureVideoDataOutput setSampleBufferDelegate:(id)self queue:self.sessionQueue];

            if ([self.captureSession canAddOutput:self.captureVideoDataOutput]) {
                [self.captureSession addOutput:self.captureVideoDataOutput];
                if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCamera] Capture video data output added.");
            } else {
                [self.captureSession commitConfiguration];
                if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCamera] Could not add capture video data output.");
                [self stopCamera];
                return NO;
            }

            [self.captureSession commitConfiguration];

            dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);

            dispatch_async(self.sessionQueue, ^{
                self.fileId = 0;
                [self.captureSession startRunning];
                if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][startCamera] Capture session started.");
                self.isPreviewing = YES;
                dispatch_semaphore_signal(semaphore);
            });

            dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);

            return YES;
        } else {
            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCamera] Could not set capture device input : %@", error.localizedDescription);
            return NO;
        }
    } else {
        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][startCamera] Could not set capture device.");
        return NO;
    }
}

- (void)stopCamera {
    if (self.sessionQueue) {
        dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            if (self.captureSession) {
                if ((self.captureSession).running) {
                    [self.captureSession stopRunning];
                    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCamera] Capture session stopped.");
                }
                [self.captureSession beginConfiguration];
                // Remove all inputs
                for(AVCaptureInput *captureInput in self.captureSession.inputs) {
                    [self.captureSession removeInput:captureInput];
                }
                // Remove all outputs
                for(AVCaptureVideoDataOutput *videoDataOutput in self.captureSession.outputs) {
                    [videoDataOutput setSampleBufferDelegate:nil queue:NULL];
                    [self.captureSession removeOutput:videoDataOutput];
                }
                [self.captureSession commitConfiguration];
                if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCamera] Capture session inputs/outputs removed.");
                if (self.sessionQueue) {
                    dispatch_sync(self.sessionQueue, ^{
                        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][stopCamera] Capture session queue flushed.");
                    });
                }
            }
            dispatch_semaphore_signal(semaphore);
        });

        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);
    }
}

- (void)initDefaults {
    self.fps = 30;
    self.width = 352;
    self.height = 288;
    self.canvasWidth = 352;
    self.canvasHeight = 288;
    self.captureWidth = 352;
    self.captureHeight = 288;
    self.hasThumbnail = false;
    self.thumbnailRatio = 1 / 6;
    self.flashMode = AVCaptureFlashModeOff;
    self.devicePosition = AVCaptureDevicePositionBack;
    [self initDefaultOptions];
}

- (void) initSessionParameters:(AVCaptureSession *)captureSession {
    if (self.captureDevice) {
        if ([self initOptimalSessionPreset:self.captureSession captureWidth:self.captureWidth captureHeight:self.captureHeight]) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][initSessionParameters] Capture size is set to width : %ld, height : %ld", (long)self.captureWidth, (long)self.captureHeight);
        }

        if ([self initOptimalFrameRate:self.captureDevice fps:self.fps]) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][initSessionParameters] Capture fps range is set to min : %ld, max : %ld", (long)self.fps, (long)self.fps);
        }

        if ([self initOptimalFlashMode:self.captureDevice flashMode:self.flashMode]) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][initSessionParameters] Capture flash mode is set to : %@", self.flashMode ? @"On" : @"Off");
        }
    }
}

- (AVCaptureDevice *)getDeviceWithPosition:(AVCaptureDevicePosition) position {
    NSArray *devices = [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];
    for (AVCaptureDevice *device in devices) {
        if (device.position == position) {
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][getDeviceWithPosition] Capture device found for position : %@", [self devicePositionToString:position]);
            return device;

        }
    }
    return nil;
}

- (BOOL) initOptimalFlashMode:(AVCaptureDevice *)captureDevice flashMode:(BOOL)flashMode {
    if (captureDevice.hasFlash && captureDevice.hasTorch) {
        NSError *error = nil;
        if([captureDevice lockForConfiguration:&error]) {
            if(flashMode) {
                if ([captureDevice isTorchModeSupported:AVCaptureTorchModeOn]) {
                    captureDevice.torchMode = AVCaptureTorchModeOn;
                }
                if ([captureDevice isFlashModeSupported:AVCaptureFlashModeOn]) {
                    captureDevice.flashMode = AVCaptureFlashModeOn;
                }
            } else {
                if ([captureDevice isTorchModeSupported:AVCaptureTorchModeOff]) {
                    captureDevice.torchMode = AVCaptureTorchModeOff;
                }
                if ([captureDevice isFlashModeSupported:AVCaptureFlashModeOff]) {
                    captureDevice.flashMode = AVCaptureFlashModeOff;
                }
            }
            [self.captureDevice unlockForConfiguration];
            return YES;
        }
        return NO;
    }
    return NO;
}


- (BOOL) initOptimalSessionPreset:(AVCaptureSession *)captureSession captureWidth:(NSInteger)captureWidth captureHeight:(NSInteger)captureHeight {
    if (captureWidth <= 352 && captureHeight <= 288) {
        if([captureSession canSetSessionPreset:AVCaptureSessionPreset352x288]) {
            captureSession.sessionPreset = AVCaptureSessionPreset352x288;
            self.captureWidth = 352;
            self.captureHeight = 288;
            return YES;
        }
    }

    if (captureWidth <= 640 && captureHeight <= 480) {
        if([captureSession canSetSessionPreset:AVCaptureSessionPreset640x480]) {
            captureSession.sessionPreset = AVCaptureSessionPreset640x480;
            self.captureWidth = 640;
            self.captureHeight = 480;
            return YES;
        }
    }

    if (captureWidth <= 1280 && captureHeight <= 720) {
        if([captureSession canSetSessionPreset:AVCaptureSessionPreset1280x720]) {
            captureSession.sessionPreset = AVCaptureSessionPreset1280x720;
            self.captureWidth = 1280;
            self.captureHeight = 720;
            return YES;
        }
    }

    if (captureWidth <= 1920 && captureHeight <= 1080) {
        if([captureSession canSetSessionPreset:AVCaptureSessionPreset1920x1080]) {
            captureSession.sessionPreset = AVCaptureSessionPreset1920x1080;
            self.captureWidth = 1920;
            self.captureHeight = 1080;
            return YES;
        }
    }

    if (captureWidth <= 3480 && captureHeight <= 2160) {
        if([captureSession canSetSessionPreset:AVCaptureSessionPreset3840x2160]) {
            captureSession.sessionPreset = AVCaptureSessionPreset3840x2160;
            self.captureWidth = 3480;
            self.captureHeight = 2160;
            return YES;
        }
    }

    return NO;
}

- (BOOL) initOptimalFrameRate:(AVCaptureDevice *)captureDevice fps:(NSInteger) fps {
    BOOL frameRateSupported = NO;

    CMTime frameDuration = CMTimeMake((int64_t)1, (int32_t)fps);
    NSArray *supportedFrameRateRanges = (captureDevice.activeFormat).videoSupportedFrameRateRanges;

    NSError *error = nil;
    for (AVFrameRateRange *range in supportedFrameRateRanges) {
        if (CMTIME_COMPARE_INLINE(frameDuration, >=, range.minFrameDuration) &&
            CMTIME_COMPARE_INLINE(frameDuration, <=, range.maxFrameDuration)) {
            frameRateSupported = YES;
        }
    }

    if (frameRateSupported && [captureDevice lockForConfiguration:&error]) {
        captureDevice.activeVideoMaxFrameDuration = frameDuration;
        captureDevice.activeVideoMinFrameDuration = frameDuration;
        [captureDevice unlockForConfiguration];
    }

    return frameRateSupported;
}

- (NSDictionary*)getPluginResultMessage:(NSString *)message {
    NSDictionary *output = @{
                             @"images": @{
                                     @"orientation" : [self getCurrentOrientationToString]
                                     }
                             };

    return [self getPluginResultMessage:message pluginOutput:output];
}

- (NSDictionary*)getPluginResultMessage:(NSString *)message pluginOutput:(NSDictionary *)output {

    NSDictionary *canvas = @{
                             @"width" : @(self.canvasWidth),
                             @"height" : @(self.canvasHeight)
                             };

    NSDictionary *capture = @{
                              @"width" : @(self.captureWidth),
                              @"height" : @(self.captureHeight)
                              };

    NSDictionary *options = @{
                              @"width" : @(self.width),
                              @"height" : @(self.height),
                              @"fps" : @(self.fps),
                              @"flashMode" : @([self AVCaptureFlashModeAsBoolean:self.flashMode]),
                              @"cameraFacing" : [self devicePositionToString:self.devicePosition],
                              @"hasThumbnail" : @(self.hasThumbnail),
                              @"thumbnailRatio" : @(self.thumbnailRatio),
                              @"canvas" : canvas,
                              @"capture" : capture
                              };

    NSDictionary *preview = @{
                              @"started" : @(self.isPreviewing)
                              };

    NSDictionary *result = @{
                             @"message" : message,
                             @"options" : options,
                             @"preview" : preview,
                             @"output" : output
                             };

    return result;
}

- (void)parseOptions:(NSDictionary *) options {
    if (![options isKindOfClass:[NSDictionary class]]) {
        return;
    }

    NSString *valueAsString = nil;

    // devicePosition
    valueAsString = options[CCLensOrientationKey];
    if (valueAsString) {
        self.devicePosition = [self devicePosition:valueAsString];
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Capture device position : %@", [self devicePositionToString:self.devicePosition]);
    }

    // use
    valueAsString = options[CCUseKey];
    if (valueAsString) {
        self.use = valueAsString;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Use : %@", self.use);
    }

    // fps
    valueAsString = options[CCFpsKey];
    if (valueAsString) {
        self.fps = valueAsString.integerValue;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Fps : %ld", (long)self.fps);
    }

    // width
    valueAsString = options[CCWidthKey];
    if (valueAsString) {
        self.width = valueAsString.integerValue;
        self.canvasWidth = self.width;
        self.captureWidth = self.width;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Global width : %ld", (long)self.width);
    }

    // height
    valueAsString = options[CCHeightKey];
    if (valueAsString) {
        self.height = valueAsString.integerValue;
        self.canvasHeight = self.height;
        self.captureHeight = self.height;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Global height : %ld", (long)self.height);
    }

    // flashMode
    valueAsString = options[CCFlashModeKey];
    if (valueAsString) {
        self.flashMode = [self AVCaptureFlashMode:valueAsString.boolValue];
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Flash mode : %@", [self AVCaptureFlashModeAsBoolean:self.flashMode] ? @"true" : @"false");
    }

    // hasThumbnail
    valueAsString = options[CCHasThumbnailKey];
    if (valueAsString) {
        self.hasThumbnail = valueAsString.boolValue;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Thumbnail ratio : %@", self.hasThumbnail ? @"true" : @"false");
    }

    // thumbnailRatio
    valueAsString = options[CCThumbnailRatioKey];
    if (valueAsString) {
        self.thumbnailRatio = valueAsString.doubleValue;
        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Thumbnail ratio : %f", self.thumbnailRatio);
    }

    NSDictionary *valueAsDictionnary = nil;

    // canvas
    valueAsDictionnary = options[CCCanvasKey];

    if (valueAsDictionnary) {
        valueAsString = valueAsDictionnary[CCWidthKey];
        if (valueAsString) {
            // canvas.width
            self.canvasWidth = valueAsString.integerValue;
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Canvas width : %ld", (long)self.canvasWidth);
        }
        valueAsString = valueAsDictionnary[CCHeightKey];
        if (valueAsString) {
            // canvas.height
            self.canvasHeight = valueAsString.integerValue;
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Canvas height : %ld", (long)self.canvasHeight);
        }
    }

    // capture
    valueAsDictionnary = options[CCCaptureKey];

    if (valueAsDictionnary) {
        valueAsString = valueAsDictionnary[CCWidthKey];
        if (valueAsString) {
            // capture.width
            self.captureWidth = valueAsString.integerValue;
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Capture width : %ld", (long)self.captureWidth);
        }
        valueAsString = valueAsDictionnary[CCHeightKey];
        if (valueAsString) {
            // capture.height
            self.captureHeight = valueAsString.integerValue;
            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][parseOptions] Capture height : %ld", (long)self.captureHeight);
        }
    }

    // parsing additional options
    [self parseAdditionalOptions:options];
}

- (AVCaptureDevicePosition) devicePosition:(NSString *) option {
    if ([option isEqualToString:@"front"]) {
        return AVCaptureDevicePositionFront;
    } else {
        return AVCaptureDevicePositionBack;
    }
}

- (NSString *) devicePositionToString:(AVCaptureDevicePosition) devicePosition {
    if (devicePosition == AVCaptureDevicePositionFront) {
        return @"front";
    } else {
        return @"back";
    }
}

- (AVCaptureFlashMode) AVCaptureFlashMode:(BOOL)isFlashModeOn {
    if (isFlashModeOn) {
        return AVCaptureFlashModeOn;
    } else {
        return AVCaptureFlashModeOff;
    }
}

- (bool) AVCaptureFlashModeAsBoolean:(AVCaptureFlashMode) flashMode {
    if (flashMode == AVCaptureFlashModeOn) {
        return true;
    } else {
        return false;
    }
}

#pragma mark - Canvas Camera Instance Capture Delegate Method

- (void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection {
    if (self.isPreviewing && self.callbackId) {

        [self setVideoOrientation:connection];

        @autoreleasepool {
            // Getting image files paths
            NSMutableDictionary *files = [self getImageFilesPaths];
            // Get core image from sample buffer
            CIImage *ciImage = [self CIImageFromSampleBuffer:sampleBuffer];
            // Get ui image from core image
            UIImage *uiImage = [self UIImageFromCIImage:ciImage];
            // Has the image been rotated ?
            BOOL rotated = (BOOL)[self getDisplayRotation];
            // Resize the ui image to match target canvas size
            uiImage = [self resizedUIImage:uiImage toSize:CGSizeMake(self.canvasWidth, self.canvasHeight) rotated:rotated];

            // Convert the ui image to JPEG NSData
            NSData *fullsizeData = UIImageJPEGRepresentation(uiImage, 1.0);

            // Same operation for the image thumbnail version
            NSData *thumbnailData = nil;
            if (self.hasThumbnail) {
                thumbnailData = UIImageJPEGRepresentation([self resizedUIImage:uiImage ratio:self.thumbnailRatio], 1.0);
            }

            // Allocating output NSDictionnary
            NSMutableDictionary *images =  [[NSMutableDictionary alloc] init];

            // Populating images NSDictionnary
            images[@"fullsize"] = [[NSMutableDictionary alloc] init];

            NSString *fullImagePath = nil;
            if ([self.use isEqualToString:@"file"]) {
                // Get a file path to save the JPEG as a file
                fullImagePath = [files valueForKey:@"fullsize"];
                if (fullImagePath) {
                    // Write the data to the file
                    if ([fullsizeData writeToFile:fullImagePath atomically:YES]) {
                        if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][captureOutput] Fullsize image file with path [%@] saved.", fullImagePath);
                        fullImagePath = [self urlTransformer:[NSURL fileURLWithPath:fullImagePath]].absoluteString;
                        images[@"fullsize"][@"file"] = (fullImagePath) ? fullImagePath : @"";
                    } else {
                        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][captureOutput] Could not save fullsize image file with path [%@].", fullImagePath);
                        fullImagePath = nil;
                    }

                } else {
                    if (LOGGING) NSLog(@"[ERROR][CanvasCamera][captureOutput] Unable to retrieve path for fullsize image file.");
                    fullImagePath = nil;
                }
            }

            NSString *fullImageDataToB64 = nil;
            if ([self.use isEqualToString:@"data"]) {
                fullImageDataToB64 = [NSString stringWithFormat:@"data:image/jpeg;base64,%@", [fullsizeData base64EncodedStringWithOptions:0]];
                images[@"fullsize"][@"data"] = (fullImageDataToB64) ? fullImageDataToB64 : @"";
            }

            // release fullsizeData
            fullsizeData = nil;

            images[@"fullsize"][@"rotation"] = @([self getDisplayRotation]);
            images[@"fullsize"][@"orientation"] = [self getCurrentOrientationToString];
            images[@"fullsize"][@"timestamp"] = @([NSDate date].timeIntervalSince1970 * 1000);

            fullImagePath = nil;
            fullImageDataToB64 = nil;

            images[@"thumbnail"] = [[NSMutableDictionary alloc] init];

            if (thumbnailData) {
                NSString *thumbImagePath = nil;
                if ([self.use isEqualToString:@"file"]) {
                    thumbImagePath = [files valueForKey:@"thumbnail"];
                    if (thumbImagePath) {
                        // Write the data to the file
                        if ([thumbnailData writeToFile:thumbImagePath atomically:YES]) {
                            if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][captureOutput] Thumbnail image file with path [%@] saved.", thumbImagePath);
                            thumbImagePath = [self urlTransformer:[NSURL fileURLWithPath:thumbImagePath]].absoluteString;
                            images[@"thumbnail"][@"file"] = (thumbImagePath) ? thumbImagePath : @"";
                        } else {
                            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][captureOutput] Could not save thumbnail image file with path [%@].", thumbImagePath);
                            thumbImagePath = nil;
                        }
                    } else {
                        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][captureOutput] Unable to retrieve path for thumbnail image file.");
                        thumbImagePath = nil;
                    }
                }

                NSString *thumbImageDataToB64 = nil;
                if ([self.use isEqualToString:@"data"]) {
                    thumbImageDataToB64 = [NSString stringWithFormat:@"data:image/jpeg;base64,%@", [thumbnailData base64EncodedStringWithOptions:0]];
                    images[@"thumbnail"][@"data"] = (thumbImageDataToB64) ? thumbImageDataToB64 : @"";
                }

                // release thumbnailData
                thumbnailData = nil;

                images[@"thumbnail"][@"rotation"] = @([self getDisplayRotation]);
                images[@"thumbnail"][@"orientation"] = [self getCurrentOrientationToString];
                images[@"thumbnail"][@"timestamp"] = @([NSDate date].timeIntervalSince1970 * 1000);

                thumbImagePath = nil;
                thumbImageDataToB64 = nil;
            }

            // Allocating output NSDictionnary
            NSMutableDictionary *output =  [[NSMutableDictionary alloc] init];

            // Populating output NSDictionnary
            output[@"images"] = images;

            [self addPluginResultDataOutput:output ciImage:ciImage rotated:rotated];

            // release images output dictionnary
            images = nil;

            // release ciImage
            ciImage = nil;

            // release uiImage
            uiImage = nil;

            if (self.isPreviewing && self.callbackId) {
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[self getPluginResultMessage:@"OK" pluginOutput:output]];
                [pluginResult setKeepCallbackAsBool:YES];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
            }
        }
    }
}

- (CIImage *)CIImageFromSampleBuffer:(CMSampleBufferRef)sampleBuffer {

    CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);

    // CFDictionaryRef attachments = CMCopyDictionaryOfAttachments(kCFAllocatorDefault, sampleBuffer, kCMAttachmentMode_ShouldPropagate);
    // CIImage *ciImage = [[CIImage alloc] initWithCVPixelBuffer:pixelBuffer options:(__bridge NSDictionary *)attachments];

    CIImage *ciImage = [[CIImage alloc] initWithCVPixelBuffer:pixelBuffer options: nil];

    return ciImage;
}

- (UIImage *)UIImageFromCIImage:(CIImage *)ciImage {
    UIImage *uiImage;
    if (self.devicePosition == AVCaptureDevicePositionBack) {
        uiImage = [[UIImage alloc] initWithCIImage:ciImage];
    } else {
        uiImage = [[UIImage alloc] initWithCIImage:ciImage scale:1.0 orientation:UIImageOrientationUpMirrored];
    }
    return uiImage;
}

- (UIImage *)UIImageFromSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);

    CVPixelBufferLockBaseAddress(imageBuffer,0);
    uint8_t *baseAddress = (uint8_t *)CVPixelBufferGetBaseAddress(imageBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer);
    size_t width = CVPixelBufferGetWidth(imageBuffer);
    size_t height = CVPixelBufferGetHeight(imageBuffer);

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef newContext = CGBitmapContextCreate(baseAddress, width, height, 8, bytesPerRow, colorSpace, kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst);

    CGImageRef cgImage = CGBitmapContextCreateImage(newContext);

    CGContextRelease(newContext);
    CGColorSpaceRelease(colorSpace);

    UIImage *uiImage = [UIImage imageWithCGImage:cgImage];

    CGImageRelease(cgImage);

    CVPixelBufferUnlockBaseAddress(imageBuffer, 0);

    return uiImage;
}

- (void) setVideoOrientation:(AVCaptureConnection *)connection {
    UIDeviceOrientation deviceOrientation = [UIDevice currentDevice].orientation;

    if (connection.supportsVideoOrientation) {
        switch(deviceOrientation) {
            case UIInterfaceOrientationPortraitUpsideDown:
                connection.videoOrientation = AVCaptureVideoOrientationPortraitUpsideDown;
                break;
            case UIInterfaceOrientationPortrait:
                connection.videoOrientation = AVCaptureVideoOrientationPortrait;
                break;
            case UIInterfaceOrientationLandscapeLeft:
                connection.videoOrientation = AVCaptureVideoOrientationLandscapeLeft;
                break;
            case UIInterfaceOrientationLandscapeRight:
                connection.videoOrientation = AVCaptureVideoOrientationLandscapeRight;
                break;
            default:
                break;
        }
    }
}

- (NSInteger)getDisplayRotation {
    if([[self getCurrentOrientationToString] isEqualToString:@"portrait"]) {
        return 90;
    } else {
        return 0;
    }
}

- (NSString *)getCurrentOrientationToString {

    UIDeviceOrientation deviceOrientation = [UIDevice currentDevice].orientation;

    switch(deviceOrientation) {
        case UIInterfaceOrientationPortraitUpsideDown:
            return @"portrait";
            break;
        case UIInterfaceOrientationPortrait:
            return @"portrait";
            break;
        case UIInterfaceOrientationLandscapeLeft:
            return @"landscape";
            break;
        case UIInterfaceOrientationLandscapeRight:
            return @"landscape";
            break;
        default:
            return @"landscape";
            break;
    }
}

- (NSMutableDictionary *)getImageFilesPaths {
    @synchronized(self) {
        NSMutableDictionary *files =  [[NSMutableDictionary alloc] init];

        if (self.appPath) {
            self.fileId ++;

            for (NSString* fileName in self.fileNames) {
                BOOL deleted;
                NSError *error = nil;
                if (self.fileId > self.fps) {
                    NSString *prevFile = [self.appPath stringByAppendingPathComponent:[NSString stringWithFormat:@"%@%ld%@", [fileName substringToIndex:1], (long)(self.fileId - self.fps), [NSString stringWithFormat:@"-%@.jpg", self.filenameSuffix]]];
                    if ([[NSFileManager defaultManager] fileExistsAtPath:prevFile]) {
                        error = nil;
                        deleted = [[NSFileManager defaultManager] removeItemAtPath:prevFile error:&error];
                        if (error) {
                            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][getImageFilesPaths] Could not delete previous file : %@.", error.localizedDescription);
                        }
                    }
                }

                NSString *curFile = [self.appPath stringByAppendingPathComponent:[NSString stringWithFormat:@"%@%ld%@", [fileName substringToIndex:1], (long)(self.fileId), [NSString stringWithFormat:@"-%@.jpg", self.filenameSuffix]]];
                if ([[NSFileManager defaultManager] fileExistsAtPath:curFile]) {
                    error = nil;
                    deleted = [[NSFileManager defaultManager] removeItemAtPath:curFile error:&error];
                    if (error) {
                        if (LOGGING) NSLog(@"[ERROR][CanvasCamera][getImageFilesPaths] Could not delete current file : %@.", error.localizedDescription);
                    }
                }

                [files setValue:curFile  forKey:fileName];
            }
        }

        return files;
    }
}

- (void) deleteCachedImageFiles {
    if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][deleteCachedImageFiles] Deleting cached files...");
    if (self.appPath) {
        NSError *error = nil;
        NSArray *filesList = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:self.appPath error:&error];
        if (error) {
            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][deleteCachedImageFiles] Could not get temporary folder contents : %@.", error.localizedDescription);
        } else {
            if (filesList.count > 0) {
                filesList = [filesList filteredArrayUsingPredicate:[NSPredicate predicateWithFormat:[NSString stringWithFormat:@"self ENDSWITH '-%@.jpg'", self.filenameSuffix]]];
                if (filesList.count > 0) {
                    BOOL deleted;
                    for (NSString* file in filesList) {
                        error = nil;
                        deleted = [[NSFileManager defaultManager] removeItemAtPath:[self.appPath stringByAppendingPathComponent:file] error:&error];
                        if (error) {
                            if (LOGGING) NSLog(@"[ERROR][CanvasCamera][deleteCachedImageFiles] Could not delete file with path [%@] : %@.", file, error.localizedDescription);
                        } else {
                            if (deleted) {
                                if (LOGGING) NSLog(@"[DEBUG][CanvasCamera][deleteCachedImageFiles] Cached file [%@] deleted !", file);
                            } else {
                                if (LOGGING) NSLog(@"[ERROR][CanvasCamera][deleteCachedImageFiles] Could not delete cached file with path [%@].", file);
                            }
                        }
                    }
                }
            }
        }
    }
}

- (NSString *)getAppPath {
    // Get application available data paths.
    NSArray* paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
    if (paths.count > 0) {
        // Creating a new path for a new temporary folder in the first available application path.
        NSString *appDataPath = [paths[0] stringByAppendingPathComponent:@"/tmp"];
        // If the temporary folder does not exist we create it.
        if (![[NSFileManager defaultManager] fileExistsAtPath:appDataPath]) {
            NSError *error = nil;
            // Creating a temporary folder for image files.
            [[NSFileManager defaultManager] createDirectoryAtPath:appDataPath withIntermediateDirectories:NO attributes:nil error:&error];
            if (error) {
                if (LOGGING) NSLog(@"[ERROR][CanvasCamera][getAppPath] Could not create tmp folder : %@.", error.localizedDescription);
                return nil;
            }
        }

        return appDataPath;
    }

    return nil;
}

- (UIImage *)resizedUIImage:(UIImage *)uiImage ratio:(CGFloat)ratio {
    if (ratio <= 0) {
        ratio = 1;
    }

    CGSize size = CGSizeMake((CGFloat)(uiImage.size.width * ratio), (CGFloat)(uiImage.size.height * ratio));

    return [self resizedUIImage:uiImage toSize:size];
}

- (UIImage *)resizedUIImage:(UIImage *)uiImage toSize:(CGSize)size rotated:(BOOL)rotated {

    if (rotated) {
        size = CGSizeMake(size.height, size.width);
    }

    return [self resizedUIImage:uiImage toSize:size];
}

- (UIImage *)resizedUIImage:(UIImage *)uiImage toSize:(CGSize)size {
    size = [self calculateAspectRatio:uiImage.size targetSize:size];

    UIGraphicsBeginImageContext(size);

    CGContextRef context = UIGraphicsGetCurrentContext();

    CGContextSetInterpolationQuality(context, kCGInterpolationHigh);

    [uiImage drawInRect:CGRectMake(0, 0, size.width, size.height)];
    UIImage *resizedUIImage = UIGraphicsGetImageFromCurrentImageContext();

    UIGraphicsEndImageContext();

    return resizedUIImage;
}

- (CGSize)calculateAspectRatio:(CGSize)origSize targetSize:(CGSize)targetSize {
    CGSize newSize = CGSizeMake(targetSize.width, targetSize.height);

    if (newSize.width <= 0 && newSize.height <= 0) {
        // If no new width or height were specified return the original bitmap
        newSize.width = origSize.width;
        newSize.height = origSize.height;
    } else if (newSize.width > 0 && newSize.height <= 0) {
        // Only the width was specified
        newSize.height = (CGFloat) ((newSize.width / origSize.width) * origSize.height);
    } else if (newSize.width <= 0 && newSize.height > 0) {
        // only the height was specified
        newSize.width = (CGFloat) ((newSize.height / origSize.height) * origSize.width);
    } else {
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        CGFloat newRatio = (CGFloat) (newSize.width /  newSize.height);
        CGFloat origRatio = (CGFloat) (origSize.width / origSize.height);

        if (origRatio > newRatio) {
            newSize.height = (CGFloat) ((newSize.width * origSize.height) / origSize.width);
        } else if (origRatio < newRatio) {
            newSize.width = (CGFloat) ((newSize.height * origSize.width) / origSize.height);
        }
    }

    return newSize;
}
@end
