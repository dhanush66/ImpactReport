package io.spmp.impact.web;

import io.spmp.impact.graph.Neo4jWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P9.6 — Spring config exposing a singleton {@link Neo4jWriter} for the web
 * controllers + {@code AppUserService}. Lifecycle is managed by Spring: closed
 * on shutdown via {@code destroyMethod}, so the underlying Neo4j Driver isn't
 * leaked.
 *
 * <p>Connection settings come from the {@code impact.neo4j.*} properties that
 * {@code WebApplication.start} pushes in from the CLI flags.
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Neo4jWriter neo4jWriter(@Value("${impact.neo4j.uri}") String uri,
                                   @Value("${impact.neo4j.user}") String user,
                                   @Value("${impact.neo4j.pass}") String pass) {
        return new Neo4jWriter(uri, user, pass);
    }
}
