<template>
  <f7-page name="PredictingPage">
    <div class="morse-code-page">
      <h3 class="morse-code-page__title">Morse Code</h3>
      <p class="morse-code-page__description">
        Morse Code encodes text characters as standardized sequences of two
        different signal durations.
      </p>
      <img
        class="morse-code-page__img"
        src="../static/images/morse-code.png"
        alt="morse-code-table"
      />
      <h3 class="morse-code-page__how-title" @click="playVideo">How to Use</h3>
      <p class="morse-code-page__how-description">
        Click on Start Capturing and you will have 7 seconds to blink one
        character. <br />
        If you want to delete the last character, click Remove Letter.
      </p>
      <a class="morse-code-page__btn back" href="/">Back</a>
    </div>
  </f7-page>
</template>

<script>
export default {};
</script>
