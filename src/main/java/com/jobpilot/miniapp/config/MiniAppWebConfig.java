package com.jobpilot.miniapp.config;

import com.jobpilot.miniapp.api.MiniAppController;
import com.jobpilot.miniapp.auth.MiniAppAuthInterceptor;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Binds the Mini App gate to the Mini App prefix and nothing else, and serves the built
 * React app from the jar. No CORS mapping is registered: the Mini App is served
 * same-origin with the API, so a cross-origin rule would only widen what a browser is
 * allowed to call.
 *
 * <p>The static side is deliberately narrow. It is a resource handler under
 * {@value #PATH}, not a catch-all controller, so it cannot shadow {@code /api/**},
 * {@code /internal/**} or {@code /health}: controller mappings are matched first, and
 * this handler never sees a path outside its own prefix.
 */
@Configuration
public class MiniAppWebConfig implements WebMvcConfigurer {
    /** Where the app is published. The Vite build bakes the same value in as its base. */
    public static final String PATH = "/mini-app";

    /** Populated by the Docker build from mini-app/dist; absent in a plain Maven build. */
    static final String LOCATION = "classpath:/static/mini-app/";

    /** Vite's output directory for content-hashed bundles. */
    private static final String ASSETS = "assets";

    private final MiniAppAuthInterceptor authentication;

    public MiniAppWebConfig(MiniAppAuthInterceptor authentication) {
        this.authentication = authentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authentication).addPathPatterns(MiniAppController.BASE + "/**");
    }

    /**
     * The two entry URLs. {@code /mini-app/} leaves an empty sub-path, which the resource
     * handler rejects before any resolver runs, so the shell is forwarded by name instead
     * of being reached through the fallback below.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(PATH, PATH + "/");
        registry.addViewController(PATH + "/").setViewName("forward:" + PATH + "/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PATH + "/**")
                .addResourceLocations(LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location)
                            throws IOException {
                        Resource requested = super.getResource(path, location);
                        if (requested != null) {
                            return requested;
                        }
                        // A client-side route: hand back the shell so a refresh is not a
                        // raw 404. Anything under the hashed-asset directory must still
                        // 404 — answering it with HTML would leave a stale bundle failing
                        // on a syntax error instead of reloading. The directory itself is
                        // matched too, because the trailing slash is normalised away
                        // before a resolver sees the path.
                        boolean asset = path.equals(ASSETS) || path.startsWith(ASSETS + "/");
                        return asset ? null : super.getResource("index.html", location);
                    }
                });
    }
}
