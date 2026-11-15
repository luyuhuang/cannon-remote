package com.cannonremote.app.ble

import java.util.UUID

/**
 * 佳能 BR-E1 蓝牙遥控协议（参考逆向工程实现：
 * pklaus/canoremote、ids1024/cannon-bluetooth-remote、iebyt/cbremote、
 * iandouglasscott.com 的协议逆向文章）。
 *
 * 相机把蓝牙功能设为 Remote 后会广播遥控服务：
 * - 配对特征：写入 `[0x03] + 遥控器名字(ASCII)` 即完成应用层配对
 * - 事件特征：写入 1 字节按键指令（Write Without Response）
 *
 * 指令字节 = 模式位 | 按键位；只写模式字节（0x0C）表示“松开所有按键”。
 */
object CanonProtocol {

    /** BR-E1 遥控服务 UUID（佳能相机广播中会包含该 UUID） */
    val SERVICE_REMOTE: UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821")

    /** 配对特征：写入 [0x03]+名字 完成配对 */
    val CHARACTERISTIC_PAIRING: UUID = UUID.fromString("00050002-0000-1000-0000-d8492fffa821")

    /** 事件特征：写入 1 字节按键指令 */
    val CHARACTERISTIC_EVENT: UUID = UUID.fromString("00050003-0000-1000-0000-d8492fffa821")

    // 模式位（bit2-3）
    const val MODE_IMMEDIATE = 0b00001100 // 立即拍摄
    const val MODE_DELAY = 0b00000100 // 相机 2 秒自拍
    const val MODE_MOVIE = 0b00001000 // 视频模式

    // 按键位（bit4-7）
    const val BUTTON_RELEASE = 0b10000000 // 快门
    const val BUTTON_FOCUS = 0b01000000 // 对焦 AF
    const val BUTTON_TELE = 0b00100000 // 变焦 +（需电动变焦适配器）
    const val BUTTON_WIDE = 0b00010000 // 变焦 -

    // —— 常用指令（立即拍摄模式）——
    /** 按下快门 0x8C */
    const val SHUTTER_PRESS = MODE_IMMEDIATE or BUTTON_RELEASE

    /** 松开快门 0x0C（= 松开所有按键） */
    const val SHUTTER_RELEASE = MODE_IMMEDIATE

    /** 按下对焦 0x4C */
    const val FOCUS_PRESS = MODE_IMMEDIATE or BUTTON_FOCUS

    /** 松开对焦 0x0C */
    const val FOCUS_RELEASE = MODE_IMMEDIATE

    // —— 视频模式指令 ——
    const val VIDEO_SHUTTER_PRESS = MODE_MOVIE or BUTTON_RELEASE // 0x88 开始/停止录制
    const val VIDEO_SHUTTER_RELEASE = MODE_MOVIE // 0x08

    /** 配对时显示在相机屏幕上的遥控器名字 */
    const val REMOTE_NAME = "CanonRemote"

    /** 佳能 GATT 服务 UUID 的公共后缀，用于识别扫描结果 */
    const val CANON_UUID_SUFFIX = "d8492fffa821"

    /** 构造配对数据包：0x03 + 遥控器名字 */
    fun pairingPayload(name: String = REMOTE_NAME): ByteArray =
        byteArrayOf(0x03) + name.toByteArray(Charsets.US_ASCII)
}
