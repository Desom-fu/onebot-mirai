package cn.evole.onebot.mirai.model;

import cn.evole.onebot.mirai.util.MiraiKotlinBridge;
import cn.evole.onebot.mirai.util.PluginRuntime;
import cn.evole.onebot.sdk.response.group.GroupHonorInfoResp;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.active.ActiveHonorInfo;
import net.mamoe.mirai.contact.active.ActiveHonorList;
import net.mamoe.mirai.data.GroupHonorType;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过 mirai 2.16 GroupActive.queryHonorHistory 构造群荣誉响应。
 */
public class MiraiGroupHonorInfoResp extends GroupHonorInfoResp {

    public MiraiGroupHonorInfoResp(Group group, String type) {
        this.setGroupId(group.getId());
        boolean all = "all".equalsIgnoreCase(type);

        if ("talkative".equalsIgnoreCase(type) || all) {
            ActiveHonorList data = querySafely(group, GroupHonorType.TALKATIVE_ID, all);
            List<OtherHonor> list = new ArrayList<>();
            if (data != null) {
                if (data.getCurrent() != null) {
                    this.setCurrentTalkative(new MiraiCurrentTalkative(data.getCurrent()));
                }
                for (ActiveHonorInfo info : data.getRecords()) {
                    list.add(toOtherHonor(info));
                }
            }
            this.setTalkativeList(list);
        }

        if ("performer".equalsIgnoreCase(type) || all) {
            ActiveHonorList data = querySafely(group, GroupHonorType.PERFORMER_ID, all);
            this.setPerformerList(toOtherHonorList(data));
        }

        if ("legend".equalsIgnoreCase(type) || all) {
            ActiveHonorList data = querySafely(group, GroupHonorType.LEGEND_ID, all);
            this.setLegendList(toOtherHonorList(data));
        }

        if ("strong_newbie".equalsIgnoreCase(type) || all) {
            ActiveHonorList data = querySafely(group, GroupHonorType.STRONG_NEWBIE_ID, all);
            this.setStrongNewbieList(toOtherHonorList(data));
        }

        if ("emotion".equalsIgnoreCase(type) || all) {
            ActiveHonorList data = querySafely(group, GroupHonorType.EMOTION_ID, all);
            this.setEmotionList(toOtherHonorList(data));
        }
    }

    private ActiveHonorList querySafely(Group group, int typeId, boolean swallowInAllMode) {
        try {
            return MiraiKotlinBridge.queryHonorHistory(group.getActive(), typeId);
        } catch (RuntimeException e) {
            if (swallowInAllMode) {
                PluginRuntime.getLogger().warning("查询群荣誉子类型失败 (group=" + group.getId() + ", typeId=" + typeId + "): " + e.getMessage());
                return null;
            }
            throw e;
        }
    }

    private List<OtherHonor> toOtherHonorList(ActiveHonorList data) {
        List<OtherHonor> list = new ArrayList<>();
        if (data != null) {
            for (ActiveHonorInfo info : data.getRecords()) {
                list.add(toOtherHonor(info));
            }
        }
        return list;
    }

    private OtherHonor toOtherHonor(ActiveHonorInfo info) {
        MiraiOtherHonor honor = new MiraiOtherHonor(info);
        if (info.getTermDays() > 0) {
            honor.setDescription("连续获得 " + info.getTermDays() + " 天");
        }
        return honor;
    }
}
