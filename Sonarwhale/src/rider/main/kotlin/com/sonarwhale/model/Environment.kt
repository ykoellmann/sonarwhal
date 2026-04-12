package com.sonarwhale.model

import java.util.UUID

data class Environment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val variables: LinkedHashMap<String, String> = LinkedHashMap()
)
