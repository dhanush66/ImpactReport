package io.spmp.impact.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * P10 — SPA history-mode fallback.
 *
 * <p>The React app uses <code>BrowserRouter</code> ("HTML5 history mode") so paths
 * like <code>/jobs/abcd1234</code> are owned by the SPA, not the server. Without
 * this config, a hard reload on <code>/jobs/abcd1234</code> hits Spring's MVC
 * dispatcher and gets a 404. Forwarding every non-API, non-WS, no-extension path
 * back to <code>/index.html</code> lets the SPA's router resolve it client-side.
 *
 * <p>Patterns excluded from forwarding:
 *  <ul>
 *    <li><code>/api/**</code> — REST controllers</li>
 *    <li><code>/ws/**</code>  — WebSocket endpoints</li>
 *    <li><code>/assets/**</code> — Vite-built static files</li>
 *    <li>any path containing a dot — assumed to be a static asset, leave to the static handler</li>
 *  </ul>
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward bare /  → index.html (Spring's default would 404 since there's no controller).
        registry.addViewController("/").setViewName("forward:/index.html");
        // Single- + multi-segment SPA-owned routes (/login, /jobs/<id>, etc.). The path
        // pattern parser requires unique capture names within a single pattern — using
        // "spring" twice in the same string trips "Not allowed to capture 'spring' twice".
        // Match anything without a dot (i.e. NOT a static asset like *.js / *.css / *.png),
        // and let the @RestController mappings for /api/** + /ws/** win by precedence so
        // those never fall through here.
        registry.addViewController("/{seg:[^.]+}").setViewName("forward:/index.html");
        registry.addViewController("/{seg1:[^.]+}/{seg2:[^.]+}").setViewName("forward:/index.html");
        registry.addViewController("/{seg1:[^.]+}/{seg2:[^.]+}/{seg3:[^.]+}").setViewName("forward:/index.html");
    }
}
