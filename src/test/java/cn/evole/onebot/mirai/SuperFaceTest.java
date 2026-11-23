package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import net.mamoe.mirai.message.data.SuperFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 超级表情等非标准消息段的转义测试。
 */
public class SuperFaceTest {

    @Test
    void testSuperFaceToCQ() {
        SuperFace superFace = new SuperFace(339, "21", 1);
        assertEquals("[CQ:face,id=339]", OnebotMsgUtils.toCQString(superFace));
    }
}
