package com.admin;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;


// 此启动测试依赖真实数据库及 DB_*/JWT_SECRET 环境变量；单元测试不加载部署环境。
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "FLUXCHAIN_INTEGRATION_TESTS", matches = "true")
class AdminApplicationTests {



    @Test
    public void test(){

    }




}
