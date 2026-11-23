# Mirai 2.16 对 OneBot v11 的能力缺口报告

> 用途：给 Mirai / 协议实现方反馈「OneBot 需要、但 mirai 2.16 公开 API 没有」的能力。
> 基线：onebot-mirai `0.2.3`（fork `Desom-fu/onebot-mirai`），mirai-core-api 2.16.0，运行环境为鸿蒙协议发行版 `mirai-hmos-2.16.0`。
> 说明：本文只列**公开 API 缺口**；协议本身是否允许（频道、讨论组、Web Cookie 等）由 Mirai 维护方判断。

## 0. 结论摘要

- 插件动作注册表共 **120 个动作名**（含别名），大部分可用；其中 **36 个动作名（约 29 项能力）**因 mirai 2.16 缺公开 API 而明确返回失败，另有 **10 余个只能部分降级**。
- 缺口分三类：
  1. **协议/产品能力缺失**：QQ 频道、讨论组、Web Cookies/CSRF、在线状态、改资料/头像等；
  2. **Mirai 未封装的常规能力**：点赞、表情回应、群签到、群头像、@全体剩余次数、群文件永久/私聊文件、群系统消息、已读上报等；
  3. **事件缺失**：群文件上传、红包运气王、消息表情回应、精华变更、离线文件等。

## 1. 完全缺失的 OneBot 动作

| OneBot 动作 | 期望行为 | mirai 2.16 公开 API 现状 | 影响 |
|---|---|---|---|
| `send_like` | 给好友资料卡点赞（times 次） | `Friend` / `User` 无 `sendLike` 类方法 | 点赞类插件不可用 |
| `set_msg_emoji_like` / `unset_msg_emoji_like` / `set_group_reaction` | 给消息贴/取消表情回应 | 无 `MessageSource.addReaction` / `MessageReaction` 公开 API | 表情回应类交互不可用 |
| `send_group_sign` | 群打卡/签到 | `Group` 无 `signIn` / `getSignInInfo` | 签到类插件不可用 |
| `set_group_portrait` | 修改群头像 | `Group` 无 `setAvatar(ExternalResource)` | 群头像管理不可用 |
| `set_group_file_forever` | 群文件转永久 | `AbsoluteFile` 只有 `getExpiryTime()`，无 setter | 文件过期管理不可用 |
| `upload_private_file` / `get_private_file_url` | 私聊文件上传/取链接 | `Friend` 未实现 `FileSupported`，无文件 API | 私聊文件不可用 |
| `get_cookies` / `get_csrf_token` / `get_credentials` | 取 Web 端 Cookie/CSRF/凭证 | 无对应公开 API（协议层能力） | 依赖 Web 凭证的插件不可用 |
| `set_input_status` | 设置「正在输入」 | 无公开设置 API | 输入状态类交互不可用 |
| `set_qq_profile` / `set_qq_avatar` / `set_self_longnick` / `set_online_status` | 改昵称/头像/签名/在线状态 | `Bot` 无对应 setter | 机器人资料管理不可用 |
| `_get_model_show` / `_set_model_show` | 在线机型显示 | 无公开 API | 机型伪装类插件不可用 |
| `get_unidirectional_friend_list` / `delete_unidirectional_friend` | 单向好友查询/删除 | 无对应公开 API | 单向好友管理不可用 |
| `get_doubt_friends_add_request` / `set_doubt_friends_add_request` | 被过滤好友请求 | mirai 无「可疑好友请求」概念 | 该类请求不可处理 |
| `ocr_image` | 图片 OCR | 无 OCR 依赖/公开 API | OCR 类插件不可用 |
| `send_discuss_msg` / `set_discuss_leave` | 讨论组消息/退组 | mirai 无讨论组模型 | 讨论组不可用 |
| `send_guild_channel_msg` / `get_guild_member_profile` / `get_guild_service_profile` / `get_guild_list` / `get_guild_msg` / `get_guild_meta_by_guest` / `get_guild_channel_list` / `get_guild_member_list` / `get_topic_channel_feeds` | QQ 频道全套 | mirai 2.16 无频道 API | 频道不可用 |

