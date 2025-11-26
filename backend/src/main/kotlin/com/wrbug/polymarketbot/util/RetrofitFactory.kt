package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.PolymarketClobApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit 客户端工厂
 * 用于创建带认证的 Polymarket CLOB API 客户端和 Ethereum RPC API 客户端
 */
@Component
class RetrofitFactory(
    @Value("\${polymarket.clob.base-url}")
    private val clobBaseUrl: String
) {
    
    /**
     * 创建带认证的 Polymarket CLOB API 客户端
     * @param apiKey API Key
     * @param apiSecret API Secret
     * @param apiPassphrase API Passphrase
     * @param walletAddress 钱包地址（用于 POLY_ADDRESS 请求头）
     * @return PolymarketClobApi 客户端
     */
    fun createClobApi(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): PolymarketClobApi {
        val authInterceptor = PolymarketAuthInterceptor(apiKey, apiSecret, apiPassphrase, walletAddress)
        
        val okHttpClient = createClient()
            .addInterceptor(authInterceptor)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(clobBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PolymarketClobApi::class.java)
    }
    
    /**
     * 创建 Ethereum RPC API 客户端
     * @param rpcUrl RPC 节点 URL
     * @return EthereumRpcApi 客户端
     */
    fun createEthereumRpcApi(rpcUrl: String): EthereumRpcApi {
        val okHttpClient = createClient().build()
        
        return Retrofit.Builder()
            .baseUrl(rpcUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EthereumRpcApi::class.java)
    }
}

