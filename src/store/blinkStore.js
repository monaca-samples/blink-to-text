/* eslint-disable import/prefer-default-export */
import { defineStore } from 'pinia';
import convertBlinkSequenceToLetter from '../js/morseCodeTable';

export const useBlinkStore = defineStore('blinkStore', {
  state: () => ({
    blinkSequence: '',
    capturing: false,
    convertedLetters: '',
    timer: null,
  }),
  actions: {
    setBlinkSequence(blink) {
      if (this.capturing) {
        this.blinkSequence += blink;
        if (this.blinkSequence.length === 4) {
          this.stopCapturingBlinks();
        }
      }
    },
    convertCapturedSequence() {
      this.capturing = false;
      const convertedLetter = convertBlinkSequenceToLetter(this.blinkSequence);
      if (convertedLetter !== undefined) {
        this.convertedLetters += convertedLetter;
      }
      this.blinkSequence = '';
    },
    startCapturingBlinks() {
      this.capturing = true;
      // 7 seconds countdown for capturing the blinks
      this.timer = setTimeout(() => {
        this.convertCapturedSequence();
      }, 7000);
    },
    stopCapturingBlinks() {
      this.convertCapturedSequence();
      clearTimeout(this.timer);
    },
    removeLastLetter() {
      this.convertedLetters = this.convertedLetters.slice(0, -1);
    },
  },
});
