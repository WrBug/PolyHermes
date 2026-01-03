package com.wrbug.polymarketbot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PolymarketBotApplication

fun main(args: Array<String>) {
    runApplication<PolymarketBotApplication>(*args)
}

