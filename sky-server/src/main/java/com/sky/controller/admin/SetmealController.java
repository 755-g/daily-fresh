package com.sky.controller.admin;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 套餐管理
 */
@RestController
@RequestMapping("/admin/setmeal")
@Api(tags = "套餐相关接口")
@Slf4j
public class SetmealController {

    @Autowired
    private SetmealService setmealService;
    @Autowired
    private RedisTemplate redisTemplate;

    // 延迟二次删除时间：500 毫秒
    private static final long DELAY_DELETE_TIME = 500;

    /**
     * 新增套餐
     *
     * @param setmealDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增套餐")
    public Result save(@RequestBody SetmealDTO setmealDTO) {
        setmealService.saveWithDish(setmealDTO);
        // 清理套餐列表缓存
        String key = "setmeal_" + setmealDTO.getCategoryId();
        cleanCache(key);
        // 异步延迟二次删除
        asyncDelayDelete(key);
        return Result.success();
    }

    /**
     * 分页查询
     *
     * @param setmealPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("分页查询")
    public Result<PageResult> page(SetmealPageQueryDTO setmealPageQueryDTO) {
        PageResult pageResult = setmealService.pageQuery(setmealPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 批量删除套餐
     *
     * @param ids
     * @return
     */
    @DeleteMapping
    @ApiOperation("批量删除套餐")
    public Result delete(@RequestParam List<Long> ids) {
        setmealService.deleteBatch(ids);
        // 清理所有套餐缓存
        cleanCache("setmeal_*");
        // 异步延迟二次删除
        asyncDelayDelete("setmeal_*");
        return Result.success();
    }

    /**
     * 根据id查询套餐，用于修改页面回显数据
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询套餐")
    public Result<SetmealVO> getById(@PathVariable Long id) {
        SetmealVO setmealVO = setmealService.getByIdWithDish(id);
        return Result.success(setmealVO);
    }

    /**
     * 修改套餐
     *
     * @param setmealDTO
     * @return
     */
    @PutMapping
    @ApiOperation("修改套餐")
    public Result update(@RequestBody SetmealDTO setmealDTO) {
        setmealService.update(setmealDTO);
        // 清理套餐列表缓存和套餐菜品缓存
        String listKey = "setmeal_" + setmealDTO.getCategoryId();
        String dishKey = "setmeal_dish_" + setmealDTO.getId();
        cleanCache(listKey);
        cleanCache(dishKey);
        // 异步延迟二次删除
        asyncDelayDelete(listKey);
        asyncDelayDelete(dishKey);
        return Result.success();
    }

    /**
     * 套餐起售停售
     *
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("套餐起售停售")
    public Result startOrStop(@PathVariable Integer status, Long id) {
        setmealService.startOrStop(status, id);
        // 清理套餐列表缓存和套餐菜品缓存
        SetmealVO setmealVO = setmealService.getByIdWithDish(id);
        if (setmealVO != null) {
            String listKey = "setmeal_" + setmealVO.getCategoryId();
            String dishKey = "setmeal_dish_" + id;
            cleanCache(listKey);
            cleanCache(dishKey);
            // 异步延迟二次删除
            asyncDelayDelete(listKey);
            asyncDelayDelete(dishKey);
        }
        return Result.success();
    }

    /**
     * 清理缓存数据
     * @param pattern
     */
    private void cleanCache(String pattern) {
        if (pattern.contains("*")) {
            // 模糊匹配
            redisTemplate.delete(redisTemplate.keys(pattern));
        } else {
            // 精确删除
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
}
