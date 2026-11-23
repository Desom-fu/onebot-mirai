package cn.evole.onebot.mirai.util;

import kotlin.OptIn;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.Mirai;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.message.data.ServiceMessage;
import net.mamoe.mirai.message.data.SimpleServiceMessage;
import net.mamoe.mirai.utils.MiraiExperimentalApi;

/**
 * 富文本 / 服务消息构造工具。
 */
public class RichMessageUtils {

    @OptIn(markerClass = MiraiExperimentalApi.class)
    public static ServiceMessage xmlMessage(String content) {
        return new SimpleServiceMessage(60, content);
    }

    @OptIn(markerClass = MiraiExperimentalApi.class)
    public static ServiceMessage jsonMessage(String content) {
        return new SimpleServiceMessage(1, content);
    }

    public static ServiceMessage contactQQ(Bot bot, long id) {
        String nick = String.valueOf(id);
        if (bot != null) {
            Friend friend = bot.getFriend(id);
            if (friend != null && friend.getNick() != null && !friend.getNick().isBlank()) {
                nick = friend.getNick();
            } else {
                try {
                    var profile = ProfileCache.query(bot, id);
                    if (profile != null && profile.getNickname() != null && !profile.getNickname().isBlank()) {
                        nick = profile.getNickname();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return xmlMessage(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<msg templateID=\"12345\" action=\"plugin\" p_actionData=\"AppCmd://OpenContactInfo/?uin=%s\" ".formatted(id) +
                        "brief=\"推荐了%s\" serviceID=\"14\" ".formatted(nick) +
                        "i_actionData=\"mqqapi://card/show_pslcard?src_type=internal&amp;source=sharecard&amp;version=1&amp;uin=%s\" ".formatted(id) +
                        "a_actionData=\"mqqapi://card/show_pslcard?src_type=internal&amp;source=sharecard&amp;version=1&amp;uin=%s\">".formatted(id) +
                        "<item layout=\"0\" mode=\"1\"><summary>推荐联系人</summary><hr/></item>" +
                        "<item layout=\"2\" mode=\"1\">" +
                        "<picture cover=\"mqqapi://card/show_pslcard?src_type=internal&amp;source=sharecard&amp;version=1&amp;uin=%s\"/>".formatted(id) +
                        "<title>%s</title><summary>帐号：%s</summary></item><source/></msg>".formatted(nick, id)
        );
    }
}
