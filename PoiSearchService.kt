package com.example.ingress

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// POI搜索响应数据结构
data class PoiResponse(
    val status: String,
    val info: String,
    val pois: List<Poi>?
)

data class Poi(
    val id: String,
    val name: String,
    val type: String,
    val location: String, // 格式："经度,纬度"
    val address: String
)

// 高德Web服务API接口
interface PoiSearchService {
    @GET("v3/place/around")
    suspend fun searchAround(
        @Query("key") key: String,
        @Query("location") location: String, // 格式："经度,纬度"
        @Query("keywords") keywords: String = "",
        @Query("types") types: String, // POI类型编码
        @Query("radius") radius: Int = 2000, // 搜索半径（米）
        @Query("offset") offset: Int = 50 // 返回数量
    ): PoiResponse

    companion object {
        private const val BASE_URL = "https://restapi.amap.com/"

        fun create(): PoiSearchService {
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(PoiSearchService::class.java)
        }
    }
}