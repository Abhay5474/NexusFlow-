package io.aetheros.control.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public SimpleUrlHandlerMapping wsMapping(ForensicsStreamHandler handler) {
        SimpleUrlHandlerMapping m = new SimpleUrlHandlerMapping();
        m.setUrlMap(Map.of("/ws/forensics", handler));
        m.setOrder(-1);
        return m;
    }

    @Bean
    public WebSocketHandlerAdapter wsAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
