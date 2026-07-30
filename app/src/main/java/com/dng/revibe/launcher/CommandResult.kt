package com.dng.revibe.launcher

/**
 * Shell 命令执行结果
 */
data class CommandResult(
    val stdout: String,
    val stderr: String,
    val statusCode: Int
)
