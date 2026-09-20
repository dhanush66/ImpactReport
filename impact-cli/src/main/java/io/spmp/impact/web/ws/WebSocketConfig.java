package io.spmp.impact.web.ws;

import io.spmp.impact.web.jobs.JobRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * P9.8 — Register the {@code /ws/jobs/{id}} endpoint.
 *
 * <p>Spring's wildcard path mapping ({@code {id}}) lets the handler extract the
 * job id at connect time. No origin restriction yet — fine for localhost dev;
 * tighten with {@code .setAllowedOrigins(...)} once the SPA is hosted.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JobRegistry jobs;

    public WebSocketConfig(JobRegistry jobs) { this.jobs = jobs; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(new JobLogWebSocketHandler(jobs), "/ws/jobs/*")
            .setAllowedOriginPatterns("*");
    }
}
