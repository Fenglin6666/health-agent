package com.health.agent.platform;

import com.health.agent.kernel.domain.Session;
import com.health.agent.platform.persistence.InMemorySessionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户隔离。
 *
 * <p>这几条对应 10-多租户.md 的 MT-01：跨租户读写必须被拒，且不泄露
 * "存在但无权"与"不存在"的区别——用 404 的差异能探测出别人有哪些会话。
 */
class SessionStoreTest {

    private final SessionStore store = new SessionStore(new InMemorySessionRepository());

    @Test
    void ownTenantCanRead() {
        Session s = store.create("u1", "tenant-a");
        assertTrue(store.find(s.id(), "tenant-a").isPresent());
    }

    @Test
    void otherTenantCannotRead() {
        Session s = store.create("u1", "tenant-a");
        assertTrue(store.find(s.id(), "tenant-b").isEmpty(),
                "跨租户查询必须返回 empty，不能返回会话");
    }

    @Test
    void unknownSessionIsAlsoEmpty() {
        // 与"存在但无权"返回同样的结果，避免用差异探测
        assertTrue(store.find("s_does_not_exist", "tenant-a").isEmpty());
    }

    @Test
    void defaultTenantWhenNotSpecified() {
        Session s = store.create("u1");
        assertEquals(Session.DEFAULT_TENANT, s.tenantId());
    }

    @Test
    void scopedUserIdIncludesTenant() {
        Session s = store.create("u1", "tenant-a");
        assertEquals("tenant-a:u1", s.scopedUserId());
    }
}
