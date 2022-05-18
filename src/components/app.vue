<template>
  <f7-app v-bind="f7params">
    <img
      class="app__img"
      id="app__img"
      ref="imageRef"
      width="224"
      height="224"
    />
    <!-- Your main view, should have "view-main" class -->
    <f7-view main class="safe-areas" url="/"></f7-view>
  </f7-app>
</template>
<script>
import { onMounted, ref } from 'vue';
import { f7, f7ready } from 'framework7-vue';
import routes from '../js/routes';
import blinkCapture from '../js/blinkPrediction';

export default {
  setup() {
    // Framework7 Parameters
    const f7params = {
      name: 'Blink-To-Text', // App name
      theme: 'auto', // Automatic theme detection
      routes, // App routes
    };
    const imageRef = ref(null);

    onMounted(() => {
      f7ready(async () => {
        await blinkCapture.loadModel();
        // First prediction takes more time to predict so we pass empty image during loading page
        await blinkCapture.startPrediciton(imageRef.value);
        f7.views.current.router.navigate('/predicting', {
          transition: 'f7-dive',
          clearPreviousHistory: true,
        });
      });
    });

    return {
      f7params,
      imageRef,
    };
  },
};
</script>
