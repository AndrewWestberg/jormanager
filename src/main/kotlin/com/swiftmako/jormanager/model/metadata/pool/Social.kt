package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Social(
    @Json(name = "twitter_handle")
    val twitterHandle: String? = null,
    @Json(name = "telegram_handle")
    val telegramHandle: String? = null,
    @Json(name = "facebook_handle")
    val facebookHandle: String? = null,
    @Json(name = "youtube_handle")
    val youtubeHandle: String? = null,
    @Json(name = "twitch_handle")
    val twitchHandle: String? = null,
    @Json(name = "discord_handle")
    val discordHandle: String? = null,
    @Json(name = "github_handle")
    val githubHandle: String? = null
)