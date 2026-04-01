package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String CART_KEY_PREFIX = "shopping_cart:";
    // 购物车过期时间：30 天
    private static final long CART_EXPIRE_DAYS = 30;

    /**
     * 生成 Redis Hash 的 field 键
     * 格式：dishId_setmealId_flavor
     */
    private String generateFieldKey(ShoppingCartDTO dto) {
        Long dishId = dto.getDishId();
        Long setmealId = dto.getSetmealId();
        String flavor = dto.getDishFlavor() != null ? dto.getDishFlavor() : "";
        
        if (dishId != null) {
            return dishId + "_null_" + flavor;
        } else {
            return "null_" + setmealId + "_" + flavor;
        }
    }

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        Long userId = BaseContext.getCurrentId();
        String redisKey = CART_KEY_PREFIX + userId;
        String fieldKey = generateFieldKey(shoppingCartDTO);

        // 1. 从 Redis 查询是否已存在
        Object obj = redisTemplate.opsForHash().get(redisKey, fieldKey);
        String json = (obj != null) ? obj.toString() : null;
        
        if (json != null) {
            // 已存在，数量 +1
            ShoppingCart cart = JSON.parseObject(json, ShoppingCart.class);
            cart.setNumber(cart.getNumber() + 1);
            redisTemplate.opsForHash().put(redisKey, fieldKey, JSON.toJSONString(cart));
            log.info("购物车商品数量 +1: {}", cart.getName());
        } else {
            // 不存在，查询 MySQL 获取商品信息（仅首次需要）
            ShoppingCart cart = new ShoppingCart();
            BeanUtils.copyProperties(shoppingCartDTO, cart);
            cart.setUserId(userId);
            
            if (shoppingCartDTO.getDishId() != null) {
                Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
                cart.setName(dish.getName());
                cart.setImage(dish.getImage());
                cart.setAmount(dish.getPrice());
            } else {
                Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                cart.setName(setmeal.getName());
                cart.setImage(setmeal.getImage());
                cart.setAmount(setmeal.getPrice());
            }
            cart.setNumber(1);
            cart.setCreateTime(LocalDateTime.now());
            
            // 写入 Redis
            redisTemplate.opsForHash().put(redisKey, fieldKey, JSON.toJSONString(cart));
            // 设置过期时间
            redisTemplate.expire(redisKey, CART_EXPIRE_DAYS, TimeUnit.DAYS);
            log.info("添加新商品到购物车：{}", cart.getName());
        }
    }

    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        Long userId = BaseContext.getCurrentId();
        String redisKey = CART_KEY_PREFIX + userId;
            
        // 直接从 Redis 读取所有购物车数据
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
        List<ShoppingCart> list = entries.values().stream()
            .map(obj -> JSON.parseObject(obj.toString(), ShoppingCart.class))
            .collect(Collectors.toList());
            
        log.info("查看购物车，商品数量：{}", list.size());
        return list;
    }

    /**
     * 清空购物车
     */
    public void cleanShoppingCart() {
        Long userId = BaseContext.getCurrentId();
        String redisKey = CART_KEY_PREFIX + userId;
        // 直接删除整个 Hash
        redisTemplate.delete(redisKey);
        log.info("清空购物车完成");
    }

    /**
     * 删除购物车中一个商品
     * @param shoppingCartDTO
     */
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        Long userId = BaseContext.getCurrentId();
        String redisKey = CART_KEY_PREFIX + userId;
        String fieldKey = generateFieldKey(shoppingCartDTO);

        Object obj = redisTemplate.opsForHash().get(redisKey, fieldKey);
        String json = (obj != null) ? obj.toString() : null;
        
        if (json != null) {
            ShoppingCart cart = JSON.parseObject(json, ShoppingCart.class);
            Integer number = cart.getNumber();
            
            if (number == 1) {
                // 数量为 1，直接删除
                redisTemplate.opsForHash().delete(redisKey, fieldKey);
                log.info("删除购物车商品：{}", cart.getName());
            } else {
                // 数量减 1
                cart.setNumber(number - 1);
                redisTemplate.opsForHash().put(redisKey, fieldKey, JSON.toJSONString(cart));
                log.info("购物车商品数量 -1: {}", cart.getName());
            }
        }
    }
}
