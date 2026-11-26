-- 会话与账本持久化。MySQL 8.0，utf8mb4。
--
-- 两条硬约束写在表结构里：
-- 1. message / fact_ledger 只追加：没有 updated_at，也不提供 UPDATE 语句。
--    要纠错就再追加一条，当前值由 fact_current 投影推导。
-- 2. message 用 (session_id, seq) 唯一键做幂等。重试不会插出重复对话——
--    对话历史重复比丢一条更难排查。

CREATE TABLE IF NOT EXISTS session (
  id            VARCHAR(32)  NOT NULL,
  tenant_id     VARCHAR(64)  NOT NULL,
  user_id       VARCHAR(128) NOT NULL,
  active_agent  VARCHAR(32)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_owner (tenant_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话';

CREATE TABLE IF NOT EXISTS message (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  session_id  VARCHAR(32)  NOT NULL,
  turn        INT          NOT NULL DEFAULT 0,
  seq         INT          NOT NULL,
  role        VARCHAR(16)  NOT NULL,
  name        VARCHAR(64)  NULL,
  content     MEDIUMTEXT   NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_seq (session_id, seq),
  KEY idx_session (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息（只追加）';

CREATE TABLE IF NOT EXISTS fact_ledger (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  owner       VARCHAR(200) NOT NULL COMMENT 'tenant:user',
  fact_type   VARCHAR(32)  NOT NULL,
  fact_key    VARCHAR(128) NOT NULL,
  fact_value  VARCHAR(512) NOT NULL,
  confidence  DECIMAL(4,3) NOT NULL DEFAULT 1.000,
  evidence    VARCHAR(1024) NULL,
  session_id  VARCHAR(32)  NULL,
  recorded_by VARCHAR(32)  NOT NULL DEFAULT 'librarian',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_owner_key (owner, fact_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事实账本（只追加，不可改）';

CREATE TABLE IF NOT EXISTS fact_current (
  owner       VARCHAR(200) NOT NULL,
  fact_key    VARCHAR(128) NOT NULL,
  fact_type   VARCHAR(32)  NOT NULL,
  fact_value  VARCHAR(512) NOT NULL,
  ledger_id   BIGINT       NOT NULL,
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (owner, fact_key),
  KEY idx_owner_type (owner, fact_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账本投影（由账本推导，可重建）';

CREATE TABLE IF NOT EXISTS contradiction (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  owner       VARCHAR(200) NOT NULL,
  fact_type   VARCHAR(32)  NOT NULL,
  fact_key    VARCHAR(128) NOT NULL,
  kind        VARCHAR(32)  NOT NULL,
  severity    VARCHAR(16)  NOT NULL,
  old_value   VARCHAR(512) NULL,
  new_value   VARCHAR(512) NULL,
  detail      VARCHAR(1024) NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'open',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_owner_status (owner, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆矛盾（待用户确认）';

-- 用户习惯层：和健康档案分开存。
-- 档案是事实（只追加、要审计、要矛盾检测），习惯是偏好（最新值有效、改了就覆盖）。
-- 两种写入语义放一张表，要么过敏史被静默覆盖，要么每次改口味都变成一条"矛盾"。
CREATE TABLE IF NOT EXISTS user_habit (
  owner       VARCHAR(200) NOT NULL,
  habit_key   VARCHAR(32)  NOT NULL,
  habit_value VARCHAR(512) NOT NULL,
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (owner, habit_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户习惯（偏好，可覆盖）';

-- 轮次事件：一轮对话里发生的事（路由、交接、每次模型调用、每次工具调用）。
-- 消息表记"说了什么"，这张表记"怎么走到那句话的"。只有消息的话，回答错了没法复盘
-- 是路由错、检索没命中、还是模型跑偏。
CREATE TABLE IF NOT EXISTS turn_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  session_id  VARCHAR(32)  NOT NULL,
  turn        INT          NOT NULL,
  seq         INT          NOT NULL,
  type        VARCHAR(24)  NOT NULL,
  agent       VARCHAR(32)  NULL,
  detail      MEDIUMTEXT   NULL,
  elapsed_ms  BIGINT       NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_turn_seq (session_id, turn, seq),
  KEY idx_session_turn (session_id, turn, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮次事件（路由/交接/模型/工具）';

-- 评测：一次跑批 = 一行 run + N 行 result。
-- 不建 eval_case 表：语料是文件（jsonl/yaml），改语料走代码评审，不走数据库。
-- 建表只解决"这次跑得比上次好还是差"，不解决"语料存在哪"。
CREATE TABLE IF NOT EXISTS eval_run (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  set_name     VARCHAR(64)  NOT NULL COMMENT '语料集名，如 route / redflag',
  snapshot     VARCHAR(256) NOT NULL COMMENT 'config_version + model + prompt_hash',
  baseline_id  BIGINT       NULL COMMENT '对比基线 run',
  total        INT          NOT NULL DEFAULT 0,
  passed       INT          NOT NULL DEFAULT 0,
  started_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_set_time (set_name, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测跑批';

CREATE TABLE IF NOT EXISTS eval_result (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  run_id     BIGINT       NOT NULL,
  case_id    VARCHAR(64)  NOT NULL,
  expected   VARCHAR(512) NOT NULL,
  actual     VARCHAR(512) NOT NULL,
  passed     TINYINT      NOT NULL,
  reason     VARCHAR(512) NULL,
  PRIMARY KEY (id),
  KEY idx_run (run_id),
  KEY idx_run_passed (run_id, passed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测单条结果';

-- 回答反馈：点赞 / 点踩 / 举报。
-- 不建 message_id 外键：前端没有消息主键，(session, turn, agent) 足以定位一条回答。
-- 唯一键让重复提交变成覆盖，而不是堆出一堆同义记录。
CREATE TABLE IF NOT EXISTS message_feedback (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(64)  NOT NULL,
  session_id  VARCHAR(32)  NOT NULL,
  turn        INT          NOT NULL,
  agent       VARCHAR(32)  NOT NULL DEFAULT '',
  rating      VARCHAR(16)  NOT NULL COMMENT 'up / down / report',
  reason      VARCHAR(32)  NOT NULL DEFAULT '',
  comment     VARCHAR(200) NOT NULL DEFAULT '',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_feedback (tenant_id, session_id, turn, agent),
  KEY idx_rating_time (rating, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回答反馈';
