/**
 * CanvasCamera.js
 * PhoneGap iOS and Android Cordova Plugin to capture Camera streaming into an HTML5 Canvas.
 *
 * VirtuoWorks <contact@virtuoworks.com>.
 *
 * MIT License
 */

#import <Cordova/CDVPlugin.h>
#import <objc/message.h>
#import <UIKit/UIKit.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <AVFoundation/AVFoundation.h>
#import <ImageIO/ImageIO.h>
#import <AssetsLibrary/AssetsLibrary.h>

#pragma mark - CanvasCamera Public Constants

static NSString *const CCUseKey              = @"use";
static NSString *const CCFpsKey              = @"fps";
static NSString *const CCWidthKey            = @"width";
static NSString *const CCHeightKey           = @"height";
static NSString *const CCCanvasKey           = @"canvas";
static NSString *const CCCaptureKey          = @"capture";
static NSString *const CCFlashModeKey        = @"flashMode";
static NSString *const CCHasThumbnailKey     = @"hasThumbnail";
static NSString *const CCThumbnailRatioKey   = @"thumbnailRatio";
static NSString *const CCLensOrientationKey  = @"cameraFacing";

#pragma mark - CanvasCamera Public Interface

@interface CanvasCamera : CDVPlugin <AVCaptureVideoDataOutputSampleBufferDelegate>

// Public Access
@property (readwrite, strong) NSString *use;
@property (readwrite, assign) NSInteger fps;
@property (readwrite, assign) NSInteger width;
@property (readwrite, assign) NSInteger height;
@property (readwrite, assign) NSInteger canvasHeight;
@property (readwrite, assign) NSInteger canvasWidth;
@property (readwrite, assign) NSInteger captureHeight;
@property (readwrite, assign) NSInteger captureWidth;

@property (readwrite, assign) BOOL hasThumbnail;
@property (readwrite, assign) CGFloat thumbnailRatio;

@property (readwrite, assign) AVCaptureDevicePosition devicePosition;

@property (readwrite, assign) BOOL isPreviewing;

- (void)startCapture:(CDVInvokedUrlCommand *)command;
- (void)stopCapture:(CDVInvokedUrlCommand *)command;
- (void)flashMode:(CDVInvokedUrlCommand *)command;
- (void)cameraPosition:(CDVInvokedUrlCommand *)command;

- (NSString *)filenameSuffix;
- (CGSize)calculateAspectRatio:(CGSize)origSize targetSize:(CGSize)targetSize;

@end

