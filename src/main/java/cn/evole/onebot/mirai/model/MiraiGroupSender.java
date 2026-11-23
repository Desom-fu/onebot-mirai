package cn.evole.onebot.mirai.model;

import cn.evole.onebot.mirai.util.ProfileCache;
import cn.evole.onebot.sdk.event.message.GroupMessageEvent;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.data.UserProfile;

/**
 * Mirai 群消息发送者到 OneBot GroupSender 的安全映射。
 */
public class MiraiGroupSender extends GroupMessageEvent.GroupSender {
    public MiraiGroupSender(Member sender) {
        this.setUserId(String.valueOf(sender.getId()));
        this.setNickname(sender.getNick());
        this.setCard(sender.getNameCard());
        this.setArea("");

        int age = 0;
        String sex = "unknown";
        String level = "0";
        try {
            UserProfile profile = ProfileCache.query(sender.getBot(), sender.getId());
            if (profile != null) {
                age = profile.getAge();
                if (profile.getSex() != null) {
                    sex = profile.getSex().name().toLowerCase();
                }
                level = String.valueOf(profile.getQLevel());
            }
        } catch (Exception ignored) {
        }
        this.setAge(age);
        this.setSex(sex);
        this.setLevel(level);
        if (sender.getPermission() == MemberPermission.ADMINISTRATOR) {
            this.setRole("admin");
        } else {
            this.setRole(sender.getPermission().name().toLowerCase());
        }
        this.setTitle(sender.getSpecialTitle());
    }
}
