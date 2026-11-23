package cn.evole.onebot.mirai.model;

import cn.evole.onebot.mirai.util.ProfileCache;
import cn.evole.onebot.sdk.event.message.PrivateMessageEvent;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.NormalMember;
import net.mamoe.mirai.data.UserProfile;

/**
 * Mirai 私聊发送者到 OneBot PrivateSender 的安全映射。
 */
public class MiraiPrivateSender extends PrivateMessageEvent.PrivateSender {
    public MiraiPrivateSender(Friend sender) {
        this.setUserId(sender.getId());
        this.setNickname(sender.getNick());
        fillProfile(() -> ProfileCache.peek(sender.getBot(), sender.getId()));
    }

    public MiraiPrivateSender(NormalMember sender) {
        this.setUserId(sender.getId());
        this.setNickname(sender.getNick());
        fillProfile(() -> ProfileCache.peek(sender.getBot(), sender.getId()));
    }

    @FunctionalInterface
    private interface ProfileSupplier {
        UserProfile get();
    }

    private void fillProfile(ProfileSupplier supplier) {
        int age = 0;
        String sex = "unknown";
        try {
            UserProfile profile = supplier.get();
            if (profile != null) {
                age = profile.getAge();
                if (profile.getSex() != null) {
                    sex = profile.getSex().name().toLowerCase();
                }
            }
        } catch (Exception ignored) {
        }
        this.setAge(age);
        this.setSex(sex);
    }
}
