package cn.evole.onebot.mirai.model;

import cn.evole.onebot.mirai.util.ProfileCache;
import cn.evole.onebot.sdk.response.group.GroupMemberInfoResp;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.contact.NormalMember;
import net.mamoe.mirai.data.UserProfile;

/**
 * Mirai 群成员信息到 OneBot GroupMemberInfoResp 的安全映射。
 */
public class MiraiGroupMemberInfoResp extends GroupMemberInfoResp {
    public MiraiGroupMemberInfoResp(NormalMember member) {
        this.setGroupId(member.getGroup().getId());
        this.setUserId(member.getId());
        this.setNickname(member.getNick());
        this.setCard(member.getNameCard());

        String sex = "unknown";
        int age = 0;
        int level = 0;
        try {
            UserProfile profile = ProfileCache.query(member.getGroup().getBot(), member.getId());
            if (profile != null) {
                if (profile.getSex() != null) {
                    sex = profile.getSex().name().toLowerCase();
                }
                age = profile.getAge();
                level = profile.getQLevel();
            }
        } catch (Exception ignored) {
        }
        this.setSex(sex);
        this.setAge(age);
        this.setLevel(level);

        this.setJoinTime(member.getJoinTimestamp());
        this.setLastSentTime(member.getLastSpeakTimestamp());
        if (member.getPermission() == MemberPermission.ADMINISTRATOR) {
            this.setRole("admin");
        } else {
            this.setRole(member.getPermission().name().toLowerCase());
        }
        this.setUnfriendly(false);
        this.setTitle(member.getSpecialTitle());
        this.setTitleExpireTime(0L);
        this.setCardChangeable(member.getGroup().getBotPermission() == MemberPermission.ADMINISTRATOR
                || member.getGroup().getBotPermission() == MemberPermission.OWNER);
    }
}
