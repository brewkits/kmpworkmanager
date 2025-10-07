package com.example.kmpworkmanagerv2

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform