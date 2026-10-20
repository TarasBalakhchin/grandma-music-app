package com.grandman.music

import java.io.File

data class LocalTrack(
    val title: String,
    val filePath: String,
    val file: File
)
