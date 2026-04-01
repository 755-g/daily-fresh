package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 套餐业务实现
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

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
     * 新增套餐，同时需要保存套餐和菜品的关联关系
     *
     * @param setmealDTO
     */
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        //向套餐表插入数据
        setmealMapper.insert(setmeal);

        //获取生成的套餐id
        Long setmealId = setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });

        //保存套餐和菜品的关联关系
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 分页查询
     *
     * @param setmealPageQueryDTO
     * @return
     */
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        int pageNum = setmealPageQueryDTO.getPage();
        int pageSize = setmealPageQueryDTO.getPageSize();

        PageHelper.startPage(pageNum, pageSize);
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除套餐
     *
     * @param ids
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {
        ids.forEach(id -> {
            Setmeal setmeal = setmealMapper.getById(id);
            if (StatusConstant.ENABLE == setmeal.getStatus()) {
                //起售中的套餐不能删除
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });

        ids.forEach(setmealId -> {
            //删除套餐表中的数据
            setmealMapper.deleteById(setmealId);
            //删除套餐菜品关系表中的数据
            setmealDishMapper.deleteBySetmealId(setmealId);
        });
    }

    /**
     * 根据id查询套餐和套餐菜品关系
     *
     * @param id
     * @return
     */
    public SetmealVO getByIdWithDish(Long id) {
        SetmealVO setmealVO = setmealMapper.getByIdWithDish(id);
        return setmealVO;
    }

    /**
     * 修改套餐
     *
     * @param setmealDTO
     */
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        //1、修改套餐表，执行update
        setmealMapper.update(setmeal);

        //套餐id
        Long setmealId = setmealDTO.getId();

        //2、删除套餐和菜品的关联关系，操作setmeal_dish表，执行delete
        setmealDishMapper.deleteBySetmealId(setmealId);

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });
        //3、重新插入套餐和菜品的关联关系，操作setmeal_dish表，执行insert
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 套餐起售、停售
     *
     * @param status
     * @param id
     */
    public void startOrStop(Integer status, Long id) {
        //起售套餐时，判断套餐内是否有停售菜品，有停售菜品提示"套餐内包含未启售菜品，无法启售"
        if (status == StatusConstant.ENABLE) {
            //select a.* from dish a left join setmeal_dish b on a.id = b.dish_id where b.setmeal_id = ?
            List<Dish> dishList = dishMapper.getBySetmealId(id);
            if (dishList != null && dishList.size() > 0) {
                dishList.forEach(dish -> {
                    if (StatusConstant.DISABLE == dish.getStatus()) {
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }

        Setmeal setmeal = Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

    /**
     * 条件查询
     * @param setmeal
     * @return
     */
    public List<Setmeal> list(Setmeal setmeal) {
        List<Setmeal> list = setmealMapper.list(setmeal);
        return list;
    }

    /**
     * 根据 id 查询菜品选项
     * @param id
     * @return
     */
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }

    /**
     * 根据 id 查询菜品选项 (带缓存保护)
     * @param id
     * @return
     */
    public List<DishItemVO> getDishItemByIdWithCache(Long id) {
        String key = "setmeal_dish_" + id;
        
        // 1. 查询缓存
        List<DishItemVO> list = (List<DishItemVO>) redisTemplate.opsForValue().get(key);
        if (list != null && !list.isEmpty()) {
            log.info("缓存命中，key:{}", key);
            return list;
        }
        
        // 2. 缓存空值检查
        if (redisTemplate.hasKey(key)) {
            log.info("缓存命中空值，key:{}", key);
            return new ArrayList<>();
        }
        
        // 3. 获取互斥锁
        RLock lock = redissonClient.getLock("lock:" + key);
        boolean isLocked = false;
        try {
            isLocked = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                log.info("获取锁成功，查询数据库，key:{}", key);
                
                // 双重检查缓存
                list = (List<DishItemVO>) redisTemplate.opsForValue().get(key);
                if (list != null && !list.isEmpty()) {
                    return list;
                }
                
                // 查询数据库
                list = setmealMapper.getDishItemBySetmealId(id);
                
                if (list == null || list.isEmpty()) {
                    // 缓存空值
                    log.info("数据库查询为空，缓存空值，key:{}", key);
                    long randomExpire = CACHE_NULL_EXPIRE_SECONDS + (long)(Math.random() * CACHE_NULL_RANDOM_SECONDS);
                    redisTemplate.opsForValue().set(key, new ArrayList<>(), randomExpire, TimeUnit.SECONDS);
                } else {
                    // 正常缓存
                    log.info("数据库查询成功，缓存数据，key:{}, size:{}", key, list.size());
                    long randomOffset = (long)(Math.random() * CACHE_RANDOM_MINUTES) - 5;
                    long finalExpire = CACHE_EXPIRE_MINUTES + randomOffset;
                    redisTemplate.opsForValue().set(key, list, finalExpire, TimeUnit.MINUTES);
                }
            } else {
                // 未获取到锁，休眠后重试
                log.info("未获取到锁，稍后重试，key:{}", key);
                Thread.sleep(100);
                list = (List<DishItemVO>) redisTemplate.opsForValue().get(key);
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
        
        return list;
    }
}
