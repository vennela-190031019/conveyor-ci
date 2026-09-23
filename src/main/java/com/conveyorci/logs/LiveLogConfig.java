package com.conveyorci.logs;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes this node to every job's live-log channel. */
@Configuration(proxyBeanMethods = false)
public class LiveLogConfig {

    @Bean
    RedisMessageListenerContainer liveLogListener(RedisConnectionFactory connections, LiveLogService service) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(
                (message, pattern) -> service.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic(LogEvents.CHANNEL_PATTERN));
        return container;
    }
}
