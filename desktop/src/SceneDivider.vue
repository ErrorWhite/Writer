<script setup>
defineProps({ variant: { type: String, default: 'ornament' } })
</script>

<template>
  <span class="scene-divider" :class="'scene-divider-' + variant" role="separator" aria-label="场景分隔线">
    <span class="scene-divider-line" aria-hidden="true"></span>
    <span v-if="variant !== 'line'" class="scene-divider-mark" aria-hidden="true">{{ variant === 'stars' ? '＊ ＊ ＊' : '✦' }}</span>
    <span v-if="variant !== 'line'" class="scene-divider-line" aria-hidden="true"></span>
  </span>
</template>
