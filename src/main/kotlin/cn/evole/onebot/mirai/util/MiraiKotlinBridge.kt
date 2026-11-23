package cn.evole.onebot.mirai.util

import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.contact.active.ActiveHonorList
import net.mamoe.mirai.contact.active.GroupActive
import net.mamoe.mirai.data.GroupHonorType

/**
 * Kotlin 协程桥接工具：为 mirai 2.16 中仅提供 suspend 函数的公开 API 提供阻塞调用封装。
 */
object MiraiKotlinBridge {
    @JvmStatic
    fun queryHonorHistory(active: GroupActive, typeId: Int): ActiveHonorList {
        return runBlocking {
            active.queryHonorHistory(GroupHonorType(typeId))
        }
    }
}
