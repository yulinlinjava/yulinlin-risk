package com.yulinlin.riskapp;

import com.yulinlin.security.annotation.ApiLimit;
import com.yulinlin.security.annotation.ApiLogin;
import com.yulinlin.security.annotation.ApiPublic;
import com.yulinlin.security.auth.ISecurityManager;
import com.yulinlin.security.auth.LoginException;
import com.yulinlin.security.auth.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

@Controller
public class AdminRegistrationController {
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private final AdminAccounts accounts;
    private final ISecurityManager security;

    AdminRegistrationController(AdminAccounts accounts, ISecurityManager security) {
        this.accounts = accounts;
        this.security = security;
    }

    @ApiPublic
    @GetMapping("/login")
    public ResponseEntity<String> login(HttpServletRequest request) {
        if (!accounts.initialized()) return redirect("/setup");
        String message = request.getParameter("error") != null ? "账号或密码不正确，请重试。"
                : request.getParameter("registered") != null ? "管理员账号已创建，请登录。"
                : request.getParameter("logout") != null ? "已退出登录。" : "";
        return page(HttpStatus.OK, "登录后台", "使用已注册的管理员账号登录。", message, """
                <form action="/login" method="post">
                    %s
                    <label for="username">账号</label>
                    <input id="username" name="username" autocomplete="username" required maxlength="64" autofocus>
                    <label for="password">密码</label>
                    <input id="password" name="password" type="password" autocomplete="current-password" required>
                    <button type="submit">登录</button>
                </form>
                """.formatted(csrf(request)));
    }

    @ApiPublic
    @ApiLimit(count = 10, interval = "1m")
    @PostMapping("/login")
    public ResponseEntity<String> authenticate(@RequestParam(required = false) String username,
                                               @RequestParam(required = false) String password) {
        if (!accounts.initialized()) return redirect("/setup");
        try {
            security.login(new PasswordLogin(username, password, "web"));
            return redirect("/admin/index.html");
        } catch (LoginException error) {
            return redirect("/login?error");
        }
    }

    @ApiLogin
    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        security.logout();
        return redirect("/login?logout");
    }

    @ApiPublic
    @GetMapping("/setup")
    public ResponseEntity<String> setup(HttpServletRequest request) {
        return accounts.initialized() ? redirect("/login") : registrationPage(request, HttpStatus.OK, "");
    }

    @ApiPublic
    @ApiLimit(count = 5, interval = "1m")
    @PostMapping("/setup")
    public ResponseEntity<String> register(HttpServletRequest request,
                                   @RequestParam(required = false) String username,
                                   @RequestParam(required = false) String password,
                                   @RequestParam(required = false) String confirmPassword) {
        if (accounts.initialized()) return registrationClosed();
        if (password == null || !password.equals(confirmPassword)) {
            return registrationPage(request, HttpStatus.BAD_REQUEST, "两次输入的密码不一致。");
        }
        try {
            accounts.register(username, password);
            return redirect("/login?registered");
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 409) return registrationClosed();
            if (error.getStatusCode().value() != 400) throw error;
            return registrationPage(request, error.getStatusCode(), error.getReason());
        }
    }

    private static ResponseEntity<String> registrationPage(HttpServletRequest request, HttpStatusCode status, String message) {
        return page(status, "创建管理员账号", "首次使用，请创建一个管理员账号。创建完成后将关闭注册入口。", message, """
                <form action="/setup" method="post">
                    %s
                    <label for="username">账号</label>
                    <input id="username" name="username" autocomplete="username" required minlength="3" maxlength="64"
                           pattern="[A-Za-z0-9_.\\-]{3,64}" aria-describedby="username-help" autofocus>
                    <p class="hint" id="username-help">3–64 位字母、数字、下划线、点或短横线。</p>
                    <label for="password">密码</label>
                    <input id="password" name="password" type="password" autocomplete="new-password" required minlength="8"
                           aria-describedby="password-help">
                    <p class="hint" id="password-help">至少 8 个字符；最多 72 字节，中文通常占 3 字节。</p>
                    <label for="confirmPassword">确认密码</label>
                    <input id="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" required minlength="8">
                    <button type="submit">创建账号</button>
                </form>
                """.formatted(csrf(request)));
    }

    private static ResponseEntity<String> registrationClosed() {
        return page(HttpStatus.CONFLICT, "注册已关闭", "管理员账号已创建，请使用已有账号登录。", "",
                "<a class=\"button\" href=\"/login\">前往登录</a>");
    }

    private static String csrf(HttpServletRequest request) {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(token.getParameterName())
                + "\" value=\"" + HtmlUtils.htmlEscape(token.getToken()) + "\">";
    }

    private static ResponseEntity<String> redirect(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore()).location(URI.create(path)).build();
    }

    private static ResponseEntity<String> page(HttpStatusCode status, String title, String description, String message, String form) {
        String notice = message == null || message.isBlank() ? ""
                : "<p class=\"notice\" role=\"status\">" + HtmlUtils.htmlEscape(message) + "</p>";
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s · 行为风控</title>
                    <link rel="stylesheet" href="/auth.css">
                </head>
                <body>
                    <main>
                        <p class="brand">行为风控 <span>Admin</span></p>
                        <h1>%s</h1>
                        <p class="description">%s</p>
                        %s
                        %s
                    </main>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(description), notice, form);
        return ResponseEntity.status(status).contentType(HTML).cacheControl(CacheControl.noStore()).body(html);
    }
}
