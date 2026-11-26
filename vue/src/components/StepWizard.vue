<template>
  <div class="jm-wizard">
    <nav
      class="jm-wizard-rail"
      aria-label="Wizard steps"
    >
      <div
        class="jm-wizard-track"
        aria-hidden="true"
      >
        <div
          class="jm-wizard-track-fill"
          :style="{ width: progressPercent + '%' }"
        />
      </div>
      <button
        v-for="(step, idx) in steps"
        :key="step.key"
        type="button"
        class="jm-wizard-step"
        :class="{
          active: idx === currentIndex,
          done: idx < currentIndex
        }"
        :aria-current="idx === currentIndex ? 'step' : undefined"
        :disabled="busy"
        @click="goToStep(idx)"
      >
        <span
          class="jm-wizard-circle"
          aria-hidden="true"
        >
          <font-awesome-icon
            v-if="idx < currentIndex"
            :icon="['fas', 'check']"
          />
          <font-awesome-icon
            v-else
            :icon="step.icon"
          />
        </span>
        <span class="jm-wizard-title">{{ step.title }}</span>
      </button>
    </nav>

    <div class="jm-card jm-wizard-card">
      <div class="jm-wizard-body">
        <div
          v-for="(step, idx) in steps"
          v-show="idx === currentIndex"
          :key="step.key"
        >
          <slot :name="step.key" />
        </div>
      </div>

      <div class="jm-wizard-footer">
        <BButton
          variant="outline-secondary"
          :disabled="busy"
          @click="emit('cancel')"
        >
          {{ cancelText }}
        </BButton>
        <div class="jm-action-group">
          <BButton
            v-if="currentIndex > 0"
            variant="secondary"
            :disabled="busy"
            @click="goPrev"
          >
            <font-awesome-icon
              :icon="['fas', 'arrow-left']"
              class="me-1"
              aria-hidden="true"
            />
            {{ backText }}
          </BButton>
          <BButton
            v-if="!isLastStep"
            variant="primary"
            :disabled="busy"
            @click="goNext"
          >
            {{ nextText }}
            <font-awesome-icon
              :icon="['fas', 'arrow-right']"
              class="ms-1"
              aria-hidden="true"
            />
          </BButton>
          <BButton
            v-else
            variant="success"
            :disabled="busy"
            @click="handleFinish"
          >
            <font-awesome-icon
              :icon="['fas', 'check']"
              class="me-1"
              aria-hidden="true"
            />
            {{ finishText }}
          </BButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BButton } from 'bootstrap-vue-next'

export interface WizardStep {
  key: string
  title: string
  icon: [string, string]
  beforeChange?: () => boolean | Promise<boolean>
}

const props = withDefaults(defineProps<{
  steps: WizardStep[]
  cancelText?: string
  backText?: string
  nextText?: string
  finishText?: string
  busy?: boolean
}>(), {
  cancelText: 'Cancel',
  backText: 'Back',
  nextText: 'Next',
  finishText: 'Confirm',
  busy: false
})

const emit = defineEmits<{
  (e: 'complete'): void
  (e: 'cancel'): void
  (e: 'step-change', index: number): void
}>()

const currentIndex = ref(0)

watch(
  () => props.steps.map(step => step.key).join('|'),
  () => {
    if (currentIndex.value >= props.steps.length) {
      currentIndex.value = Math.max(0, props.steps.length - 1)
    }
  }
)

const isLastStep = computed(() => currentIndex.value >= props.steps.length - 1)

const progressPercent = computed(() => {
  if (props.steps.length <= 1) return 100
  return (currentIndex.value / (props.steps.length - 1)) * 100
})

async function runStepValidator(index: number): Promise<boolean> {
  const step = props.steps[index]
  if (!step?.beforeChange) return true
  const result = await step.beforeChange()
  return result !== false
}

async function goNext() {
  if (props.busy || isLastStep.value) return
  if (!(await runStepValidator(currentIndex.value))) return
  currentIndex.value += 1
  emit('step-change', currentIndex.value)
}

function goPrev() {
  if (props.busy || currentIndex.value <= 0) return
  currentIndex.value -= 1
  emit('step-change', currentIndex.value)
}

async function goToStep(targetIndex: number) {
  if (props.busy || targetIndex === currentIndex.value || targetIndex < 0 || targetIndex >= props.steps.length) return
  if (targetIndex < currentIndex.value) {
    currentIndex.value = targetIndex
    emit('step-change', currentIndex.value)
    return
  }
  for (let i = currentIndex.value; i < targetIndex; i++) {
    if (!(await runStepValidator(i))) return
  }
  currentIndex.value = targetIndex
  emit('step-change', currentIndex.value)
}

async function handleFinish() {
  if (props.busy) return
  if (!(await runStepValidator(currentIndex.value))) return
  emit('complete')
}

defineExpose({
  currentIndex,
  goNext,
  goPrev,
  goToStep
})
</script>

<style scoped>
.jm-wizard {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.jm-wizard-rail {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.25rem 0.5rem;
  overflow-x: auto;
}

.jm-wizard-track {
  position: absolute;
  left: 2.5rem;
  right: 2.5rem;
  top: 24px;
  height: 2px;
  background: var(--jm-border-strong);
  z-index: 0;
}

.jm-wizard-track-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--jm-emerald), var(--jm-cyan));
  transition: width 0.25s ease;
}

.jm-wizard-step {
  position: relative;
  z-index: 1;
  flex: 1 1 0;
  min-width: 92px;
  appearance: none;
  background: transparent;
  border: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.45rem;
  cursor: pointer;
  color: var(--jm-text-secondary);
}

.jm-wizard-step:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.jm-wizard-circle {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--jm-bg-surface);
  border: 2px solid var(--jm-border-strong);
  color: var(--jm-text-secondary);
  font-size: 0.9rem;
  transition: all 0.2s ease;
}

.jm-wizard-step:hover:not(:disabled) .jm-wizard-circle {
  border-color: rgba(56, 189, 248, 0.55);
  color: var(--jm-text-primary);
}

.jm-wizard-step.active .jm-wizard-circle {
  background: rgba(56, 189, 248, 0.16);
  border-color: var(--jm-cyan);
  color: var(--jm-cyan);
  box-shadow: 0 0 0 5px rgba(56, 189, 248, 0.14);
}

.jm-wizard-step.done .jm-wizard-circle {
  background: var(--jm-emerald);
  border-color: var(--jm-emerald);
  color: #031f16;
}

.jm-wizard-title {
  font-size: 0.78rem;
  font-weight: 500;
  text-align: center;
  line-height: 1.25;
}

.jm-wizard-step.active .jm-wizard-title {
  color: var(--jm-text-primary);
  font-weight: 600;
}

.jm-wizard-step.done .jm-wizard-title {
  color: #6ee7b7;
}

.jm-wizard-body {
  padding: 1.5rem;
}

.jm-wizard-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--jm-border-subtle);
  background: rgba(11, 17, 29, 0.55);
}

@media (max-width: 640px) {
  .jm-wizard-body {
    padding: 1rem;
  }
  .jm-wizard-footer {
    padding: 0.85rem 1rem;
  }
}
</style>
