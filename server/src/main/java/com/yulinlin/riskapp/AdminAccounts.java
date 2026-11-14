package com.yulinlin.riskapp;

import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.data.core.wrapper.ISelectWrapper;
import com.yulinlin.riskapp.schema.model.RiskTables;
import com.yulinlin.security.auth.IUserDetails;
import com.yulinlin.security.auth.LoginAuthenticator;
import com.yulinlin.security.auth.LoginException;
import com.yulinlin.security.auth.PasswordLogin;
import com.yulinlin.security.auth.UserStore;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** One persistent administrator; public registration closes after the first account. */
@Service
@DependsOnDatabaseInitialization
class AdminAccounts implements LoginAuthenticator<PasswordLogin>, UserStore {
    private final RouteSession orm;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String dummyPasswordHash;

    AdminAccounts(RouteSession orm, PasswordEncoder passwords, Clock clock) {
        this.orm = orm;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    boolean initialized() { return account() != null; }

    synchronized void register(String username, String password) {
        if (initialized()) throw registrationClosed();
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw Inputs.bad("账号需为 3–64 位字母、数字、下划线、点或短横线");
        }
        if (password == null || password.isBlank() || password.length() < 8 || password.indexOf('\0') >= 0
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw Inputs.bad("密码至少 8 个字符，UTF-8 编码不能超过 72 字节");
        }
        var row = new RiskTables.AdminAccount();
        // A fixed primary key also prevents two processes from creating separate first accounts.
        row.id = 1;
        row.username = username;
        row.passwordHash = passwords.encode(password);
        row.createdAt = clock.millis();
        try {
            orm.transaction(() -> {
                orm.insert(ExecuteRequest.ofInsert(RiskTables.AdminAccount.class)
                        .addRequest(orm.getWrapperFactory().createInsertWrapper(row)));
                return null;
            });
        } catch (RuntimeException error) {
            if (initialized()) throw registrationClosed();
            throw error;
        }
    }

    @Override public String name() { return "password"; }

    @Override public Class<PasswordLogin> requestType() { return PasswordLogin.class; }

    @Override public IUserDetails authenticate(PasswordLogin request) {
        String password = request.password();
        if (password == null || password.isBlank() || password.indexOf('\0') >= 0
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new LoginException("账号或密码错误");
        }
        var row = account();
        boolean known = row != null && row.username.equals(request.username());
        // Unknown accounts still perform password verification; credentials never enter the session.
        boolean matches = passwords.matches(password, known ? row.passwordHash : dummyPasswordHash);
        if (!known || !matches) throw new LoginException("账号或密码错误");
        return new AdminUser(row.username);
    }

    @Override public IUserDetails load(String userId) {
        if (!"1".equals(userId)) return null;
        var row = account();
        return row == null ? null : new AdminUser(row.username);
    }

    private record AdminUser(String username) implements IUserDetails, Serializable {
        @Override public String getUserId() { return "1"; }
        @Override public String getUsername() { return username; }
        @Override public Set<String> getRoles() { return Set.of("ADMIN"); }
        @Override public Set<String> getPermissions() { return Set.of(); }
        @Override public Object getAttribute(String name) { return null; }
    }

    private RiskTables.AdminAccount account() {
        ISelectWrapper<RiskTables.AdminAccount, ?, ?, ?> query = orm.getWrapperFactory().createSelectWrapper(new RiskTables.AdminAccount());
        query.where().eq("id", 1);
        var rows = orm.select(QueryRequest.newInstance(RiskTables.AdminAccount.class, query).cache(false).autoUpdate(false));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static ResponseStatusException registrationClosed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "管理员已创建，注册入口已关闭，请登录");
    }
}