## 2. 只能部分实现 / 降级的动作

| OneBot 动作                                                                                        | 降级情况                                                      | 原因                                                                     |
| ------------------------------------------------------------------------------------------------ | --------------------------------------------------------- | ---------------------------------------------------------------------- |
| `get_group_at_all_remain`                                                                        | `can_at_all` 按 bot 权限推断；两个剩余次数返回 `-1`                     | 无「@全体剩余次数」查询 API                                                       |
| `get_group_system_msg`                                                                           | 只能返回本次运行缓存的待处理加群/邀请请求                                     | 无群系统消息历史 API                                                           |
| `get_group_ignore_add_request`                                                                   | 只能返回本次运行记录的被忽略请求                                          | 无持久化/官方忽略列表 API                                                        |
| `get_group_file_system_info`                                                                     | `file_count` 可算；`limit_count/used_space/total_space` 返回 0 | 无群文件配额 API                                                             |
| `get_group_info`                                                                                 | `max_member_count=0`、`group_memo=""`                      | 无群人数上限/群简介公开 getter（`entranceAnnouncement` 在字节码里是 synthetic，Java 不可调用） |
| `set_group_leave`                                                                                | `is_dismiss=true` 明确失败                                    | `Group.quit()` 不支持解散群                                                  |
| `set_group_special_title`                                                                        | `duration` 被忽略                                            | API 只有 `setSpecialTitle(String)`                                       |
| `get_stranger_info`                                                                              | `qid`、`login_days` 返回默认值                                  | `UserProfile` 无对应字段                                                    |
| `send_group_notice`                                                                              | 文本/置顶/确认等可用；部分 NapCat 扩展参数忽略                              | `AnnouncementParameters` 覆盖面有限                                         |
| `mark_msg_as_read` / `_mark_all_as_read` / `mark_private_msg_as_read` / `mark_group_msg_as_read` | 返回成功但为 no-op                                              | 无公开已读上报 API（鸿蒙协议内部会自动上报）                                               |
| `get_recent_contact`                                                                             | 只统计插件启动后的内存记录                                             | 无最近联系人查询 API                                                           |
| `get_group_msg_history` / `get_friend_msg_history`                                               | 通过 `RoamingMessages` 取，数量/范围受协议漫游限制                       | 无完整历史消息 API                                                            |
| `trans_group_file`                                                                               | 需要显式 `target_group_id`，否则失败                               | 无跨群转存 API                                                              |
| `get_image` / `get_record`                                                                       | 依赖插件自己的图片/语音缓存与 URL                                       | mirai 没有统一媒体库查询 API                                                    |
| `set_group_anonymous_ban`                                                                        | 依赖匿名 flag 格式（`anonymousId&nameCard`）                      | mirai 匿名成员信息有限                                                         |

## 3. 缺失的事件

| OneBot 事件 | 说明 | mirai 2.16 现状 |
|---|---|---|
| `notice.group_upload` | 群文件上传通知 | 无 `GroupFileUploadEvent` |
| `notice.notify.lucky_king` | 红包运气王 | 无红包事件 |
| `notice.group_msg_emoji_like` | 消息表情回应通知 | 无 reaction 事件 |
| `notice.essence` | 群精华变更通知 | 无精华变更事件 |
| `notice.offline_file` | 离线文件通知 | 无离线文件事件 |
| `notice.group_name` | 群名变更（OneBot 非标准，插件已用 `GroupNameChangeEvent` 补上） | mirai 有 `GroupNameChangeEvent` |
| `message_sent` | 自身消息事件 | mirai 有 `MessagePostSendEvent`，已实现 |
| `meta_event.lifecycle` / `heartbeat` | 生命周期/心跳 | 已用 `BotOnline/BotOffline` + 定时器实现 |
| 频道事件全套 | 频道消息/成员/反应等 | mirai 2.16 无频道 API |

## 4. 建议 Mirai 增加的公开 API（按优先级）

### P0：OneBot 常用，且有明确协议语义

