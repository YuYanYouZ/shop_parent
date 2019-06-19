package com.qfedu.ljb.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.qfedu.common.config.ProjectConfig;
import com.qfedu.common.jwt.JwtUtil;
import com.qfedu.common.util.EncryptionUtil;
import com.qfedu.common.util.IdGenerator;
import com.qfedu.common.util.JedisUtil;
import com.qfedu.common.vo.R;
import com.qfedu.ljb.entity.User;
import com.qfedu.ljb.server.dao.UserDao;
import com.qfedu.ljb.server.dao.UserLogDao;
import com.qfedu.ljb.server.model.LoginToken;
import com.qfedu.ljb.server.service.LoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

@Service
public class LoginServiceimpl implements LoginService {
    @Autowired(required = false)
    private UserDao userDao;
    @Autowired(required = false)
    private UserLogDao logDao;
    @Autowired(required = false)
    private JedisUtil jedisUtil;
    @Autowired(required = false)
    private IdGenerator idGenerator;

    //随时都可以登录，但是只允许在线一个（最新的）10分钟 连续登录失败3次自动锁定账户一小时
    @Override
    public R login(String phone, String pass) {
        R r;
        //先校验当前账号是否被锁定
        if(jedisUtil.exists(ProjectConfig.USERSD+phone)){
            r = R.setERROR("账号锁定中，剩余时间：" + (jedisUtil.ttl(ProjectConfig.USERSD + phone)/60));
        }else {
            //1、校验用户名是否存在
            User user = userDao.selectByPhone(phone);
            if(user!=null){
                //2、校验密码是否正常
                if (Objects.equals(user.getPassword(), EncryptionUtil.RSAEnc(ProjectConfig.PASSRSAPRI, pass))){
                    //登录成功
                    LoginToken loginToken = new LoginToken();
                    loginToken.setPhone(phone);
                    loginToken.setUid(user.getId());
                    loginToken.setId(idGenerator.nextId()+"");
                    //3、生成Token
                    String token = JwtUtil.createJWT(loginToken.getId(), JSON.toJSONString(loginToken));
                    //4、存储到服务器
                        //1、String类型user:phone token
                    jedisUtil.setex(ProjectConfig.TOKENPHONE+phone, 1800, token);
                        //2、String类型token phone  校验token是否有效
                    jedisUtil.setex(ProjectConfig.TOKENJWT+token, 1800, phone);
                        //3、Zset 记录每个手机号下的所有的Token phone  score:phone   value:令牌
                    //5、将token返回
                    logDao.save(user.getId(),"登录成功，令牌生成");
                    r = R.setOK("OK", token);
                }else {
                    logDao.save(user.getId(), "登录失败，密码有误");
                    r = R.setERROR("密码不正确");
                }
            }else {
                logDao.save(user.getId(),"登录失败，账号有误");
                r = R.setERROR("账号不存在，请注册");
            }
            if (r.getCode() != 1){
                String key = ProjectConfig.USERLOGINCOUNT + phone;
                jedisUtil.setex(key+"_"+System.currentTimeMillis(), 600, "1");
                Set<String> set = jedisUtil.keys(key + "*");
                if(set.size() == 3){
                    logDao.save(user.getId(),"登录失败超过3次，账号被冻结");
                    //将当前账号冻结1小时
                    jedisUtil.setex(ProjectConfig.USERSD+phone,3600,"10分钟连续失败三次冻结账户");
                    r = R.setERROR("连续多次账号或密码错误，账号被锁定，请1小时之后重新登录");
                }
            }
        }
        return r;
    }

    @Override
    public R checkLogin(String token) {
        //if(token!=null){
            //1、校验Toke有效性
            if (JwtUtil.checkJWT(token)){
                //反解析 令牌 获取当初登录的手机号
                LoginToken loginToken = JSON.parseObject(JwtUtil.parseJWT(token), LoginToken.class);
                //获取当前手机号的令牌
                String t = jedisUtil.get(ProjectConfig.TOKENPHONE+loginToken.getPhone());
                //比对令牌
                if (Objects.equals(t, token)){
                    return R.setOK("有效", token);
                }else {
                    return R.setERROR("已经在其他地方登录了");
                }
            }else {
                return R.setERROR("Token校验失败");
            }
//        }else{
//            return R.setERROR("暂未登录");
//        }

    }

    @Override
    public R exitLogin(String token) {
        //1、校验Token有效性
        if (JwtUtil.checkJWT(token)){
            jedisUtil.del(ProjectConfig.TOKENJWT+token);
            //反解析 令牌 获得当初登录的手机号
            LoginToken loginToken = JSON.parseObject(JwtUtil.parseJWT(token), LoginToken.class);
            //获得当前手机号的令牌
            jedisUtil.del(ProjectConfig.TOKENPHONE+loginToken.getPhone());
            return R.setOK("退出成功", null);
        }else {
            return R.setERROR("Token校验失败");
        }
    }
}
