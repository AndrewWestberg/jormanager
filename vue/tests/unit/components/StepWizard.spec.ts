import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createBootstrap } from 'bootstrap-vue-next'
import StepWizard, { type WizardStep } from '@/components/StepWizard.vue'

function mountWizard(steps: WizardStep[]) {
  return mount(StepWizard, {
    props: { steps },
    slots: {
      first: '<div data-testid="step-first">Step 1 Body</div>',
      second: '<div data-testid="step-second">Step 2 Body</div>',
      third: '<div data-testid="step-third">Step 3 Body</div>'
    },
    global: {
      plugins: [createBootstrap()],
      stubs: { 'font-awesome-icon': true }
    }
  })
}

describe('StepWizard', () => {
  it('advances steps when beforeChange succeeds, blocks when it fails, and emits complete on final step', async () => {
    let allowStep1 = false
    const validateStep1 = vi.fn(() => allowStep1)
    const steps: WizardStep[] = [
      { key: 'first', title: 'Basics', icon: ['fas', 'dice-d20'], beforeChange: validateStep1 },
      { key: 'second', title: 'Config', icon: ['fas', 'percent'] },
      { key: 'third', title: 'Review', icon: ['fas', 'check-circle'] }
    ]

    const wrapper = mountWizard(steps)
    const buttons = () => wrapper.findAll('button')
    const nextBtn = () => buttons().find(b => b.text().includes('Next'))!

    // Blocked while validator returns false
    await nextBtn().trigger('click')
    await flushPromises()
    expect(validateStep1).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Basics')

    // Passes when validator returns true
    allowStep1 = true
    await nextBtn().trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Config')

    // Advance to final step
    await nextBtn().trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Review')

    // Clicking Confirm emits complete
    const finishBtn = buttons().find(b => b.text().includes('Confirm'))!
    await finishBtn.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('complete')).toHaveLength(1)

    // Clicking Back moves back to Config
    const backBtn = buttons().find(b => b.text().includes('Back'))!
    await backBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Config')

    // Clicking Cancel emits cancel
    const cancelBtn = buttons().find(b => b.text().includes('Cancel'))!
    await cancelBtn.trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    wrapper.unmount()
  })

  it('prevents skipping past a failing intermediate step when clicking step rail directly', async () => {
    const steps: WizardStep[] = [
      { key: 'first', title: 'Basics', icon: ['fas', 'dice-d20'], beforeChange: () => false },
      { key: 'second', title: 'Config', icon: ['fas', 'percent'] },
      { key: 'third', title: 'Review', icon: ['fas', 'check-circle'] }
    ]

    const wrapper = mountWizard(steps)
    const railButtons = wrapper.findAll('.jm-wizard-step')
    await railButtons[2].trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-current="step"]').text()).toContain('Basics')
    wrapper.unmount()
  })
})
