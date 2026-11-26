#!/usr/bin/env python3
"""内循环冒烟：对运行中的 jar 发真实请求，记录路由链与状态。

用途：每次改动后跑一次，确认五条主路径没坏。
不判医学正确性——那需要人工，见 04-test 的验收单。
"""
import json
import sys
import time
import urllib.request

BASE = 'http://localhost:8080/api/v1'

CASES = [
    ('单意图-症状', '我这两天头疼，要去医院吗'),
    ('多意图-症状+用药', '我头疼，能吃布洛芬吗'),
    ('红旗-急症', '我胸口疼得厉害还冒冷汗'),
    ('域外-非医疗', '今天天气怎么样'),
    ('报告-指标', '体检报告白细胞13.5，严重吗'),
]


def post(path, body, timeout=180):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'}, method='POST')
    return urllib.request.urlopen(req, timeout=timeout)


def run(text):
    sid = json.loads(post('/sessions', {'userId': 'smoke'}).read())['session_id']
    t0 = time.time()
    resp = post(f'/sessions/{sid}/messages', {'text': text})
    events, buf, ttft = [], b'', None
    while True:
        chunk = resp.read(256)
        if not chunk:
            break
        buf += chunk
        while b'\n\n' in buf:
            block, buf = buf.split(b'\n\n', 1)
            for line in block.decode('utf-8', 'ignore').split('\n'):
                if line.startswith('event:'):
                    ev = line[6:].strip()
                    events.append(ev)
                    if ev == 'token' and ttft is None:
                        ttft = time.time() - t0
    dur = time.time() - t0
    tl = json.loads(urllib.request.urlopen(f'{BASE}/sessions/{sid}/timeline', timeout=40).read())
    turn = tl['turns'][-1] if tl.get('turns') else {}
    answer = ''
    for m in reversed(turn.get('messages') or []):
        if m.get('role') == 'ASSISTANT':
            answer = m.get('content', '')
            break
    return {
        'agents': turn.get('agents') or [],
        'status': turn.get('status') or '',
        'events': sorted(set(events)),
        'dur': round(dur, 1),
        'ttft': round(ttft, 1) if ttft else None,
        'answer': answer[:120],
    }


def main():
    print('| 用例 | 路由链 | 状态 | 耗时 | 首token |')
    print('|---|---|---|---:|---:|')
    fail = 0
    for name, text in CASES:
        try:
            r = run(text)
            print(f"| {name} | {' → '.join(r['agents']) or '(空)'} | {r['status']} | {r['dur']}s | {r['ttft']}s |")
            if r['status'] == 'ERROR' or r['status'] == 'running':
                fail += 1
                print(f"  ⚠ {name}: 状态异常", file=sys.stderr)
        except Exception as e:
            fail += 1
            print(f'| {name} | ERROR | {e} | - | - |')
    print(f'\n失败: {fail}/{len(CASES)}')
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
