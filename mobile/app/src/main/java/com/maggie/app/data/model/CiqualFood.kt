package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CiqualFood(
    @SerialName("alim_code") val alimCode: String,
    @SerialName("alim_name_fr") val alimNameFr: String,
    @SerialName("alim_group_name_fr") val alimGroupNameFr: String? = null,
    @SerialName("alim_ssgroup_name_fr") val alimSsgroupNameFr: String? = null,
)
