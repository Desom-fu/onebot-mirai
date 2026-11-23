package cn.evole.onebot.mirai.model;

import cn.evole.onebot.sdk.action.ActionData;
import cn.evole.onebot.sdk.response.misc.MiraiResp;
import com.google.gson.annotations.SerializedName;

/**
 * 带明确中文原因（wording / message / data）的失败与扩展响应封装。
 */
public final class DetailedFailure {

    private DetailedFailure() {
    }

    /**
     * 继承自 MiraiResp.MiraiFailure，保留原有类型兼容性，同时补充 wording/message 以便 OlivOS 识别。
     */
    public static class MiraiDetailedFailure extends MiraiResp.MiraiFailure {
        @SerializedName("wording")
        private final String wording;

        @SerializedName("message")
        private final String message;

        public MiraiDetailedFailure(int retCode, String reason) {
            super();
            String msg = (reason == null || reason.isBlank()) ? "操作失败" : reason;
            this.setStatus("failed");
            this.setRetCode(retCode);
            this.setData(msg);
            this.wording = msg;
            this.message = msg;
        }

        public String getWording() {
            return wording;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * 带可选 wording 说明的成功响应。
     */
    public static class ExtendedActionData<T> extends ActionData<T> {
        @SerializedName("wording")
        private String wording;

        public ExtendedActionData() {
            super();
        }

        public String getWording() {
            return wording;
        }

        public void setWording(String wording) {
            this.wording = wording;
        }
    }

    public static MiraiDetailedFailure fail(String reason) {
        return new MiraiDetailedFailure(1400, reason);
    }

    public static MiraiDetailedFailure fail(int retCode, String reason) {
        return new MiraiDetailedFailure(retCode, reason);
    }

    public static MiraiDetailedFailure unsupported(String reason) {
        return new MiraiDetailedFailure(1400, reason);
    }

    public static MiraiDetailedFailure notFound(String action) {
        return new MiraiDetailedFailure(1404, "未知的 OneBot API: " + action);
    }

    public static MiraiDetailedFailure unauthorized(String reason) {
        return new MiraiDetailedFailure(1401, reason);
    }

    public static <T> ExtendedActionData<T> ok(T data) {
        ExtendedActionData<T> resp = new ExtendedActionData<>();
        resp.setStatus("ok");
        resp.setRetCode(0);
        resp.setData(data);
        return resp;
    }

    public static <T> ExtendedActionData<T> ok(T data, String wording) {
        ExtendedActionData<T> resp = ok(data);
        resp.setWording(wording);
        return resp;
    }

    public static ExtendedActionData<String> okWording(String wording) {
        return ok("", wording);
    }
}
