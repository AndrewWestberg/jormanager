<template>
  <div>
    <div
      v-if="!showAddNodeWizard"
      id="nodes-home"
    >
      <div class="jm-page-header">
        <div>
          <h1 class="jm-page-title">
            Nodes &amp; Pools
          </h1>
          <p class="jm-page-subtitle">
            Operate block producers, relays, KES rotations, Leios keys, and governance votes
          </p>
        </div>
        <div class="jm-action-group">
          <BButton
            v-if="hasCoreNodes"
            v-b-tooltip.hover.bottom="'Submit governance vote for all core nodes'"
            variant="outline-success"
            @click="showGovernanceVoteModal = true"
          >
            <font-awesome-icon
              :icon="['fas', 'check-to-slot']"
              class="me-1"
              aria-hidden="true"
            />
            Vote
          </BButton>
          <BButton
            v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
            variant="primary"
            @click="poolParentId = null; showAddNodeWizard = true"
          >
            <font-awesome-icon
              :icon="['fas', 'plus']"
              class="me-1"
              aria-hidden="true"
            />
            Node
          </BButton>
        </div>
      </div>

      <!-- Filter & Search Toolbar -->
      <div class="jm-toolbar">
        <div
          class="jm-chip-group"
          role="group"
          aria-label="Filter nodes by type"
        >
          <button
            v-for="tab in roleFilters"
            :key="tab.value"
            type="button"
            class="jm-chip-btn"
            :class="{ active: roleFilter === tab.value }"
            @click="roleFilter = tab.value"
          >
            {{ tab.label }} ({{ tab.count }})
          </button>
        </div>
        <div class="jm-search-wrap">
          <font-awesome-icon
            :icon="['fas', 'search']"
            class="jm-search-icon"
            aria-hidden="true"
          />
          <BFormInput
            v-model="searchQuery"
            type="search"
            size="sm"
            class="jm-search-input"
            placeholder="Search node, host, pool ID…"
            aria-label="Search nodes"
          />
        </div>
      </div>

      <div
        v-if="filteredNodes.length > 0"
        class="jm-table-wrapper"
        tabindex="0"
        role="region"
        aria-label="Cardano Nodes"
      >
        <BTable
          small
          hover
          striped
          :items="filteredNodes"
          :fields="fields"
        >
          <template #cell(name)="{ item, value }">
            <div class="d-flex align-items-center justify-content-between gap-2 flex-wrap">
              <div class="d-flex align-items-center gap-2">
                <button
                  v-b-tooltip.hover.left="'Update Color'"
                  type="button"
                  class="jm-color-swatch-btn"
                  :style="{
                    backgroundColor: item.color,
                    boxShadow: `0 0 0 3px ${item.color}33`
                  }"
                  aria-label="Update node color"
                  @click="openColorModal(item.id, item.color)"
                >
                  <font-awesome-icon
                    v-if="item.isDefault"
                    :icon="['fas', 'check']"
                    class="jm-default-check"
                    aria-hidden="true"
                  />
                </button>
                <span class="jm-mono fw-semibold">{{ value }}</span>
                <span
                  v-if="item.isDefault"
                  class="jm-pill jm-pill-cyan"
                >Default</span>
              </div>
              <div
                v-if="item.type !== 'relay' && item.poolId"
                class="jm-addr-chip jm-mono"
              >
                <span>{{ truncatePoolId(item.poolId) }}</span>
                <button
                  v-b-tooltip.hover.right="{ title: 'Copy PoolId to clipboard', variant: 'secondary' }"
                  type="button"
                  class="jm-icon-btn cyan jm-mini-copy"
                  aria-label="Copy Pool ID"
                  @click="copyToClipboard(item.poolId)"
                >
                  <font-awesome-icon
                    :icon="['fas', 'copy']"
                    aria-hidden="true"
                  />
                </button>
              </div>
            </div>
          </template>

          <template #cell(type)="{ value }">
            <span
              v-if="value === 'relay'"
              v-b-tooltip.hover.right="{ title: 'Relay Node', variant: 'danger' }"
              class="jm-pill jm-pill-rose"
            >
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                aria-hidden="true"
              />
              Relay
            </span>
            <span
              v-else-if="value === 'core'"
              v-b-tooltip.hover.right="{ title: 'Core Node', variant: 'success' }"
              class="jm-pill jm-pill-emerald"
            >
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                aria-hidden="true"
              />
              Core
            </span>
            <span
              v-else-if="value === 'pool'"
              v-b-tooltip.hover.right="{ title: 'Pool Node', variant: 'primary' }"
              class="jm-pill jm-pill-cyan"
            >
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                aria-hidden="true"
              />
              Pool
            </span>
          </template>

          <template #cell(host)="{ value }">
            <span class="jm-mono text-secondary">{{ value || '—' }}</span>
          </template>

          <template #cell(kesExpireTimeSec)="{ item, value }">
            <div
              v-if="(value as number) > -1"
              class="d-inline-flex align-items-center gap-2 flex-wrap"
            >
              <span
                class="jm-pill jm-mono"
                :class="kesPillClass(value as number)"
              >
                {{ formatKesExpiry(value as number) }}
              </span>
              <button
                v-b-tooltip.hover.right="{ title: 'Rotate KES Key', variant: 'warning' }"
                type="button"
                class="jm-icon-btn amber"
                aria-label="Rotate KES Key"
                @click="rotateKesKey(item.name)"
              >
                <font-awesome-icon
                  :icon="['fas', 'key']"
                  aria-hidden="true"
                />
              </button>
            </div>
            <span
              v-else
              class="text-secondary"
            >—</span>
          </template>

          <template #cell(edit)="{ item }">
            <div class="d-flex align-items-center justify-content-end gap-1 flex-nowrap">
              <button
                v-if="item.type !== 'pool'"
                v-b-tooltip.hover.left="{ title: 'Restart Node', variant: 'danger' }"
                type="button"
                class="jm-icon-btn rose"
                aria-label="Restart Node"
                @click="restartNode(item.name)"
              >
                <font-awesome-icon
                  :icon="['fas', 'power-off']"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="item.type !== 'relay'"
                v-b-tooltip.hover.left="{ title: 'Edit Pool Config', variant: 'warning' }"
                type="button"
                class="jm-icon-btn amber"
                aria-label="Edit Pool Config"
                @click="openPoolConfigModal(item.id)"
              >
                <font-awesome-icon
                  :icon="['fas', 'percent']"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="item.type !== 'relay'"
                v-b-tooltip.hover.left="{ title: 'Edit Metadata', variant: 'primary' }"
                type="button"
                class="jm-icon-btn cyan"
                aria-label="Edit Metadata"
                @click="openMetadataModal(item.id)"
              >
                <font-awesome-icon
                  :icon="['fas', 'info-circle']"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="item.type !== 'relay'"
                v-b-tooltip.hover.left="{ title: 'Edit Relays', variant: 'success' }"
                type="button"
                class="jm-icon-btn emerald"
                aria-label="Edit Relays"
                @click="openRelaysModal(item.id)"
              >
                <font-awesome-icon
                  :icon="['fas', 'project-diagram']"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="item.type !== 'relay'"
                v-b-tooltip.hover.left="{ title: 'Leios key status', variant: 'info' }"
                type="button"
                class="jm-icon-btn indigo"
                aria-label="Leios key status"
                @click="leiosNodeId = item.id"
              >
                <font-awesome-icon
                  :icon="['fas', 'key']"
                  aria-hidden="true"
                />
              </button>
              <button
                v-if="item.type === 'core' && mp"
                v-b-tooltip.hover.left="{ title: 'Add Pool', variant: 'primary' }"
                type="button"
                class="jm-icon-btn cyan"
                aria-label="Add Pool"
                @click="addPool(item.id)"
              >
                <font-awesome-icon
                  :icon="['fas', 'plus-circle']"
                  aria-hidden="true"
                />
              </button>
              <template v-if="item.type !== 'relay'">
                <span
                  class="jm-action-divider"
                  aria-hidden="true"
                />
                <button
                  v-b-tooltip.hover.left="{ title: 'Retire Pool', variant: 'danger' }"
                  type="button"
                  class="jm-icon-btn rose"
                  aria-label="Retire Pool"
                  @click="openRetireModal(item.id)"
                >
                  <font-awesome-icon
                    :icon="['fas', 'skull']"
                    aria-hidden="true"
                  />
                </button>
              </template>
            </div>
          </template>
        </BTable>
      </div>

      <div
        v-else
        class="jm-card jm-empty-state"
      >
        <div class="jm-empty-icon">
          <font-awesome-icon
            :icon="['fas', 'dice-d20']"
            aria-hidden="true"
          />
        </div>
        <h2 class="h6 mb-1">
          {{ displayNodes.length === 0 ? 'No nodes configured yet' : 'No matching nodes' }}
        </h2>
        <p class="small mb-3">
          {{ displayNodes.length === 0 ? 'Provision your first Cardano relay or block-producing core node.' : 'Try adjusting your role filter or search query.' }}
        </p>
        <BButton
          v-if="displayNodes.length === 0"
          variant="primary"
          size="sm"
          @click="poolParentId = null; showAddNodeWizard = true"
        >
          + Add Node
        </BButton>
      </div>
    </div>

    <AddNodeWizard
      v-if="showAddNodeWizard"
      :parent-id="poolParentId"
      @hide-add-node-wizard="showAddNodeWizard = false"
    />

    <BModal
      :model-value="leiosNodeId !== null"
      title="Leios key lifecycle"
      size="xl"
      no-footer
      @update:model-value="value => { if (!value) leiosNodeId = null }"
    >
      <LeiosKeyPanel
        v-if="leiosNodeId !== null"
        :node-id="leiosNodeId"
      />
    </BModal>

    <!-- Edit Color Modal -->
    <BModal
      v-model="showColorModal"
      title="Edit Node Color"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveColor"
    >
      <div class="mb-3">
        <div class="small text-secondary mb-2">
          Quick Swatches
        </div>
        <div class="d-flex gap-2 flex-wrap">
          <button
            v-for="swatch in presetSwatches"
            :key="swatch"
            type="button"
            class="jm-preset-swatch"
            :class="{ selected: editColorForm.color.toLowerCase() === swatch.toLowerCase() }"
            :style="{ backgroundColor: swatch }"
            :aria-label="`Select color ${swatch}`"
            @click="editColorForm.color = swatch"
          />
        </div>
      </div>
      <BFormGroup
        label="Custom Color"
        label-cols-md="3"
      >
        <div class="d-flex align-items-center gap-3">
          <BFormInput
            v-model="editColorForm.color"
            type="color"
            style="width: 56px; height: 38px; padding: 2px;"
          />
          <span class="jm-mono text-secondary">{{ editColorForm.color }}</span>
        </div>
      </BFormGroup>
    </BModal>

    <!-- Edit Pool Config Modal -->
    <BModal
      v-model="showPoolConfigModal"
      :title="poolConfigStep === 1 ? 'Edit Pool Config' : 'Review Pool Config'"
      size="xl"
      :no-close-on-backdrop="true"
      :ok-title="poolConfigStep === 1 ? 'Review' : 'Submit'"
      :ok-disabled="poolConfigStep !== 1 && nodeChainUnavailable(editPoolConfigForm.nodeId)"
      :cancel-title="poolConfigStep === 1 ? 'Cancel' : 'Back'"
      @ok.prevent="handleSavePoolConfig"
      @cancel.prevent="handleCancelPoolConfig"
    >
      <div v-if="poolConfigStep === 1">
        <div class="jm-sub-panel mb-3">
          <h5 class="jm-sub-panel-title">
            Account Config
          </h5>
          <BFormGroup
            label="Fees Account"
            label-cols-md="3"
            class="mb-3"
          >
            <BFormSelect
              v-model="editPoolConfigForm.registrationFeesAccount"
              :options="feeAccountOptions"
              :state="editPoolConfigForm.registrationFeesAccount != null"
            >
              <template #first>
                <BFormSelectOption
                  :value="null"
                  disabled
                >
                  -- Please select --
                </BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
          <label class="d-block mb-2 small"><input
            v-model="replacePoolOwners"
            type="checkbox"
            class="me-2"
          >Replace the entire owner set with the selected accounts</label>
          <BFormGroup
            label="Owner (Pledge) Accounts"
            label-cols-md="3"
            class="mb-3"
          >
            <BFormSelect
              v-model="selectedPoolOwners"
              :options="stakingAccountOptions"
              :state="!replacePoolOwners || selectedPoolOwners.length > 0"
              :disabled="!replacePoolOwners"
              multiple
            />
          </BFormGroup>
          <label class="d-block mb-2 small"><input
            v-model="changePoolReward"
            type="checkbox"
            class="me-2"
          >Change the reward account</label>
          <BFormGroup
            label="Rewards Account"
            label-cols-md="3"
          >
            <BFormSelect
              v-model="editPoolConfigForm.rewardsStakingAccount"
              :options="rewardsAccountOptions"
              :state="editPoolConfigForm.rewardsStakingAccount != null"
              :disabled="!changePoolReward"
            >
              <template #first>
                <BFormSelectOption
                  :value="null"
                  disabled
                >
                  -- Please select --
                </BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
        </div>

        <div class="jm-sub-panel">
          <h5 class="jm-sub-panel-title">
            Pledge &amp; Fees
          </h5>
          <BFormGroup
            label="Pledge (lovelace)"
            label-cols-md="3"
            class="mb-3"
          >
            <BFormInput
              v-model="editPoolConfigForm.poolPledge"
              placeholder="e.g. 250000000000"
              class="jm-mono"
            />
          </BFormGroup>
          <BFormGroup
            label="Cost (lovelace)"
            label-cols-md="3"
            class="mb-3"
          >
            <BFormInput
              v-model="editPoolConfigForm.poolCost"
              placeholder="e.g. 340000000"
              class="jm-mono"
            />
          </BFormGroup>
          <BFormGroup
            label="Margin"
            label-cols-md="3"
          >
            <div class="d-flex align-items-center gap-3">
              <BFormInput
                v-model="editPoolConfigForm.poolMargin"
                type="number"
                min="0.00"
                max="1.00"
                step="0.001"
                class="jm-mono"
              />
              <span class="jm-pill jm-pill-cyan jm-mono">{{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%</span>
            </div>
          </BFormGroup>
        </div>
      </div>
      <div v-else>
        <p class="mb-2">
          Target pool: <strong class="jm-mono">{{ nodes.find(node => node.id === editPoolConfigForm.nodeId)?.name }}</strong> ({{ editPoolConfigForm.nodeId }}).
        </p>
        <h5>Please Review Your Changes</h5>
        <p class="small text-secondary">
          Owner set: {{ replacePoolOwners ? selectedPoolOwners.join(', ') : 'preserve current owners' }}. Reward account: {{ changePoolReward ? editPoolConfigForm.rewardsStakingAccount : 'preserve current reward account' }}.
        </p>
        <div class="jm-table-wrapper">
          <table class="table table-sm mb-0">
            <tbody>
              <tr>
                <th style="width: 30%">
                  Pledge
                </th>
                <td class="jm-mono">
                  {{ editPoolConfigForm.poolPledge }}
                </td>
              </tr>
              <tr>
                <th>Fixed Cost</th>
                <td class="jm-mono">
                  {{ editPoolConfigForm.poolCost }}
                </td>
              </tr>
              <tr>
                <th>Margin</th>
                <td class="jm-mono">
                  {{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </BModal>

    <!-- Edit Metadata Modal -->
    <BModal
      v-model="showMetadataModal"
      title="Edit Metadata"
      size="xl"
      :no-close-on-backdrop="true"
      ok-title="Submit"
      :ok-disabled="nodeChainUnavailable(editMetadataForm.nodeId)"
      @ok.prevent="handleSaveMetadata"
    >
      <BFormGroup
        label="Fees Account"
        class="mb-3"
      >
        <BFormSelect
          v-model="poolChangeFeeAccount"
          :options="feeAccountOptions"
        />
      </BFormGroup>
      <div class="jm-sub-panel mb-3">
        <h5 class="jm-sub-panel-title">
          Primary (Required)
        </h5>
        <BFormGroup
          label="Ticker"
          label-cols-md="2"
          class="mb-2"
        >
          <BFormInput
            v-model="editMetadataForm.ticker"
            placeholder="e.g. TICKR"
            maxlength="5"
            class="jm-mono"
          />
        </BFormGroup>
        <BFormGroup
          label="Name"
          label-cols-md="2"
          class="mb-2"
        >
          <BFormInput
            v-model="editMetadataForm.name"
            placeholder="e.g. My Awesome Pool"
            maxlength="50"
          />
        </BFormGroup>
        <BFormGroup
          label="Description"
          label-cols-md="2"
          class="mb-2"
        >
          <BFormInput
            v-model="editMetadataForm.description"
            placeholder="Pool description"
            maxlength="255"
          />
        </BFormGroup>
        <BFormGroup
          label="Homepage"
          label-cols-md="2"
        >
          <BFormInput
            v-model="editMetadataForm.homepage"
            placeholder="e.g. https://mypool.com"
            class="jm-mono"
          />
        </BFormGroup>
      </div>

      <div class="jm-sub-panel">
        <h5 class="jm-sub-panel-title">
          Extended (Optional)
        </h5>
        <BFormGroup
          label="Location"
          label-cols-md="2"
          class="mb-2"
        >
          <BFormInput
            v-model="editMetadataForm.location"
            placeholder="e.g. United States"
          />
        </BFormGroup>
        <BFormGroup
          label="Twitter"
          label-cols-md="2"
        >
          <BFormInput
            v-model="editMetadataForm.twitter"
            placeholder="e.g. mypool"
          />
        </BFormGroup>
      </div>
    </BModal>

    <!-- Edit Relays Modal -->
    <BModal
      v-model="showRelaysModal"
      :title="relaysStep === 1 ? 'Edit Relays' : 'Review Relays'"
      size="lg"
      :no-close-on-backdrop="true"
      :ok-title="relaysStep === 1 ? 'Review' : 'Submit'"
      :ok-disabled="relaysStep !== 1 && nodeChainUnavailable(editRelaysForm.nodeId)"
      :cancel-title="relaysStep === 1 ? 'Cancel' : 'Back'"
      @ok.prevent="handleSaveRelays"
      @cancel.prevent="handleCancelRelays"
    >
      <BFormGroup
        label="Fees Account"
        class="mb-3"
      >
        <BFormSelect
          v-model="poolChangeFeeAccount"
          :options="feeAccountOptions"
        />
      </BFormGroup>
      <div v-if="relaysStep === 1">
        <div
          v-for="(relay, index) in editRelaysForm.relays"
          :key="index"
          class="jm-sub-panel mb-3"
        >
          <div class="d-flex justify-content-between align-items-center mb-2">
            <span class="jm-pill jm-pill-indigo">Relay {{ index + 1 }}</span>
            <BButton
              variant="outline-danger"
              size="sm"
              @click="editRelaysForm.relays.splice(index, 1)"
            >
              ✕
            </BButton>
          </div>
          <BFormGroup
            label="Address"
            label-cols-md="2"
            class="mb-2"
          >
            <BFormInput
              v-model="relay.addr"
              placeholder="e.g. relay1.mypool.com"
              class="jm-mono"
            />
          </BFormGroup>
          <BFormGroup
            label="Port"
            label-cols-md="2"
          >
            <BFormInput
              v-model="relay.port"
              type="number"
              placeholder="e.g. 3001"
              class="jm-mono"
            />
          </BFormGroup>
        </div>
        <BButton
          variant="primary"
          size="sm"
          @click="editRelaysForm.relays.push({ addr: '', port: 3001 })"
        >
          + Add Relay
        </BButton>
      </div>
      <div v-else>
        <h5>Please Review Your Relays</h5>
        <div
          v-if="editRelaysForm.relays.length > 0"
          class="jm-table-wrapper"
        >
          <table class="table table-sm mb-0">
            <thead>
              <tr>
                <th>Address</th>
                <th>Port</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(relay, idx) in editRelaysForm.relays"
                :key="idx"
              >
                <td class="jm-mono">
                  {{ relay.addr }}
                </td>
                <td class="jm-mono">
                  {{ relay.port }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p
          v-else
          class="text-secondary"
        >
          No relays configured.
        </p>
      </div>
    </BModal>

    <!-- Retire Pool Modal -->
    <BModal
      v-model="showRetireModal"
      title="Retire Pool"
      :no-close-on-backdrop="true"
      no-footer
      :no-close-on-esc="retirementBusy"
      :no-header-close="retirementBusy"
    >
      <div
        class="alert alert-danger d-flex align-items-center gap-2 mb-3"
        role="alert"
      >
        <font-awesome-icon
          :icon="['fas', 'exclamation-triangle']"
          aria-hidden="true"
        />
        <span>Warning: This action will schedule retirement for your pool!</span>
      </div>
      <p class="mb-3">
        Target pool: <strong class="jm-mono">{{ nodes.find(node => node.id === retirePoolForm.id)?.name }}</strong> ({{ retirePoolForm.id }}).
      </p>
      <BFormGroup
        label="Fees Account"
        label-cols-md="4"
        class="mb-3"
      >
        <BFormSelect
          v-model="retirePoolForm.retireFeesAccount"
          :options="retireFeeAccountOptions"
          :state="retirePoolForm.retireFeesAccount !== null"
        >
          <template #first>
            <BFormSelectOption
              :value="null"
              disabled
            >
              -- Please select --
            </BFormSelectOption>
          </template>
        </BFormSelect>
      </BFormGroup>
      <BFormGroup
        label="Retire in Epoch"
        label-cols-md="4"
        class="mb-3"
      >
        <BFormInput
          v-model="retirePoolForm.retireEpoch"
          type="number"
          placeholder="e.g. Current epoch + 2"
          class="jm-mono"
          :state="/^[1-9]\d*$/.test(String(retirePoolForm.retireEpoch))"
        />
      </BFormGroup>
      <p
        v-if="retirementError"
        class="text-danger small mb-3"
      >
        {{ retirementError }}
      </p>
      <div class="d-flex justify-content-end gap-2">
        <BButton
          variant="secondary"
          :disabled="retirementBusy"
          @click="showRetireModal = false"
        >
          Close
        </BButton>
        <BButton
          variant="danger"
          :disabled="retirementBusy || nodeChainUnavailable(retirePoolForm.id)"
          @click="requestRetirement"
        >
          Retire pool
        </BButton>
      </div>
    </BModal>

    <!-- Governance Vote Modal -->
    <GovernanceVoteModal v-model="showGovernanceVoteModal" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import moment from 'moment-timezone'
import { find } from 'lodash-es'
import {
  BButton,
  BTable,
  BModal,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { generateUUID } from '@/utils/filters'
import type { PoolRegistrationResult, PoolRetirementResult } from '@/types'
import LeiosKeyPanel from '@/components/LeiosKeyPanel.vue'
import AddNodeWizard from '@/components/AddNodeWizard.vue'
import GovernanceVoteModal from '@/components/GovernanceVoteModal.vue'

interface Relay {
  addr: string
  port: number
}

const store = useJorManagerStore()
const { displayNodes, mp, nodes } = storeToRefs(store)
const emitter = useEventBus()

const showAddNodeWizard = ref(false)
const poolParentId = ref<number | null>(null)
const leiosNodeId = ref<number | null>(null)

// Filter state
const roleFilter = ref<'all' | 'core' | 'pool' | 'relay'>('all')
const searchQuery = ref('')
const presetSwatches = ['#38bdf8', '#818cf8', '#a855f7', '#10b981', '#f59e0b', '#f43f5e', '#ec4899', '#14b8a6']

// Modal visibility
const showColorModal = ref(false)
const showPoolConfigModal = ref(false)
const showMetadataModal = ref(false)
const showRelaysModal = ref(false)
const showRetireModal = ref(false)
const showGovernanceVoteModal = ref(false)

const poolConfigStep = ref(1)
const relaysStep = ref(1)

// Form data
const editColorForm = ref({ nodeId: 0, color: '#000000' })
const editPoolConfigForm = ref({
  nodeId: 0,
  registrationFeesAccount: null as number | null,
  ownerStakingAccount: null as number | null,
  rewardsStakingAccount: null as number | null,
  poolPledge: 0,
  poolCost: 340,
  poolMargin: 0.03
})
const editMetadataForm = ref({
  nodeId: 0,
  ticker: '',
  name: '',
  description: '',
  homepage: '',
  location: '',
  twitter: ''
})
type LoadedMetadata = {
  nodeId: number
  metadata: { ticker?: string; name?: string; description?: string; homepage?: string }
  extendedMetadata?: {
    telegramAdminHandle?: string[]; adapoolsVerify?: string
    info?: {
      urlPngIcon64x64?: string; urlPngLogo?: string; location?: string; rss?: string
      social?: { twitterHandle?: string; telegramHandle?: string; facebookHandle?: string; youtubeHandle?: string; twitchHandle?: string; discordHandle?: string; githubHandle?: string }
      company?: { name?: string; addr?: string; city?: string; country?: string; companyId?: string; vatId?: string }
      about?: { me?: string; server?: string; company?: string }
    }
  }
}
let loadedMetadata: LoadedMetadata | null = null
const editRelaysForm = ref({
  nodeId: 0,
  relays: [] as Relay[]
})
type PoolChangeRequest = { requestId: string; nodeId: number; feePayerAccountId: number; action: 'update'; keyId: null; changes: Record<string, unknown>; metadata?: unknown }
const replacePoolOwners = ref(false)
const selectedPoolOwners = ref<number[]>([])
const changePoolReward = ref(false)
const poolChangeFeeAccount = ref<number | null>(null)
const poolChangeBusy = ref(false)
const retirementBusy = ref(false)
const retirementError = ref('')
let poolRequestId = generateUUID()
let retirementRequestId = generateUUID()
watch([editPoolConfigForm, editMetadataForm, editRelaysForm, replacePoolOwners, selectedPoolOwners, changePoolReward, poolChangeFeeAccount],
  () => { poolRequestId = generateUUID() }, { deep: true, flush: 'sync' })

function nodeChainUnavailable(_nodeId: number) {
  return !store.connected
}

function requestPoolChange(nodeId: number, feePayerAccountId: number | null, changes: Record<string, unknown>, metadata?: unknown) {
  if (feePayerAccountId == null || poolChangeBusy.value) {
    store.toastError = { title: 'Pool update', message: 'Select a fee payer account.' }
    return
  }
  if (nodeChainUnavailable(nodeId)) return
  const data: PoolChangeRequest = JSON.parse(JSON.stringify({ requestId: poolRequestId, nodeId, feePayerAccountId, action: 'update', keyId: null, changes, metadata }))
  emitter.emit('show-spending-password-modal', { action: 'submit-pool-change', data })
}

async function submitPoolChange(request: PoolChangeRequest, password: string) {
  if (poolChangeBusy.value || !(showPoolConfigModal.value || showRelaysModal.value || showMetadataModal.value) || request.requestId !== poolRequestId || nodeChainUnavailable(request.nodeId)) return
  poolChangeBusy.value = true
  try {
    const result = await store.socketRequest<PoolRegistrationResult>('/jormanager/leios/registration/submit', 'leios_registration_submitted', { ...request, spendingPassword: password })
    if (result.requestId !== request.requestId || result.nodeId !== request.nodeId || result.action !== 'update') throw new Error('Submission target does not match')
    if (!['submitted', 'confirmed'].includes(result.state)) throw new Error(`Transaction outcome: ${result.state}`)
    if (poolRequestId === request.requestId) {
      showPoolConfigModal.value = false
      showRelaysModal.value = false
      showMetadataModal.value = false
      poolRequestId = generateUUID()
    }
    store.requestNodes()
    store.toastSuccess = { title: 'Pool update submitted', message: result.txId }
  } catch (error) {
    store.toastError = { title: 'Pool update failed', message: error instanceof Error ? error.message : String(error) }
  } finally {
    poolChangeBusy.value = false
  }
}

const retirePoolForm = ref({
  id: 0,
  retireFeesAccount: null as number | null,
  retireEpoch: 0 as number | string
})
watch(retirePoolForm, () => { retirementRequestId = generateUUID() }, { deep: true, flush: 'sync' })

// Select options
const feeAccountOptions = computed(() => store.registrationFeesSelectOptions())
const retireFeeAccountOptions = computed(() => store.reregistrationFeesSelectOptions())
const stakingAccountOptions = computed(() => store.stakingSelectOptions())
const rewardsAccountOptions = computed(() => store.rewardsSelectOptions())

const hasCoreNodes = computed(() => {
  return nodes.value.some((node) => node.type !== 'relay' && !node.isDeleted)
})

const roleFilters = computed(() => {
  const all = displayNodes.value
  return [
    { value: 'all' as const, label: 'All', count: all.length },
    { value: 'core' as const, label: 'Core', count: all.filter(n => n.type === 'core').length },
    { value: 'pool' as const, label: 'Pool', count: all.filter(n => n.type === 'pool').length },
    { value: 'relay' as const, label: 'Relay', count: all.filter(n => n.type === 'relay').length }
  ]
})

const filteredNodes = computed(() => {
  const list = displayNodes.value
  const query = searchQuery.value.trim().toLowerCase()
  return list.filter(item => {
    const matchRole = roleFilter.value === 'all' || item.type === roleFilter.value
    const matchQuery = !query || [item.name, item.host, item.poolId].some(v => String(v || '').toLowerCase().includes(query))
    return matchRole && matchQuery
  })
})

const fields = [
  { key: 'name', label: 'Node / Pool ID', sortable: true },
  { key: 'type', label: 'Role', sortable: true },
  { key: 'host', label: 'Host', sortable: true },
  { key: 'kesExpireTimeSec', label: 'KES Expiry' },
  { key: 'edit', label: 'Operations', thClass: 'text-end', tdClass: 'text-end' }
]

function truncatePoolId(poolId?: string | null): string {
  if (!poolId || poolId.length < 18) return poolId || ''
  return `${poolId.slice(0, 10)}…${poolId.slice(-5)}`
}

function formatKesExpiry(timestamp: number): string {
  const date = moment.unix(timestamp)
  return `${date.format('YYYY-MM-DD h:mma UTCZ')} (${date.fromNow()})`
}

function kesPillClass(timestamp: number): string {
  const daysLeft = (timestamp - Math.floor(Date.now() / 1000)) / 86400
  if (daysLeft <= 5) return 'jm-pill-rose'
  if (daysLeft <= 20) return 'jm-pill-amber'
  return 'jm-pill-emerald'
}

function openColorModal(nodeId: number, currentColor: string) {
  editColorForm.value = { nodeId, color: currentColor }
  showColorModal.value = true
}

function handleSaveColor() {
  store.updateNodeColor({ id: editColorForm.value.nodeId, color: editColorForm.value.color })
  showColorModal.value = false
}

function openPoolConfigModal(nodeId: number) {
  const node = find(nodes.value, { id: nodeId })
  if (node) {
    editPoolConfigForm.value = {
      nodeId,
      registrationFeesAccount: node.registrationFeesAccountId || null,
      ownerStakingAccount: node.ownerStakingAccountId || null,
      rewardsStakingAccount: node.rewardsStakingAccountId || null,
      poolPledge: node.poolPledge ?? 0,
      poolCost: node.poolCost ?? 0,
      poolMargin: node.poolMargin ?? 0
    }
  }
  poolConfigStep.value = 1
  replacePoolOwners.value = false
  selectedPoolOwners.value = []
  changePoolReward.value = false
  showPoolConfigModal.value = true
}

function handleSavePoolConfig() {
  if (poolConfigStep.value === 1) {
    poolConfigStep.value = 2
    return
  }
  const pledge = String(editPoolConfigForm.value.poolPledge)
  const cost = String(editPoolConfigForm.value.poolCost)
  const margin = String(editPoolConfigForm.value.poolMargin)
  if (!/^(0|[1-9]\d*)$/.test(pledge) || !/^(0|[1-9]\d*)$/.test(cost) || !/^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(margin)) {
    store.toastError = { title: 'Pool review', message: 'Use whole lovelace amounts and a margin between 0 and 1.' }
    return
  }
  const changes: Record<string, unknown> = { pledge, cost, margin }
  if (replacePoolOwners.value) {
    if (selectedPoolOwners.value.length === 0) {
      store.toastError = { title: 'Pool review', message: 'Select at least one replacement owner account.' }
      return
    }
    changes.ownerAccountIds = [...selectedPoolOwners.value]
  }
  if (changePoolReward.value) {
    if (editPoolConfigForm.value.rewardsStakingAccount == null) {
      store.toastError = { title: 'Pool review', message: 'Select the replacement reward account.' }
      return
    }
    changes.rewardAccountId = editPoolConfigForm.value.rewardsStakingAccount
  }
  requestPoolChange(editPoolConfigForm.value.nodeId, editPoolConfigForm.value.registrationFeesAccount, changes)
}

function handleCancelPoolConfig() {
  if (poolConfigStep.value === 2) {
    poolConfigStep.value = 1
  } else {
    showPoolConfigModal.value = false
    poolConfigStep.value = 1
  }
}

watch(() => store.editorMetadata, value => {
  if (!showMetadataModal.value || !value || typeof value !== 'object' || !('nodeId' in value) || value.nodeId !== editMetadataForm.value.nodeId || !('metadata' in value)) return
  const response = value as LoadedMetadata
  if (!response.metadata || typeof response.metadata !== 'object') return
  loadedMetadata = response
  const metadata = response.metadata
  const info = response.extendedMetadata?.info
  if (editMetadataForm.value.ticker || editMetadataForm.value.name || editMetadataForm.value.description || editMetadataForm.value.homepage || editMetadataForm.value.location || editMetadataForm.value.twitter) return
  editMetadataForm.value = {
    nodeId: editMetadataForm.value.nodeId,
    ticker: metadata.ticker || '', name: metadata.name || '', description: metadata.description || '',
    homepage: metadata.homepage || '', location: info?.location || '', twitter: info?.social?.twitterHandle || ''
  }
})

function openMetadataModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  loadedMetadata = null
  store.editorMetadata = null
  editMetadataForm.value = { nodeId, ticker: '', name: '', description: '', homepage: '', location: '', twitter: '' }
  showMetadataModal.value = true
  store.requestMetadata(nodeId)
}

function handleSaveMetadata() {
  const { nodeId, ticker, name, description, homepage, location, twitter } = editMetadataForm.value
  if (!ticker.trim() || !name.trim() || !description.trim() || !homepage.trim()) {
    store.toastError = { title: 'Pool update', message: 'Complete the required metadata fields.' }
    return
  }
  const current = loadedMetadata?.extendedMetadata
  const info = current?.info
  const social = info?.social
  const extended = { itn: null, telegramAdminHandle: current?.telegramAdminHandle?.[0] ?? null, adapoolsVerify: current?.adapoolsVerify ?? null,
    info: { icon64: info?.urlPngIcon64x64 ?? null, logo: info?.urlPngLogo ?? null, location,
      social: { twitter, telegram: social?.telegramHandle ?? null, facebook: social?.facebookHandle ?? null,
        youtube: social?.youtubeHandle ?? null, twitch: social?.twitchHandle ?? null,
        discord: social?.discordHandle ?? null, github: social?.githubHandle ?? null },
      company: info?.company ? { name: info.company.name, addr: info.company.addr, city: info.company.city, country: info.company.country, company_id: info.company.companyId, vat_id: info.company.vatId } : null,
      about: info?.about ?? null, rss: info?.rss ?? null } }
  requestPoolChange(nodeId, poolChangeFeeAccount.value, {}, { ticker, name, description, homepage, extended })
}

function openRelaysModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  const node = find(nodes.value, { id: nodeId })
  editRelaysForm.value = {
    nodeId,
    relays: node?.relays?.map((r) => ({ addr: r.addr, port: r.port })) || []
  }
  relaysStep.value = 1
  showRelaysModal.value = true
}

function handleSaveRelays() {
  if (relaysStep.value === 1) {
    relaysStep.value = 2
    return
  }
  const relays: Record<string, unknown>[] = []
  for (const relay of editRelaysForm.value.relays) {
    const address = relay.addr.trim()
    const port = Number(relay.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      store.toastError = { title: 'Pool review', message: 'Each relay needs a valid port.' }
      return
    }
    if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) {
      if (address.split('.').some(part => Number(part) > 255)) {
        store.toastError = { title: 'Pool review', message: 'Invalid IPv4 relay address.' }
        return
      }
      relays.push({ 'single host address': { port, IPv4: address, IPv6: null } })
    } else if (/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(address)) {
      relays.push({ 'single host name': { port, dnsName: address } })
    } else {
      store.toastError = { title: 'Pool review', message: 'Use an IPv4 address or DNS name for each relay.' }
      return
    }
  }
  requestPoolChange(editRelaysForm.value.nodeId, poolChangeFeeAccount.value, { relays })
}

function handleCancelRelays() {
  if (relaysStep.value === 2) {
    relaysStep.value = 1
  } else {
    showRelaysModal.value = false
    relaysStep.value = 1
  }
}

function openRetireModal(nodeId: number) {
  retirementError.value = ''
  retirePoolForm.value = {
    id: nodeId,
    retireFeesAccount: null,
    retireEpoch: 0
  }
  showRetireModal.value = true
}

function requestRetirement() {
  const { id: nodeId, retireFeesAccount: feePayerAccountId, retireEpoch } = retirePoolForm.value
  if (retirementBusy.value || nodeChainUnavailable(nodeId)) return
  if (feePayerAccountId === null || !/^[1-9]\d*$/.test(String(retireEpoch))) {
    retirementError.value = 'Select a fee account and a valid retirement epoch.'
    return
  }
  emitter.emit('show-spending-password-modal', { action: 'submit-pool-retirement', data: {
    requestId: retirementRequestId, nodeId, feePayerAccountId, retireEpoch: String(retireEpoch)
  } })
}

async function submitRetirement(request: { requestId: string; nodeId: number; feePayerAccountId: number; retireEpoch: string }, password: string) {
  if (retirementBusy.value || !showRetireModal.value || request.requestId !== retirementRequestId || nodeChainUnavailable(request.nodeId)) return
  retirementBusy.value = true
  retirementError.value = ''
  try {
    const result = await store.socketRequest<PoolRetirementResult>('/jormanager/pool/retirement/submit', 'pool_retirement_submit', { ...request, spendingPassword: password })
    if (result.requestId !== request.requestId || result.nodeId !== request.nodeId) throw new Error('Submission target does not match')
    if (!['submitted', 'confirmed'].includes(result.state)) throw new Error(`Transaction outcome: ${result.state}`)
    if (retirementRequestId === request.requestId) {
      showRetireModal.value = false
      retirementRequestId = generateUUID()
    }
    store.toastSuccess = { title: 'Pool retirement submitted', message: result.txId }
  } catch (error) {
    retirementError.value = error instanceof Error ? error.message : String(error)
  } finally {
    retirementBusy.value = false
  }
}

function copyToClipboard(value?: string | null) {
  if (!value) return
  navigator.clipboard.writeText(value).then(
    () => { store.toastInfo = { title: 'Pool ID', message: 'Copied to clipboard' } },
    () => { store.toastError = { title: 'Pool ID', message: 'Copy to clipboard failed.' } }
  )
}

function rotateKesKey(nodeName: string) {
  emitter.emit('show-spending-password-modal', { action: 'rotate-kes', data: nodeName })
}

function restartNode(nodeName: string) {
  store.restartNodeByName(nodeName)
}

function addPool(parentId: number) {
  poolParentId.value = parentId
  showAddNodeWizard.value = true
}

type RetirementRequest = { requestId: string; nodeId: number; feePayerAccountId: number; retireEpoch: string }

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  switch (data.action) {
    case 'submit-pool-change':
      void submitPoolChange(data.originalData as PoolChangeRequest, data.password)
      break
    case 'submit-pool-retirement':
      void submitRetirement(data.originalData as RetirementRequest, data.password)
      break
    case 'rotate-kes':
      store.rotateKesByName({ name: String(data.originalData), spendingPassword: data.password })
      break
  }
}

onMounted(() => {
  store.requestNodes()
  store.requestHosts()
  store.fetchWalletItems()
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.jm-search-wrap {
  position: relative;
  min-width: 240px;
  flex: 0 1 300px;
}

.jm-search-icon {
  position: absolute;
  left: 0.72rem;
  top: 50%;
  transform: translateY(-50%);
  color: var(--jm-text-muted);
  font-size: 0.8rem;
  pointer-events: none;
}

.jm-search-input {
  padding-left: 2.1rem;
}

.jm-color-swatch-btn {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: none;
  padding: 0;
  display: inline-grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: transform 0.15s ease;
}

.jm-color-swatch-btn:hover {
  transform: scale(1.15);
}

.jm-default-check {
  font-size: 0.58rem;
  color: #041320;
}

.jm-mini-copy {
  width: 22px;
  height: 22px;
  font-size: 0.72rem;
}

.jm-preset-swatch {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 0.14s ease, border-color 0.14s ease;
}

.jm-preset-swatch:hover {
  transform: scale(1.1);
}

.jm-preset-swatch.selected {
  border-color: #ffffff;
  box-shadow: 0 0 0 2px rgba(56, 189, 248, 0.55);
}
</style>
