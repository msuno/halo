package run.halo.app.controller.content;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import run.halo.app.cache.AbstractStringCacheStore;
import run.halo.app.config.properties.HaloProperties;
import run.halo.app.exception.MissingPropertyException;
import run.halo.app.exception.ServiceException;
import run.halo.app.model.entity.User;
import run.halo.app.model.properties.BlogProperties;
import run.halo.app.model.support.HaloConst;
import run.halo.app.service.OptionService;
import run.halo.app.service.UserService;
import run.halo.app.utils.HaloUtils;
import run.halo.app.utils.ServletUtils;

/**
 * Main controller.
 *
 * @author ryanwang
 * @date 2019-04-23
 */
@Controller
public class MainController {

    /**
     * Index redirect uri.
     */
    private final static String INDEX_REDIRECT_URI = "index.html";
    
    /**
     * cache key
     */
    private final static String INDEX_HTML_KEY = "index_html_key";

    /**
     * Install redirect uri.
     */
    private final static String INSTALL_REDIRECT_URI = INDEX_REDIRECT_URI + "#install";

    private final UserService userService;

    private final OptionService optionService;

    private final HaloProperties haloProperties;

    private final RestTemplate restTemplate;
    
    private final AbstractStringCacheStore cacheStore;

    public MainController(UserService userService, OptionService optionService, HaloProperties haloProperties,
            RestTemplate restTemplate, AbstractStringCacheStore cacheStore) {
        this.userService = userService;
        this.optionService = optionService;
        this.haloProperties = haloProperties;
        this.restTemplate = restTemplate;
        this.cacheStore = cacheStore;
    }

    @GetMapping("${halo.admin-path:admin}/" + INDEX_REDIRECT_URI)
    @ResponseBody
    public void remoteIndex() throws Exception {
        String key = INDEX_HTML_KEY + haloProperties.getVersion();
        Optional<byte[]> bytes = cacheStore.getAny(key, byte[].class);
        if (!bytes.isPresent()) {
            ResponseEntity<byte[]> response = restTemplate.exchange(String.format(haloProperties.getStaticAdminIndex(),
                    haloProperties.getVersion()), HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            bytes = Optional.ofNullable(response.getBody());
            bytes.ifPresent(v -> cacheStore.putAny(key, v));
        }
        
        Optional<HttpServletResponse> optional = ServletUtils.getCurrentResponse();
        if (optional.isPresent() && bytes.isPresent()) {
            optional.get().getOutputStream().write(Objects.requireNonNull(bytes.get()));
        } else {
            throw new MissingPropertyException("can't get http servlet response");
        }
    }

    @GetMapping("${halo.admin-path:admin}")
    public void admin(HttpServletResponse response) throws IOException {
        String adminIndexRedirectUri = HaloUtils.ensureBoth(haloProperties.getAdminPath(), HaloUtils.URL_SEPARATOR) + INDEX_REDIRECT_URI;
        response.sendRedirect(adminIndexRedirectUri);
    }

    @GetMapping("version")
    @ResponseBody
    public String version() {
        return HaloConst.HALO_VERSION;
    }

    @GetMapping("install")
    public void installation(HttpServletResponse response) throws IOException {
        String installRedirectUri = StringUtils.appendIfMissing(this.haloProperties.getAdminPath(), "/") + INSTALL_REDIRECT_URI;
        response.sendRedirect(installRedirectUri);
    }

    @GetMapping("avatar")
    public void avatar(HttpServletResponse response) throws IOException {
        User user = userService.getCurrentUser().orElseThrow(() -> new ServiceException("未查询到博主信息"));
        if (StringUtils.isNotEmpty(user.getAvatar())) {
            response.sendRedirect(HaloUtils.normalizeUrl(user.getAvatar()));
        }
    }

    @GetMapping("logo")
    public void logo(HttpServletResponse response) throws IOException {
        String blogLogo = optionService.getByProperty(BlogProperties.BLOG_LOGO).orElse("").toString();
        if (StringUtils.isNotEmpty(blogLogo)) {
            response.sendRedirect(HaloUtils.normalizeUrl(blogLogo));
        }
    }

    @GetMapping("favicon.ico")
    public void favicon(HttpServletResponse response) throws IOException {
        String favicon = optionService.getByProperty(BlogProperties.BLOG_FAVICON).orElse("").toString();
        if (StringUtils.isNotEmpty(favicon)) {
            response.sendRedirect(HaloUtils.normalizeUrl(favicon));
        }
    }
}
