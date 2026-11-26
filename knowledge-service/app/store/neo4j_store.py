"""Neo4j 存储层。

用 HTTP 事务接口（/db/neo4j/tx/commit）而不是 bolt 驱动——少一个依赖，
而且我们只跑固定几条 Cypher，不需要连接池和 ORM 那一套。
"""

from __future__ import annotations

import base64
import json
import logging
import urllib.error
import urllib.request
from typing import Any

log = logging.getLogger(__name__)

# 关系类型白名单。Cypher 的关系类型不能参数化，只能拼字符串，
# 所以必须限定来源，不能让它变成注入点。
ALLOWED_RELATIONS = {
    "HAS_SYMPTOM", "TREATED_BY", "BELONGS_TO", "REQUIRES_EXAM",
    "INTERACTS_WITH", "CONTRAINDICATED", "MENTIONED_IN",
    "RED_FLAG_OF", "TREATS",
}


class Neo4jStore:
    def __init__(self, url: str, user: str, password: str, timeout: float = 3.0) -> None:
        self.url = url.rstrip("/")
        self.timeout = timeout
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self._auth = f"Basic {token}"

    def _run(self, statement: str, params: dict | None = None) -> tuple[bool, list[dict]]:
        payload = {"statements": [{"statement": statement, "parameters": params or {}}]}
        req = urllib.request.Request(
            f"{self.url}/db/neo4j/tx/commit",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": self._auth},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            return False, [{"error": str(e)}]

        if body.get("errors"):
            return False, body["errors"]
        results = body.get("results", [])
        if not results:
            return True, []
        columns = results[0].get("columns", [])
        rows = [dict(zip(columns, d.get("row", []))) for d in results[0].get("data", [])]
        return True, rows

    def available(self) -> bool:
        ok, _ = self._run("RETURN 1 AS ok")
        return ok

    def count(self) -> int:
        ok, rows = self._run("MATCH (n:Entity) RETURN count(n) AS c")
        return int(rows[0]["c"]) if ok and rows else 0

    def init_graph(self, graph: dict[str, list[dict[str, str]]]) -> bool:
        """首次启动时把种子图谱导入。已有数据则跳过。"""
        if self.count() > 0:
            return True

        ok_any = False
        for source, edges in graph.items():
            for edge in edges:
                rel = edge.get("relation", "")
                target = edge.get("target", "")
                if rel not in ALLOWED_RELATIONS or not target:
                    log.warning("skip invalid edge: %s -[%s]-> %s", source, rel, target)
                    continue
                cypher = (
                    f"MERGE (a:Entity {{name: $from}}) "
                    f"MERGE (b:Entity {{name: $to}}) "
                    f"MERGE (a)-[:{rel}]->(b)"
                )
                ok, _ = self._run(cypher, {"from": source, "to": target})
                ok_any = ok_any or ok

        log.info("Neo4j graph initialized: %d entities", self.count())
        return ok_any

    def query(self, entity: str) -> list[dict[str, str]]:
        """双向查一跳关系：出边和入边都返回。"""
        cypher = (
            "MATCH (a:Entity {name: $name})-[r]->(b:Entity) "
            "RETURN type(r) AS relation, b.name AS target, 'out' AS direction "
            "UNION "
            "MATCH (a:Entity)-[r]->(b:Entity {name: $name}) "
            "RETURN type(r) AS relation, a.name AS target, 'in' AS direction"
        )
        ok, rows = self._run(cypher, {"name": entity})
        return rows if ok else []

    def entities(self) -> list[str]:
        ok, rows = self._run("MATCH (n:Entity) RETURN n.name AS name ORDER BY name")
        return [r["name"] for r in rows] if ok else []
