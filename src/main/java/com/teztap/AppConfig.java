package com.teztap;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.teztap.service.GeometryUtils;
import org.locationtech.jts.geom.Point;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;

import java.util.concurrent.TimeUnit;

/**
 * Application-level beans for the pricing/routing feature.
 *
 * Add this class if you don't already have a RestTemplate or CacheManager bean.
 * If you already have one, just merge the @Bean methods into your existing config.
 */
@Configuration
@EnableCaching
public class AppConfig {

    /**
     * Shared RestTemplate for ORS and OpenWeatherMap calls.
     * For production you may want to configure connection/read timeouts:
     *
     *   SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
     *   factory.setConnectTimeout(3000);
     *   factory.setReadTimeout(5000);
     *   return new RestTemplate(factory);
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }


    /**
     * Caffeine cache with a 5-minute TTL, used by WeatherService.
     * This keeps OpenWeatherMap calls well within the free 1,000/day quota
     * even under high traffic — a busy city area maps to ~1 cache key.
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("weather");
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(500)           // at most 500 distinct city-grid cells cached
        );
        return manager;
    }



    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        // 1. Create your custom module FIRST
        SimpleModule pointModule = new SimpleModule("PointModule");
        pointModule.addSerializer(Point.class, new GeometryUtils.LatLngSerializer());
        pointModule.addDeserializer(Point.class, new GeometryUtils.LatLngDeserializer());

        // 2. Add it to the BUILDER before calling .build()
        ObjectMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .addModule(pointModule) // <-- JACKSON 3 WAY TO REGISTER MODULES
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .activateDefaultTyping(ptv, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .build(); // After this line, the mapper is strictly immutable

        GenericJacksonJsonRedisSerializer jsonSerializer =
                new GenericJacksonJsonRedisSerializer(objectMapper);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();

        return template;
    }

}