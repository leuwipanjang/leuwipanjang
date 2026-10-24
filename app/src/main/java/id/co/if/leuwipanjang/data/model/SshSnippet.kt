package id.co.if.leuwipanjang.data.model

import java.util.UUID

data class SshSnippet(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var command: String,
    var description: String? = null
)
