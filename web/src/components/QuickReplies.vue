<script setup lang="ts">
defineProps<{ options: string[] }>()
defineEmits<{ (e: 'pick', text: string): void }>()
</script>

<template>
  <div v-if="options.length" class="quick-replies">
    <button v-for="o in options" :key="o" class="quick-chip" @click="$emit('pick', o)">
      {{ o }}
    </button>
    <span class="quick-hint">也可以直接打字</span>
  </div>
</template>
