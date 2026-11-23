package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.core.ActionRegistry;
import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.util.BaseUtils;
import cn.evole.onebot.mirai.web.http.OneBotHttpServer;
import cn.evole.onebot.sdk.action.ActionData;
import cn.evole.onebot.sdk.response.misc.MiraiResp;
import com.google.gson.JsonObject;
import net.mamoe.mirai.Bot;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OlivOS onebotSDK 全量 70 动作及关键别名注册表与分发覆盖测试。
 */
public class ActionRegistryTest {

    /**
     * 来自 OlivOS/adapter/onebotV11/onebotSDK.py 的全部 70 个 api 类名。
     */
    private static final List<String> OLIVOS_70_ACTIONS = List.of(
            "send_private_msg",
            "send_group_msg",
            "send_group_forward_msg",
            "send_private_forward_msg",
            "send_msg",
            "delete_msg",
            "get_msg",
            "get_forward_msg",
            "set_essence_msg",
            "delete_essence_msg",
            "send_like",
            "send_group_sign",
            "group_poke",
            "friend_poke",
            "set_group_kick",
            "set_group_ban",
            "set_group_anonymous_ban",
            "set_group_whole_ban",
            "set_group_admin",
            "set_group_anonymous",
            "set_group_card",
            "set_group_name",
            "set_group_leave",
            "set_group_special_title",
            "set_friend_add_request",
            "set_group_add_request",
            "get_login_info",
            "get_stranger_info",
            "get_friend_list",
            "get_group_info",
            "get_group_list",
            "get_group_member_info",
            "get_group_member_list",
            "get_cookies",
            "get_csrf_token",
            "get_credentails",
            "get_record",
            "get_image",
            "can_send_image",
            "can_send_record",
            "get_status",
            "get_version_info",
            "set_restart",
            "clean_cache",
            "send_guild_channel_msg",
            "get_guild_member_profile",
            "upload_group_file",
            "delete_group_file",
            "create_group_file_folder",
            "delete_group_folder",
            "get_group_file_system_info",
            "get_group_root_files",
            "get_group_files_by_folder",
            "get_group_file_url",
            "upload_private_file",
            "rename_group_file_folder",
            "rename_group_file",
            "set_group_file_forever",
            "trans_group_file",
            "get_essence_msg_list",
            "get_group_ignore_add_request",
            "get_doubt_friends_add_request",
            "get_group_system_msg",
            "set_doubt_friends_add_request",
            "get_group_notice",
            "send_group_notice",
            "del_group_notice",
            "set_group_reaction",
            "set_msg_emoji_like",
            "unset_msg_emoji_like"
    );

    private static final List<String> KEY_ALIASES = List.of(
            "_send_group_notice",
            "_get_group_notice",
            "_delete_group_notice",
            "_set_group_notice",
            "delete_group_notice",
            "send_forward_msg",
            "set_restart",
            "set_restart_plugin",
            "clean_cache",
            "clean_data_dir",
            "clean_plugin_log",
            "get_credentials",
            ".get_word_slices",
            "get_word_slices",
            "_check_url_safely",
            "check_url_safely",
            "send_poke"
    );

    @Test
    void testAll70OlivOSActionsRegistered() {
        assertEquals(70, OLIVOS_70_ACTIONS.size());
        for (String action : OLIVOS_70_ACTIONS) {
            assertTrue(ActionRegistry.contains(action), "注册表缺少 OlivOS 动作: " + action);
        }
    }

    @Test
    void testKeyAliasesRegistered() {
        for (String alias : KEY_ALIASES) {
            assertTrue(ActionRegistry.contains(alias), "注册表缺少关键别名: " + alias);
        }
    }

    @Test
    void testEveryRegisteredActionIsDispatchedInApiMap() {
        Bot bot = Mockito.mock(Bot.class);
        Mockito.when(bot.getId()).thenReturn(3520767439L);
        Mockito.when(bot.getNick()).thenReturn("TestBot");
        Mockito.when(bot.isOnline()).thenReturn(true);

        ApiMap apiMap = new ApiMap(bot);
        for (String action : ActionRegistry.all()) {
            ActionData<?> resp = apiMap.callMiraiApi(action, new JsonObject());
            assertNotNull(resp, "动作返回了 null: " + action);
            assertNotEquals(1404, resp.getRetCode(), "动作已注册但 ApiMap.callMiraiApi 未分派: " + action);
        }
    }

    @Test
    void testUnsupportedActionsReturnMiraiFailureWithChineseReason() {
        Bot bot = Mockito.mock(Bot.class);
        ApiMap apiMap = new ApiMap(bot);
        List<String> unsupported = List.of(
                "send_like",
                "set_msg_emoji_like",
                "unset_msg_emoji_like",
                "set_group_reaction",
                "get_cookies",
                "get_csrf_token",
                "get_credentials",
                "get_credentails",
                "set_group_file_forever",
                "upload_private_file",
                "send_guild_channel_msg",
                "get_doubt_friends_add_request"
        );
        for (String action : unsupported) {
            ActionData<?> resp = apiMap.callMiraiApi(action, new JsonObject());
            assertInstanceOf(MiraiResp.MiraiFailure.class, resp, "不支持动作必须返回 MiraiFailure: " + action);
            assertEquals("failed", resp.getStatus());
            assertNotNull(resp.getData());
            assertFalse(resp.getData().toString().isBlank());
        }
    }

    @Test
    void testHmacSha1AndQueryParsing() {
        // RFC 2202 test case 2: key="Jefe", data="what do ya want for nothing?"
        String sig = BaseUtils.hmacSha1Hex("what do ya want for nothing?", "Jefe");
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", sig);

        Map<String, String> parsed = OneBotHttpServer.parseQueryString("group_id=123456&access_token=1919810&message=%E4%BD%A0%E5%A5%BD");
        assertEquals("123456", parsed.get("group_id"));
        assertEquals("1919810", parsed.get("access_token"));
        assertEquals("你好", parsed.get("message"));
    }
}