1. `Friend.sendLike(int times)` / `User.sendLike(int times)`
2. `MessageSource.addReaction(int emojiId)`、`removeReaction(int emojiId)`，以及 `MessageReactionEvent`
3. `Group.signIn()` / `Group.getSignInInfo()`
4. `Group.setAvatar(ExternalResource)`
5. `AbsoluteFile.setExpiryTime(long)` / `setForever()`
6. `Friend` 实现 `FileSupported`（或 `Friend.uploadFile(...)`）
7. `Group.getAtAllRemain()` → `{canAtAll, remainForGroup, remainForUin}`
8. `Group.getFileSystemInfo()` → `{fileCount, limitCount, usedSpace, totalSpace}`
9. `Group.getMaxMemberCount()` / `Group.getMemo()`（或把 `entranceAnnouncement` 从 synthetic 改为正常公开 getter）
10. `Group.getSystemMessages()` / `Group.getIgnoredJoinRequests()`（至少返回当前待处理列表）

### P1：事件与细节

11. `GroupFileUploadEvent`（群文件上传）
12. `GroupLuckyKingEvent`（红包运气王）
13. `MessageReactionEvent`（表情回应）
14. `GroupEssenceChangeEvent`（精华变更）
15. `OfflineFileEvent`（离线文件）
16. `Group.setSpecialTitle(String, int durationSeconds)`
17. `MessageSource.markAsRead()` / `Bot.markAsRead(...)`
18. `Group.quit(boolean dismiss)` 或 `Group.dismiss()`

### P2：协议/产品能力（可能超出 Mirai 范围，供评估）

19. `Bot.getCookies(String domain)` / `getCsrfToken()` / `getCredentials(String domain)`
20. `Bot.setNick(String)` / `setAvatar(ExternalResource)` / `setLongNick(String)` / `setOnlineStatus(...)`
21. QQ 频道 API 全套
22. 讨论组模型（若仍要兼容旧协议）
23. 单向好友查询/删除
24. 可疑好友请求（doubt friends）管理
25. OCR（可作为可选扩展，不必进 core）

## 5. 不是 Mirai 缺口的项（已用现有 API 实现）

- 群荣誉：`Group.getActive().queryHonorHistory(GroupHonorType)`（Kotlin `runBlocking` 桥）。
- 群历史：`Group.getRoamingMessages().getAllMessagesStream()` / `Friend.getRoamingMessages()`。
- 群文件：`Group.getFiles().getRoot()` + `AbsoluteFolder` 的 `filesStream/foldersStream/uploadNewFile/resolveFileById`。
- 群公告：`Group.getAnnouncements()` 的 `toList/publish/delete/uploadImage`。
- 群精华：`Group.getEssences()` 的 `getPage/share/remove`。
- 戳一戳：`Member.nudge().sendTo(group)` / `Friend.nudge().sendTo(friend)`。
- 骰子/猜拳：`Dice` / `RockPaperScissors`。
- 合并转发：`ForwardMessage` / `ForwardMessage.Node` / `Mirai.downloadForwardMessage`。
- 自身消息事件：`MessagePostSendEvent`。
- 用户资料：`Mirai.getInstance().queryProfile(bot, userId)` 可用；onebot-mirai 已加 `ProfileCache`（10 分钟 TTL、4096 条上限），避免逐条网络查询。

## 6. 证据与复现

- 编译/运行基线：`mirai-core-api-jvm-2.16.0.jar`、`mirai-core-all-2.16.0-all.jar`。
- 用 `javap` 检查过的关键类：
  - `net.mamoe.mirai.contact.Friend`（无 `sendLike`、未实现 `FileSupported`）
  - `net.mamoe.mirai.contact.Group`（无 `signIn`、`setAvatar`、`getAtAllRemain`、`getFileSystemInfo`）
  - `net.mamoe.mirai.contact.file.AbsoluteFile`（无过期时间 setter）
  - `net.mamoe.mirai.message.data.MessageSource`（无 reaction / markAsRead）
  - `net.mamoe.mirai.event.events.*`（无 `GroupFileUploadEvent`、`GroupLuckyKingEvent`、reaction/essence/offline-file 事件）
- 插件对缺口动作统一返回：`status=failed`、`retCode=1400`、`data/wording/message` 为中文原因，不会伪装成功。

