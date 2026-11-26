package com.health.agent.platform;

import com.health.agent.kernel.domain.Session;
import com.health.agent.platform.persistence.InMemorySessionRepository;
import com.health.agent.platform.persistence.SessionSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话列表的租户隔离与摘要。
 *
 * <p>内存模式下仓储拿不到跨会话视图，列表由 {@link SessionStore} 的缓存兜底。
 * 这条兜底路径必须和生产路径行为一致：同样的过滤、同样的摘要规则。
 */
class SessionStoreListTest {

    private final SessionStore store = new SessionStore(new InMemorySessionRepository());

    @Test
    void onlyReturnsMatchingTenantAndUser() {
        Session a1 = store.create("u1", "tenant-a");
        a1.addUser("我头疼三天了");
        Session a2 = store.create("u1", "tenant-a");
        a2.addUser("孩子发烧了怎么办");
        Session b1 = store.create("u1", "tenant-b");
        b1.addUser("别人的会话");
        Session c1 = store.create("u2", "tenant-a");
        c1.addUser("另一个用户");

        List<SessionSummary> rows = store.list("tenant-a", "u1", 10);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(SessionSummary::id)
                .containsExactlyInAnyOrder(a1.id(), a2.id());
    }

    @Test
    void previewComesFromFirstUserMessageAndIsTruncated() {
        Session s = store.create("u1", "tenant-a");
        s.addUser("我最近总是失眠晚上翻来覆去睡不着白天没精神已经两个月了这个情况要怎么调理");
        s.addUser("补充一句，我还容易醒");

        SessionSummary row = store.list("tenant-a", "u1", 10).get(0);
        assertThat(row.preview()).startsWith("我最近总是失眠");
        assertThat(row.preview()).endsWith("…");
        assertThat(row.preview().length()).isLessThanOrEqualTo(SessionSummary.PREVIEW_MAX + 1);
    }

    @Test
    void emptySessionsAreHidden() {
        // 打开页面就建会话会留下这种行：没有消息，不该出现在历史里
        store.create("u1", "tenant-a");
        Session used = store.create("u1", "tenant-a");
        used.addUser("我头疼");

        List<SessionSummary> rows = store.list("tenant-a", "u1", 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(used.id());
    }

    @Test
    void limitIsCapped() {
        for (int i = 0; i < 5; i++) {
            store.create("u1", "tenant-a").addUser("问题" + i);
        }
        assertThat(store.list("tenant-a", "u1", 2)).hasSize(2);
        // 超过 50 按 50 处理，防止一次拉全库
        assertThat(store.list("tenant-a", "u1", 999)).hasSize(5);
    }
}
