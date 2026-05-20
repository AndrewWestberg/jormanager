package com.swiftmako.jormanager.utils

import com.swiftmako.jormanager.model.GenesisShelley

object CardanoNetworkEpochs {
    fun byronToShelleyEpochs(shelley: GenesisShelley): Long =
        if (shelley.networkId.equals("testnet", ignoreCase = true)) {
            when (shelley.networkMagic) {
                GUILD_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_GUILD
                VASIL_DEV_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_VASIL_DEV
                PREVIEW_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_PREVIEW
                PREPROD_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_PREPROD
                MIXED_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_MIXED
                REKT_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_REKT
                SANCHO_NETWORK_MAGIC -> BYRON_TO_SHELLEY_EPOCHS_SANCHO
                else -> BYRON_TO_SHELLEY_EPOCHS_TESTNET
            }
        } else {
            BYRON_TO_SHELLEY_EPOCHS_MAINNET
        }

    private const val BYRON_TO_SHELLEY_EPOCHS_MAINNET = 208L
    private const val BYRON_TO_SHELLEY_EPOCHS_TESTNET = 74L
    private const val BYRON_TO_SHELLEY_EPOCHS_GUILD = 2L
    private const val BYRON_TO_SHELLEY_EPOCHS_VASIL_DEV = 1L
    private const val BYRON_TO_SHELLEY_EPOCHS_PREVIEW = 0L
    private const val BYRON_TO_SHELLEY_EPOCHS_PREPROD = 4L
    private const val BYRON_TO_SHELLEY_EPOCHS_MIXED = 0L
    private const val BYRON_TO_SHELLEY_EPOCHS_REKT = 0L
    private const val BYRON_TO_SHELLEY_EPOCHS_SANCHO = 0L

    private const val GUILD_NETWORK_MAGIC = 141L
    private const val VASIL_DEV_NETWORK_MAGIC = 9L
    private const val PREVIEW_NETWORK_MAGIC = 2L
    private const val PREPROD_NETWORK_MAGIC = 1L
    private const val MIXED_NETWORK_MAGIC = 5L
    private const val REKT_NETWORK_MAGIC = 7L
    private const val SANCHO_NETWORK_MAGIC = 4L
}
