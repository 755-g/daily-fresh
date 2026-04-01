package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
@Api(tags = "C 端 - 套餐浏览接口")
@Slf4j
public class SetmealController {
    @Autowired
    private SetmealService setmealService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    /*
    因为不熟悉git导致前三次提交推送都被清空了，清空的分别是项目初始代码、防缓存穿透代码、防缓存击穿代码
    当前代码是已经实现了防缓存问题的非功能扩展了的，这里通过注释补一下
    防缓存穿透方案是缓存空值+随机短过期时间
    后进来的获取不到锁的线程理应轮询，这里简化了一下逻辑，sleep（100）后直接查缓存，查不到就返回空列表
     */

    // 缓存基础过期时间：30 分钟
    private static final long CACHE_EXPIRE_MINUTES = 30;
    // 缓存随机偏移：10 分钟（最终范围：25~35 分钟）
    private static final long CACHE_RANDOM_MINUTES = 10;
    
    // 空值缓存基础过期时间：40 秒（防穿透）
    private static final long CACHE_NULL_EXPIRE_SECONDS = 40;
    // 空值缓存随机偏移：40 秒（最终范围：40~80 秒）
    private static final long CACHE_NULL_RANDOM_SECONDS = 40;
    
    // 互斥锁等待时间（秒）
    private static final long LOCK_WAIT_TIME = 5;
    // 互斥锁持有时间（秒）
    private static final long LOCK_LEASE_TIME = 10;

    /**
     * 条件查询
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类 id 查询套餐")
    public Result<List<Setmeal>> list(Long categoryId) {
        //构造 redis 中的 key，规则：setmeal_分类 id
        String key = "setmeal_" + categoryId;
    
        //查询 redis 中是否存在套餐数据
        List<Setmeal> list = (List<Setmeal>) redisTemplate.opsForValue().get(key);
        if(list != null && list.size() > 0){
            //如果存在，直接返回，无须查询数据库
            log.info("缓存命中，key:{}", key);
            return Result.success(list);
        }
    
        // 如果缓存的是空值，说明数据库中无数据，直接返回空列表
        if (redisTemplate.hasKey(key)) {
            log.info("缓存命中空值，key:{}", key);
            return Result.success(new ArrayList<>());
        }
    
        // 缓存不存在，尝试获取互斥锁
        RLock lock = redissonClient.getLock("lock:" + key);
        boolean isLocked = false;
        try {
            // 尝试获取锁，最多等待 5 秒，锁持有时间 10 秒
            isLocked = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                log.info("获取锁成功，查询数据库，key:{}", key);
                // 双重检查缓存
                list = (List<Setmeal>) redisTemplate.opsForValue().get(key);
                if (list != null && !list.isEmpty()) {
                    log.info("双重检查缓存命中，key:{}", key);
                    return Result.success(list);
                }
                
                Setmeal setmeal = new Setmeal();
                setmeal.setCategoryId(categoryId);
                setmeal.setStatus(StatusConstant.ENABLE);
                
                //如果不存在，查询数据库
                list = setmealService.list(setmeal);
                    
                if (list == null || list.isEmpty()) {
                    // 数据库也无数据，缓存空值，设置较短过期时间防穿透
                    log.info("数据库查询为空，缓存空值，key:{}", key);
                    long randomExpire = CACHE_NULL_EXPIRE_SECONDS + (long) (Math.random() * CACHE_NULL_RANDOM_SECONDS);
                    redisTemplate.opsForValue().set(key, new ArrayList<>(), randomExpire, TimeUnit.SECONDS);
                } else {
                    // 数据库有数据，正常缓存，设置随机过期时间防雪崩
                    log.info("数据库查询成功，缓存数据，key:{}, size:{}", key, list.size());
                    long randomOffset = (long) (Math.random() * CACHE_RANDOM_MINUTES) - 5;
                    long finalExpire = CACHE_EXPIRE_MINUTES + randomOffset;
                    redisTemplate.opsForValue().set(key, list, finalExpire, TimeUnit.MINUTES);
                }
            } else {
                // 未获取到锁，休眠后重试
                log.info("未获取到锁，稍后重试，key:{}", key);
                Thread.sleep(100);
                list = (List<Setmeal>) redisTemplate.opsForValue().get(key);
                if (list == null) {
                    list = new ArrayList<>();
                }
            }
        } catch (InterruptedException e) {
            log.error("获取锁异常，key:{}", key, e);
            Thread.currentThread().interrupt();
            list = new ArrayList<>();
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放锁，key:{}", key);
            }
        }
    
        return Result.success(list);
    }

    /**
     * 根据套餐id查询包含的菜品列表
     *
     * @param id
     * @return
     */
    @GetMapping("/dish/{id}")
    @ApiOperation("根据套餐id查询包含的菜品列表")
    public Result<List<DishItemVO>> dishList(@PathVariable("id") Long id) {
        List<DishItemVO> list = setmealService.getDishItemById(id);
        return Result.success(list);
    }
}
