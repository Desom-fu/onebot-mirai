<div align="center">

<h1>OneBot Mirai</h1> 
欢迎回到Mirai

_✨ 对接[OneBot](https://github.com/howmanybots/onebot/blob/master/README.md)标准的 mirai 插件✨_

</div>
<hr>
<p align="center">
    <a href="https://github.com/cnlimiter/onebot-mirai/issues"><img src="https://img.shields.io/github/issues/cnlimiter/onebot-mirai?style=flat" alt="issues" /></a>
    <a href="https://github.com/cnlimiter/onebot-mirai/blob/master/LICENSE"><img src="https://img.shields.io/badge/license-GPLV3-green" alt="License"/></a>
    <a href="https://github.com/howmanybots/onebot"><img src="https://img.shields.io/badge/OneBot-v11-blue?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABABAMAAABYR2ztAAAAIVBMVEUAAAAAAAADAwMHBwceHh4UFBQNDQ0ZGRkoKCgvLy8iIiLWSdWYAAAAAXRSTlMAQObYZgAAAQVJREFUSMftlM0RgjAQhV+0ATYK6i1Xb+iMd0qgBEqgBEuwBOxU2QDKsjvojQPvkJ/ZL5sXkgWrFirK4MibYUdE3OR2nEpuKz1/q8CdNxNQgthZCXYVLjyoDQftaKuniHHWRnPh2GCUetR2/9HsMAXyUT4/3UHwtQT2AggSCGKeSAsFnxBIOuAggdh3AKTL7pDuCyABcMb0aQP7aM4AnAbc/wHwA5D2wDHTTe56gIIOUA/4YYV2e1sg713PXdZJAuncdZMAGkAukU9OAn40O849+0ornPwT93rphWF0mgAbauUrEOthlX8Zu7P5A6kZyKCJy75hhw1Mgr9RAUvX7A3csGqZegEdniCx30c3agAAAABJRU5ErkJggg==" alt=""></a>
    <a href=""><img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/cnlimiter/onebot-mirai?style=flat&color=abcdef"></a>

</p>


## 声明

- OneBot Mirai 是完全免费且开放源代码的Mirai插件，旨在学习，请勿用于非法用途 

## 兼容性
- OneBot Mirai 兼容 [OneBot-v11](https://github.com/botuniverse/onebot-11) 大部分内容。

### 接口
- [x] 正向 WebSocket
- [x] 反向 WebSocket
- [x] HTTP API
- [x] 反向 HTTP POST

### 实现
<details>
<summary>已实现 CQ 码 / 消息段</summary>

#### 支持的 OneBot v11 CQ 码与消息段

| CQ 码 / 段类型 | 功能 | 说明 |
|---|---|---|
| `[CQ:text]` | 纯文本 | 支持 `&#91;` / `&#93;` / `&#44;` / `&amp;` 反转义 |
| `[CQ:image]` | 图片 / 闪照 | 支持 `base64://`、`data:image/...;base64,`、`http(s)://`、`file://`、本地路径与 mirai 图片 ID；`sub_type=1` 或 `type=flash` 发送闪照 |
| `[CQ:face]` | QQ 表情 | 标准 `Face(id)` |
| `[CQ:mface]` | 商城表情 | mirai 2.16 无公开构造，自动降级为摘要文本或普通表情并记录告警 |
| `[CQ:record]` | 语音 | 支持 `file://`、本地路径、`http(s)://`、`base64://` 与缓存 |
| `[CQ:video]` | 短视频 | 需携带 `thumb` 缩略图参数，缺省返回明确失败说明 |
| `[CQ:at]` | @某人 / @全体成员 | 仅群聊生效（`qq=all` 为 `@全体成员`），私聊自动跳过并告警 |
| `[CQ:share]` | 链接分享 | `RichMessage.share` |
| `[CQ:music]` | 音乐分享 / 自定义分享 | 支持 `qq`、`163`、`custom`（校验必要参数） |
| `[CQ:reply]` | 回复引用 | 从消息缓存 / 本地 DB 取 `MessageSource` |
| `[CQ:forward]` / `[CQ:node]` | 合并转发 | 兼容 NapCat（`user_id`/`nickname`/`message`）与 go-cqhttp（`uin`/`name`/`content`） |
| `[CQ:xml]` / `[CQ:json]` | XML / JSON 卡片 | `SimpleServiceMessage` / `LightApp` |
| `[CQ:contact]` | 联系人推荐 | 支持 `type=qq`；`type=group` 无公开 API，返回失败说明 |
| `[CQ:poke]` / `[CQ:nudge]` / `[CQ:shake]` | 戳一戳 | 支持群/好友戳一戳动作与 `PokeMessage` |
| `[CQ:dice]` / `[CQ:rps]` | 掷骰子 / 猜拳 | `Dice(1-6)` / `RockPaperScissors(1-3)` |
| `[CQ:markdown]` / `[CQ:keyboard]` | Markdown / 按钮 | 通过 `LightApp` 透传合法 JSON |
| `[CQ:location]` | 位置消息 | mirai 2.16 无公开构造，返回明确失败说明 |

</details>

<details>
<summary>已实现 API</summary>

#### OneBot v11 / OlivOS / NapCat 扩展 API（同时支持 HTTP POST / GET 与 WebSocket）

| 类别 | API | 说明 |
|---|---|---|
| 消息 | `send_private_msg` / `send_group_msg` / `send_msg` | 发送私聊（含群临时会话）/ 群聊消息，兼容 CQ 字符串与段数组 |
| 合并转发 | `send_group_forward_msg` / `send_private_forward_msg` / `send_forward_msg` / `get_forward_msg` | 兼容新旧两套节点字段，发送与接收均写入 LRU 缓存供 `get_forward_msg` 取回 |
| 消息管理 | `get_msg` / `delete_msg` / `get_group_msg_history` / `get_friend_msg_history` / `get_recent_contact` | 内存缓存优先、本地 DB 兜底；历史消息使用 mirai `RoamingMessages` |
| 戳一戳 | `group_poke` / `friend_poke` / `send_poke` | 基于 mirai `nudge().sendTo(...)` |
| 群管理 | `set_group_kick` / `set_group_ban` / `set_group_anonymous_ban` / `set_group_whole_ban` / `set_group_admin` / `set_group_anonymous` / `set_group_card` / `set_group_name` / `set_group_leave` / `set_group_special_title` | `is_dismiss=true` 明确返回不支持 |
| 群信息与请求 | `get_group_info` / `get_group_list` / `get_group_member_info` / `get_group_member_list` / `get_group_honor_info` / `get_group_at_all_remain` / `get_group_system_msg` / `get_group_ignore_add_request` / `set_friend_add_request` / `set_group_add_request` | `get_group_honor_info` 使用 Kotlin `runBlocking` 桥调用 `queryHonorHistory` |
| 群文件与下载 | `upload_group_file` / `delete_group_file` / `create_group_file_folder` / `delete_group_folder` / `get_group_file_system_info` / `get_group_root_files` / `get_group_files_by_folder` / `get_group_file_url` / `rename_group_file` / `rename_group_file_folder` / `move_group_file` / `trans_group_file` / `download_file` | 基于 mirai `FileSupported` / `AbsoluteFolder` |
| 群公告与精华 | `get_group_notice`（`_get_group_notice`）/ `send_group_notice`（`_send_group_notice`、`_set_group_notice`）/ `del_group_notice`（`delete_group_notice`、`_delete_group_notice`）/ `set_essence_msg` / `delete_essence_msg` / `get_essence_msg_list` | 支持群公告附带图片上传与精华列表查询 |
| 好友与系统 | `get_login_info` / `get_stranger_info` / `get_friend_list` / `delete_friend` / `set_friend_remark` / `get_online_clients` / `get_status` / `get_version_info` / `can_send_image` / `can_send_record` / `get_image` / `get_record` / `clean_cache` / `set_restart` / `_check_url_safely` / `get_word_slices`（`.get_word_slices`）/ `mark_msg_as_read`（no-op） | `mark_*_as_read` 按 no-op 返回成功并记录日志 |
| 明确不支持（返回 `failed` + 中文 `wording`） | `send_like`、`set_msg_emoji_like`、`unset_msg_emoji_like`、`set_group_reaction`、`set_group_portrait`、`send_group_sign`、`get_doubt_friends_add_request`、`set_doubt_friends_add_request`、`set_group_file_forever`、`upload_private_file`、`get_private_file_url`、`get_cookies`、`get_csrf_token`、`get_credentials`/`get_credentails`、`set_input_status`、`set_qq_profile`、`set_qq_avatar`、`set_self_longnick`、`set_online_status`、`ocr_image`、`send_discuss_msg`、`set_discuss_leave`、`*_guild_*`、`_get_model_show`、`*_unidirectional_friend*` | mirai 2.16 无对应公开 API，明确返回失败原因，不伪造成功 |

> mirai 2.16 公开 API 的完整缺口清单（含建议新增的 API/事件）见 [docs/mirai-2.16-unsupported.md](docs/mirai-2.16-unsupported.md)。

</details>

<details>
<summary>已实现 Event</summary>

#### 符合 OneBot 标准的 Event

| 事件类型 | Event | 状态 |
|------|-----------|------|
| 消息事件 | 私聊消息（`message.private`） | 已支持 |
| 消息事件 | 群消息（`message.group`） | 已支持 |
| 消息事件 | 自身发送消息（`message_sent`） | 已支持 |
| 通知事件 | 群管理员变动（`group_admin`） | 已支持 |
| 通知事件 | 群成员减少（`group_decrease`） | 已支持 |
| 通知事件 | 群成员增加（`group_increase`） | 已支持 |
| 通知事件 | 群禁言（`group_ban`） | 已支持 |
| 通知事件 | 好友添加（`friend_add`） | 已支持 |
| 通知事件 | 群消息撤回（`group_recall`） | 已支持 |
| 通知事件 | 好友消息撤回（`friend_recall`） | 已支持 |
| 通知事件 | 群内 / 好友戳一戳（`notify.poke`） | 已支持 |
| 通知事件 | 群成员荣誉变更（`notify.honor`） | 已支持 |
| 通知事件 | 群成员名片变更（`group_card`） | 已支持 |
| 通知事件 | 群成员特殊头衔变更（`group_title`） | 已支持 |
| 通知事件 | 群名称变更（`group_name`） | 已支持 |
| 通知事件 | 其他客户端在线状态（`client_status`） | 已支持 |
| 请求事件 | 加好友请求（`request.friend`） | 已支持 |
| 请求事件 | 加群请求 / 邀请（`request.group`） | 已支持 |
| 元事件 | 生命周期（`meta_event.lifecycle` `connect`/`disable`） | 已支持 |
| 元事件 | 定时心跳（`meta_event.heartbeat`） | 已支持 |
| 通知事件 | 群文件上传（`group_upload`） | **不支持**（mirai 2.16 无群文件上传事件） |
| 通知事件 | 群红包运气王（`notify.lucky_king`） | **不支持**（mirai 2.16 无红包事件） |

</details>

## 更新日志:
- [CHANGELOG](https://github.com/cnlimiter/onebot-mirai/blob/master/CHANGELOG.md)

## 开源协议
- [AGPL-3.0](LICENSE) © cnlimiter

## 项目引用
- [OneBot标准](https://github.com/howmanybots/onebot)  
- [onebot-kotlin](https://github.com/yyuueexxiinngg/onebot-kotlin)  -  [LICENSE](https://github.com/yyuueexxiinngg/onebot-kotlin/blob/dev/LICENSE)
- [CQHTTP](https://github.com/richardchien/coolq-http-api) -  [LICENSE](https://github.com/richardchien/coolq-http-api/blob/master/LICENSE)
- [go-cqhttp](https://github.com/Mrs4s/go-cqhttp)  -  [LICENSE](https://github.com/Mrs4s/go-cqhttp/blob/master/LICENSE)
- [onebot-sdk](https://github.com/cnlimiter/onebot-sdk)  -  [LICENSE](https://github.com/cnlimiter/onebot-sdk/blob/master/LICENSE)

## 友情链接
兼容 [OneBot-v11 ](https://github.com/howmanybots/onebot)标准的一些项目🥰  

| 项目地址                                                                              | 核心作者           | 备注                                                                   |
|-----------------------------------------------------------------------------------|----------------|----------------------------------------------------------------------|
| [Overflow](https://github.com/MrXiaoM/Overflow)                                   | MrXiaoM        | 实现 mirai 的无缝迁移                                                       |
| [Lagrange.Core](https://github.com/LagrangeDev/Lagrange.Core)                     | NepPure        | C#实现 By Konata.Core                                                  |
| [OpenShamrock](https://github.com/whitechi73/OpenShamrock)                        | whitechi73     | Xposed框架hook实现                                                       |
| [Gensokyo](https://github.com/Hoshinonyaruko/Gensokyo)                            | Hoshinonyaruko | 基于官方api 轻量 原生跨平台                                                     |
| [LiteLoaderQQNT-OneBotApi](https://github.com/linyuchen/LiteLoaderQQNT-OneBotApi) | linyuchen      | 使用[LiteLoaderQQNT](https://github.com/LiteLoaderQQNT/LiteLoaderQQNT) |



## 鸣谢

> [IntelliJ IDEA](https://zh.wikipedia.org/zh-hans/IntelliJ_IDEA) 是一个在各个方面都最大程度地提高开发人员的生产力的 IDE，适用于 JVM 平台语言。  
[<img src="https://mikuac.com/images/jetbrains-variant-3.png" width="150"/>](https://www.jetbrains.com/?from=onebot-mirai)



## 统计
> 觉得不错给个star吧~  (～￣▽￣)～  
 [![Stargazers over time](https://starchart.cc/cnlimiter/onebot-mirai.svg)](https://starchart.cc/cnlimiter/onebot-mirai)
