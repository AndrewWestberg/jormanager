package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Social(
    @param:Json(name = "twitter_handle")
    val twitterHandle: String? = null,
    @param:Json(name = "telegram_handle")
    val telegramHandle: String? = null,
    @param:Json(name = "facebook_handle")
    val facebookHandle: String? = null,
    @param:Json(name = "youtube_handle")
    val youtubeHandle: String? = null,
    @param:Json(name = "twitch_handle")
    val twitchHandle: String? = null,
    @param:Json(name = "discord_handle")
    val discordHandle: String? = null,
    @param:Json(name = "github_handle")
    val githubHandle: String? = null
)
