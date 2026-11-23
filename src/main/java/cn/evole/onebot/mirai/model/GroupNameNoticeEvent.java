package cn.evole.onebot.mirai.model;

import cn.evole.onebot.sdk.event.notice.NoticeEvent;
import com.google.gson.annotations.SerializedName;

/**
 * 群名称变更通知事件（notice_type=group_name）。
 */
public class GroupNameNoticeEvent extends NoticeEvent {
    @SerializedName("group_id")
    private long groupId;

    @SerializedName("name_new")
    private String nameNew;

    @SerializedName("name_old")
    private String nameOld;

    public long getGroupId() {
        return groupId;
    }

    public void setGroupId(long groupId) {
        this.groupId = groupId;
    }

    public String getNameNew() {
        return nameNew;
    }

    public void setNameNew(String nameNew) {
        this.nameNew = nameNew;
    }

    public String getNameOld() {
        return nameOld;
    }

    public void setNameOld(String nameOld) {
        this.nameOld = nameOld;
    }
}
