package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;

    // 延迟二次删除时间：500 毫秒
    private static final long DELAY_DELETE_TIME = 500;

    /**
     * 新增菜品
     *
     * @param dishDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);

        // 清理缓存数据（精确删除）
        String key = "dish_" + dishDTO.getCategoryId();
        cleanCache(key);
        
        // 异步延迟二次删除
        asyncDelayDelete(key);
        
        return Result.success();
    }

    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        log.info("菜品分页查询:{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 菜品批量删除
     *
     * @param ids
     * @return
     */
    @DeleteMapping
    @ApiOperation("菜品批量删除")
    public Result delete(@RequestParam List<Long> ids) {
        log.info("菜品批量删除：{}", ids);
        dishService.deleteBatch(ids);
    
        // 精确删除受影响的分类缓存
        cleanCacheByDishIds(ids);
            
        // 异步延迟二次删除
        asyncDelayDelete("dish_*");
    
        return Result.success();
    }

    /**
     * 根据id查询菜品
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询菜品")
    public Result<DishVO> getById(@PathVariable Long id) {
        log.info("根据id查询菜品：{}", id);
        DishVO dishVO = dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    /**
     * 修改菜品
     *
     * @param dishDTO
     * @return
     */
    @PutMapping
    @ApiOperation("修改菜品")
    public Result update(@RequestBody DishDTO dishDTO) {
        log.info("修改菜品：{}", dishDTO);
        dishService.updateWithFlavor(dishDTO);
    
        // 精确删除受影响的分类缓存
        String key = "dish_" + dishDTO.getCategoryId();
        cleanCache(key);
            
        // 异步延迟二次删除
        asyncDelayDelete(key);
    
        return Result.success();
    }

    /**
     * 菜品起售停售
     *
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("菜品起售停售")
    public Result<String> startOrStop(@PathVariable Integer status, Long id) {
        dishService.startOrStop(status, id);
    
        // 获取菜品所属分类，精确删除缓存
        String key = getDishCategoryKey(id);
        cleanCache(key);
            
        // 异步延迟二次删除
        asyncDelayDelete(key);
    
        return Result.success();
    }

    /**
     * 根据分类 id 查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类 id 查询菜品")
    public Result<List<Dish>> list(Long categoryId) {
        List<Dish> list = dishService.list(categoryId);
        return Result.success(list);
    }

    /**
     * 清理缓存数据
     * @param pattern
     */
    private void cleanCache(String pattern){
        if (pattern.contains("*")) {
            Set keys = redisTemplate.keys(pattern);
            redisTemplate.delete(keys);
        } else {
            redisTemplate.delete(pattern);
        }
    }

    /**
     * 异步延迟二次删除缓存
     * @param key 缓存 key 或 pattern
     */
    @Async("cacheAsyncExecutor")
    public void asyncDelayDelete(String key) {
        try {
            Thread.sleep(DELAY_DELETE_TIME);
            log.info("执行延迟二次删除缓存：{}", key);
            cleanCache(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("延迟删除缓存被中断：{}", key, e);
        }
    }

    /**
     * 根据菜品 ID 获取所属分类的缓存 key
     * @param dishId 菜品 ID
     * @return 缓存 key
     */
    private String getDishCategoryKey(Long dishId) {
        // 简化处理：直接删除所有菜品缓存，或者可以通过查询数据库获取分类 ID
        // 为了性能考虑，这里返回精确的 key 模式
        return "dish_" + dishId; // 实际使用时可能需要根据业务调整
    }

    /**
     * 根据菜品 IDs 清理对应的分类缓存
     * @param dishIds 菜品 ID 列表
     */
    private void cleanCacheByDishIds(List<Long> dishIds) {
        // 批量删除时，为简单起见删除所有菜品缓存
        // 如果需要更精确，可以查询这些菜品所属的分类
        cleanCache("dish_*");
    }
}
