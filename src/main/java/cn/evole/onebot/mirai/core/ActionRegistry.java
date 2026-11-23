package cn.evole.onebot.mirai.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OneBot v11 及扩展动作全量注册表，用于 HTTP 路由注册与 API 分发一致性校验。
 */
public final class ActionRegistry {

    private static final LinkedHashSet<String> ACTIONS = new LinkedHashSet<>();

    static {
        // 消息类
        ACTIONS.add("send_private_msg");
        ACTIONS.add("send_group_msg");
        ACTIONS.add("send_msg");
        ACTIONS.add("send_group_forward_msg");
        ACTIONS.add("send_private_forward_msg");
        ACTIONS.add("send_forward_msg");
        ACTIONS.add("delete_msg");
        ACTIONS.add("get_msg");
        ACTIONS.add("get_forward_msg");
        ACTIONS.add("send_like");
        ACTIONS.add("set_msg_emoji_like");
        ACTIONS.add("unset_msg_emoji_like");
        ACTIONS.add("set_group_reaction");
        ACTIONS.add("group_poke");
        ACTIONS.add("friend_poke");
        ACTIONS.add("send_poke");

        // 群管理与请求类
        ACTIONS.add("set_group_kick");
        ACTIONS.add("set_group_ban");
        ACTIONS.add("set_group_anonymous_ban");
        ACTIONS.add("set_group_whole_ban");
        ACTIONS.add("set_group_admin");
        ACTIONS.add("set_group_anonymous");
        ACTIONS.add("set_group_card");
        ACTIONS.add("set_group_name");
        ACTIONS.add("set_group_leave");
        ACTIONS.add("set_group_special_title");
        ACTIONS.add("set_group_portrait");
        ACTIONS.add("get_group_honor_info");
        ACTIONS.add("get_group_at_all_remain");
        ACTIONS.add("get_group_system_msg");
        ACTIONS.add("get_group_ignore_add_request");
        ACTIONS.add("get_doubt_friends_add_request");
        ACTIONS.add("set_doubt_friends_add_request");
        ACTIONS.add("send_group_sign");
        ACTIONS.add("set_friend_add_request");
        ACTIONS.add("set_group_add_request");

        // 群文件与文件下载类
        ACTIONS.add("upload_group_file");
        ACTIONS.add("delete_group_file");
        ACTIONS.add("create_group_file_folder");
        ACTIONS.add("delete_group_folder");
        ACTIONS.add("get_group_file_system_info");
        ACTIONS.add("get_group_root_files");
        ACTIONS.add("get_group_files_by_folder");
        ACTIONS.add("get_group_file_url");
        ACTIONS.add("rename_group_file");
        ACTIONS.add("rename_group_file_folder");
        ACTIONS.add("move_group_file");
        ACTIONS.add("trans_group_file");
        ACTIONS.add("set_group_file_forever");
        ACTIONS.add("upload_private_file");
        ACTIONS.add("get_private_file_url");
        ACTIONS.add("download_file");

        // 群公告 / 精华类
        ACTIONS.add("get_group_notice");
        ACTIONS.add("_get_group_notice");
        ACTIONS.add("send_group_notice");
        ACTIONS.add("_send_group_notice");
        ACTIONS.add("_set_group_notice");
        ACTIONS.add("del_group_notice");
        ACTIONS.add("delete_group_notice");
        ACTIONS.add("_delete_group_notice");
        ACTIONS.add("set_essence_msg");
        ACTIONS.add("delete_essence_msg");
        ACTIONS.add("get_essence_msg_list");

        // 查询 / 好友 / 系统类
        ACTIONS.add("get_login_info");
        ACTIONS.add("get_stranger_info");
        ACTIONS.add("get_friend_list");
        ACTIONS.add("get_group_info");
        ACTIONS.add("get_group_list");
        ACTIONS.add("get_group_member_info");
        ACTIONS.add("get_group_member_list");
        ACTIONS.add("get_online_clients");
        ACTIONS.add("get_status");
        ACTIONS.add("get_version_info");
        ACTIONS.add("get_cookies");
        ACTIONS.add("get_csrf_token");
        ACTIONS.add("get_credentials");
        ACTIONS.add("get_credentails");
        ACTIONS.add("get_group_msg_history");
        ACTIONS.add("get_friend_msg_history");
        ACTIONS.add("get_recent_contact");
        ACTIONS.add("mark_msg_as_read");
        ACTIONS.add("_mark_all_as_read");
        ACTIONS.add("mark_private_msg_as_read");
        ACTIONS.add("mark_group_msg_as_read");
        ACTIONS.add("set_input_status");
        ACTIONS.add("delete_friend");
        ACTIONS.add("set_friend_remark");
        ACTIONS.add("set_qq_profile");
        ACTIONS.add("set_qq_avatar");
        ACTIONS.add("set_self_longnick");
        ACTIONS.add("set_online_status");
        ACTIONS.add("set_restart");
        ACTIONS.add("set_restart_plugin");
        ACTIONS.add("clean_cache");
        ACTIONS.add("clean_data_dir");
        ACTIONS.add("clean_plugin_log");
        ACTIONS.add("can_send_image");
        ACTIONS.add("can_send_record");
        ACTIONS.add("get_image");
        ACTIONS.add("get_record");
        ACTIONS.add("_check_url_safely");
        ACTIONS.add("check_url_safely");
        ACTIONS.add("ocr_image");
        ACTIONS.add("get_word_slices");
        ACTIONS.add(".get_word_slices");
        ACTIONS.add("send_discuss_msg");
        ACTIONS.add("set_discuss_leave");

        // 频道与不支持扩展类
        ACTIONS.add("send_guild_channel_msg");
        ACTIONS.add("get_guild_member_profile");
        ACTIONS.add("get_guild_service_profile");
        ACTIONS.add("get_guild_list");
        ACTIONS.add("get_guild_msg");
        ACTIONS.add("get_guild_meta_by_guest");
        ACTIONS.add("get_guild_channel_list");
        ACTIONS.add("get_guild_member_list");
        ACTIONS.add("get_topic_channel_feeds");
        ACTIONS.add("_get_model_show");
        ACTIONS.add("_set_model_show");
        ACTIONS.add("get_unidirectional_friend_list");
        ACTIONS.add("delete_unidirectional_friend");
    }

    private ActionRegistry() {
    }

    public static Set<String> all() {
        return Collections.unmodifiableSet(ACTIONS);
    }

    public static boolean contains(String action) {
        return action != null && ACTIONS.contains(action);
    }
}
