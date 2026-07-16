package com.ncm.app

import android.app.Application
import com.ncm.app.data.AppCache
import com.ncm.app.data.SessionManager
import com.ncm.app.data.api.NeteaseApi
import com.ncm.app.data.repository.MusicRepository
import com.ncm.app.ui.theme.AccentThemeSettings
import com.ncm.app.ui.theme.PlayerAppearanceSettings
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NeteaseApp : Application() {

    lateinit var api: NeteaseApi
        private set

    lateinit var repository: MusicRepository
        private set

    lateinit var session: SessionManager
        private set

    lateinit var cache: AppCache
        private set
    lateinit var accentThemeSettings: AccentThemeSettings
        private set
    lateinit var playerAppearanceSettings: PlayerAppearanceSettings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionManager(this)
        cache = AppCache(this)
        accentThemeSettings = AccentThemeSettings(this)
        playerAppearanceSettings = PlayerAppearanceSettings(this)
        cache.removePrefix(AppCache.KEY_PLAYLIST_PREFIX)
        cache.removePrefix(AppCache.KEY_QUICK_PREFIX)
        cache.remove(AppCache.KEY_DISCOVER)
        cache.remove(AppCache.KEY_MY)
        initNetwork()
    }

    private fun initNetwork() {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.163.com/")
                    .apply {
                        if (session.cookie.isNotBlank()) {
                            header("Cookie", session.cookie)
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(NeteaseApi::class.java)
        repository = MusicRepository(api, session)
    }

    companion object {
        lateinit var instance: NeteaseApp
            private set
    }
}
