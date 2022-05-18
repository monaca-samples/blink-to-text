import * as faceLandmarksDetection from '@tensorflow-models/face-landmarks-detection';
import '@tensorflow/tfjs-backend-webgl';

const EAR_THRESHOLD = 0.27;
let model;
let event;
let blinkCount = 0;

// Loading model from Tensorflow.js
const loadModel = async () => {
  model = await faceLandmarksDetection.load(
    faceLandmarksDetection.SupportedPackages.mediapipeFacemesh,
    { maxFaces: 1 },
  );
};

// Calculate the position of eyelid to predict a blink
function getEAR(upper, lower) {
  function getEucledianDistance(x1, y1, x2, y2) {
    return Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
  }

  return (
    (getEucledianDistance(upper[5][0], upper[5][1], lower[4][0], lower[4][1])
      + getEucledianDistance(
        upper[3][0],
        upper[3][1],
        lower[2][0],
        lower[2][1],
      ))
    / (2
      * getEucledianDistance(upper[0][0], upper[0][1], upper[8][0], upper[8][1]))
  );
}

async function startPrediciton(video) {
  // Sending video to model for prediction
  const predictions = await model.estimateFaces({
    input: video,
  });

  if (predictions.length > 0) {
    predictions.forEach((prediction) => {
      // Right eye parameters
      const lowerRight = prediction.annotations.rightEyeUpper0;
      const upperRight = prediction.annotations.rightEyeLower0;
      const rightEAR = getEAR(upperRight, lowerRight);
      // Left eye parameters
      const lowerLeft = prediction.annotations.leftEyeUpper0;
      const upperLeft = prediction.annotations.leftEyeLower0;
      const leftEAR = getEAR(upperLeft, lowerLeft);

      // True if the eye is closed
      const blinked = leftEAR <= EAR_THRESHOLD && rightEAR <= EAR_THRESHOLD;

      // Determine how long you blinked
      if (blinked) {
        event = {
          shortBlink: false,
          longBlink: false,
        };
        blinkCount += 1;
      } else {
        event = {
          shortBlink: blinkCount <= 5 && blinkCount !== 0,
          longBlink: blinkCount > 5,
        };
        blinkCount = 0;
      }
    });
  }
  return event;
}

const blinkCapture = {
  loadModel,
  startPrediciton,
};

export default blinkCapture;
