package com.ncm.app.data.api

import com.google.gson.JsonObject
import retrofit2.http.*

interface NeteaseApi {

    @GET("api/v2/banner/get")
    suspend fun getBanners(
        @Query("clientType") clientType: String = "pc"
    ): JsonObject

    @GET("api/personalized/playlist")
    suspend fun getPersonalizedPlaylists(
        @Query("limit") limit: Int = 6
    ): JsonObject

    @GET("api/playlist/list")
    suspend fun getTopPlaylists(
        @Query("limit") limit: Int = 30,
        @Query("order") order: String = "hot",
        @Query("cat") category: String = "全部",
        @Query("offset") offset: Int = 0,
        @Query("total") total: Boolean = true
    ): JsonObject

    @GET("api/toplist/detail")
    suspend fun getToplistDetail(): JsonObject

    @GET("api/program/recommend/v1")
    suspend fun getRecommendedPrograms(
        @Query("limit") limit: Int = 30
    ): JsonObject

    @GET("api/search/get/web")
    suspend fun search(
        @Query("s") keywords: String,
        @Query("type") type: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): JsonObject

    @GET("api/song/detail")
    suspend fun getSongDetail(
        @Query("ids") ids: String
    ): JsonObject

    @GET("api/song/enhance/player/url")
    suspend fun getSongUrl(
        @Query("ids") ids: String,
        @Query("br") br: Int = 128000
    ): JsonObject

    @GET("api/song/lyric")
    suspend fun getLyric(
        @Query("id") id: Long,
        @Query("lv") lv: Int = -1,
        @Query("kv") kv: Int = -1,
        @Query("tv") tv: Int = -1
    ): JsonObject

    @FormUrlEncoded
    @POST("api/radio/like")
    suspend fun likeSong(
        @Field("alg") alg: String,
        @Field("trackId") songId: Long,
        @Field("like") like: Boolean,
        @Field("time") timestamp: Long
    ): JsonObject

    @FormUrlEncoded
    @POST("api/song/like/get")
    suspend fun getLikedSongIds(
        @Field("uid") userId: Long,
        @Field("timestamp") timestamp: Long
    ): JsonObject

    @GET("api/v6/playlist/detail")
    suspend fun getPlaylistDetail(
        @Query("id") id: Long,
        @Query("n") count: Int = 100000,
        @Query("s") subscribers: Int = 8
    ): JsonObject

    @GET("api/nuser/account/get")
    suspend fun getUserAccount(): JsonObject

    @GET("api/user/playlist")
    suspend fun getUserPlaylists(
        @Query("uid") userId: Long,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): JsonObject

    @FormUrlEncoded
    @POST("api/playlist/manipulate/tracks")
    suspend fun manipulatePlaylistTracks(
        @Field("op") operation: String,
        @Field("pid") playlistId: Long,
        @Field("trackIds") trackIds: String,
        @Field("imme") immediate: String = "true",
        @Field("timestamp") timestamp: Long = System.currentTimeMillis()
    ): JsonObject

    @GET("api/v1/user/detail/{uid}")
    suspend fun getUserDetail(
        @Path("uid") userId: Long
    ): JsonObject
}
