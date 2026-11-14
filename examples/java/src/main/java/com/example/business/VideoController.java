package com.example.business;

import com.yulinlin.risk.sdk.RiskClient;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VideoController {
    private final RiskClient riskClient;

    public VideoController(RiskClient riskClient) {
        this.riskClient = riskClient;
    }

    @GetMapping("/videos/{videoId}")
    public VideoDetail detail(@PathVariable long videoId, HttpServletRequest request, Principal principal) {
        long occurredAt = System.currentTimeMillis();
        String userId = principal.getName(); // 已认证的业务用户 ID，不能使用前端随意传入的 userId。
        String resourceId = "video:" + videoId;
        String ip = request.getRemoteAddr();
        String requestUrl = request.getRequestURI(); // 只取路径，不上传查询串中的敏感信息。

        // 记录一次访问尝试，包括稍后被黑名单拦截的访问。仅入队，由 SDK 延迟批量上传。
        // 队列满等情况返回 false；本示例继续业务流程，丢弃数量可通过 SDK metrics 查看。
        riskClient.tryReport(userId, resourceId, ip, occurredAt, requestUrl);

        // 可选：只查本地黑名单缓存，不发起网络请求。首次同步完成前，本示例放行。
        // SDK 每轮结束后等待 60 秒刷新，版本未变跳过后续分页，失败继续使用旧名单。
        if (riskClient.isBlacklisted(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前用户禁止访问");
        }

        // 在实际项目中替换成已有的业务查询。
        return new VideoDetail(videoId, "演示视频 " + videoId);
    }

    public record VideoDetail(long id, String title) {}
}
