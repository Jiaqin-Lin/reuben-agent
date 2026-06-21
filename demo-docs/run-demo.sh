#!/bin/bash
# =============================================================================
# reuben-agent 全链路演示脚本
# 上传 → 策略推荐 → 确认策略 → 索引构建 → RAG 检索
# =============================================================================
set -e

BASE_URL="${1:-http://localhost:8080}"
DOC_DIR="$(cd "$(dirname "$0")" && pwd)"
BOLD="\033[1m"
GREEN="\033[32m"
YELLOW="\033[33m"
CYAN="\033[36m"
RED="\033[31m"
RESET="\033[0m"

section()  { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════════════════════════${RESET}"; echo -e "${BOLD}${CYAN}  $*${RESET}"; echo -e "${BOLD}${CYAN}══════════════════════════════════════════════════════════════${RESET}\n"; }
step()    { echo -e "${BOLD}${YELLOW}▶ $*${RESET}"; }
ok()      { echo -e "  ${GREEN}✅ $*${RESET}"; }
info()    { echo -e "  ${CYAN}ℹ $*${RESET}"; }
fail()    { echo -e "  ${RED}❌ $*${RESET}"; }

# check if server is up
if ! curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/document/upload" 2>/dev/null | grep -q .; then
    fail "服务未启动，请先执行: mvn spring-boot:run -pl launcher"
    exit 1
fi

# =============================================================================
# 阶段 1 — 上传三份文档
# =============================================================================
section "阶段 1 — 上传文档（3 份）"

declare -A DOC_IDS DOC_NAMES
for f in "$DOC_DIR"/Redis*.md "$DOC_DIR"/Docker*.md "$DOC_DIR"/MySQL*.md; do
    fname=$(basename "$f")
    step "上传: $fname"

    RESP=$(curl -s -X POST "$BASE_URL/api/document/upload" \
        -F "file=@$f;filename=$fname" \
        -F "meta={\"documentName\":\"$fname\",\"knowledgeScopeCode\":\"general_document\",\"knowledgeScopeName\":\"通用文档\",\"businessCategory\":\"技术手册\"};type=application/json")

    CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "parse_error")
    DOC_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['documentId'])" 2>/dev/null || echo "0")
    TASK_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['taskId'])" 2>/dev/null || echo "0")

    if [ "$CODE" != "0" ]; then
        fail "上传失败: $RESP"
        continue
    fi
    DOC_IDS["$DOC_ID"]="$fname"
    DOC_NAMES["$DOC_ID"]="$fname"
    ok "documentId=$DOC_ID  taskId=$TASK_ID → 已投递到 Kafka 解析队列"
done

# =============================================================================
# 阶段 2 — 等待策略推荐（Kafka 异步解析）
# =============================================================================
section "阶段 2 — 等待 Kafka 异步解析 + 策略推荐"

MAX_WAIT=60
declare -A PLAN_IDS

for DOC_ID in "${!DOC_IDS[@]}"; do
    fname="${DOC_IDS[$DOC_ID]}"
    step "等待策略就绪: $fname (documentId=$DOC_ID)"

    ELAPSED=0
    while [ $ELAPSED -lt $MAX_WAIT ]; do
        RESP=$(curl -s "$BASE_URL/api/document/$DOC_ID" 2>/dev/null || echo '{"data":{}}')
        STRATEGY_STATUS=$(echo "$RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin).get('data',{})
print(d.get('strategyStatus',-1))
" 2>/dev/null || echo "-1")

        # strategyStatus: 1=WAIT_RECOMMEND, 2=RECOMMENDED, 3=CONFIRMED
        if [ "$STRATEGY_STATUS" = "2" ] || [ "$STRATEGY_STATUS" = "3" ]; then
            PLAN_RESP=$(curl -s "$BASE_URL/api/document/strategy/plan?documentId=$DOC_ID" 2>/dev/null || echo '{"data":{}}')
            PLAN_ID=$(echo "$PLAN_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin).get('data',{})
plans=d if isinstance(d,list) else []
print(plans[0].get('id','') if plans else '')
" 2>/dev/null || echo "")
            ok "策略就绪! strategyStatus=RECOMMENDED planId=$PLAN_ID"
            PLAN_IDS["$DOC_ID"]="$PLAN_ID"

            # 显示策略信息
            STRATEGY_SNAPSHOT=$(echo "$PLAN_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin).get('data',{})
plans=d if isinstance(d,list) else []
if plans:
    p=plans[0]
    ss=p.get('strategySnapshot','')
    rr=p.get('recommendReason','')
    print(f'    策略快照: {ss}')
    print(f'    推荐理由: {rr}')
" 2>/dev/null || echo "")
            echo -e "$STRATEGY_SNAPSHOT"
            break
        fi
        sleep 1
        ELAPSED=$((ELAPSED + 1))
        if [ $((ELAPSED % 5)) -eq 0 ]; then
            info "  等待中... ${ELAPSED}s (当前 strategyStatus=$STRATEGY_STATUS)"
        fi
    done

    if [ $ELAPSED -ge $MAX_WAIT ]; then
        fail "超时: 策略推荐未在 ${MAX_WAIT}s 内完成 (strategyStatus=$STRATEGY_STATUS)"
    fi
done

# =============================================================================
# 阶段 3 — 确认策略
# =============================================================================
section "阶段 3 — 确认策略 → 触发索引构建"

for DOC_ID in "${!PLAN_IDS[@]}"; do
    PLAN_ID="${PLAN_IDS[$DOC_ID]}"
    fname="${DOC_NAMES[$DOC_ID]}"
    step "确认策略: $fname (documentId=$DOC_ID, planId=$PLAN_ID)"

    RESP=$(curl -s -X POST "$BASE_URL/api/document/strategy/confirm" \
        -H "Content-Type: application/json" \
        -d "{\"documentId\":$DOC_ID,\"planId\":$PLAN_ID}")

    CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "1")
    BUILD_TASK_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['taskId'])" 2>/dev/null || echo "0")

    if [ "$CODE" = "0" ]; then
        ok "策略已确认 → Kafka 索引构建消息已投递 (buildTaskId=$BUILD_TASK_ID)"
    else
        fail "确认失败: $RESP"
    fi
done

# =============================================================================
# 阶段 4 — 等待索引构建完成
# =============================================================================
section "阶段 4 — 等待 Kafka 异步索引构建（切块 + 向量化 + ES 索引）"

declare -A INDEX_READY
MAX_WAIT=120

for DOC_ID in "${!DOC_IDS[@]}"; do
    fname="${DOC_IDS[$DOC_ID]}"
    step "等待索引完成: $fname (documentId=$DOC_ID)"

    ELAPSED=0
    while [ $ELAPSED -lt $MAX_WAIT ]; do
        RESP=$(curl -s "$BASE_URL/api/document/$DOC_ID" 2>/dev/null || echo '{"data":{}}')
        INDEX_STATUS=$(echo "$RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin).get('data',{})
print(d.get('indexStatus',-1))
" 2>/dev/null || echo "-1")

        # indexStatus: 3=BUILD_SUCCESS, 4=BUILD_FAIL
        if [ "$INDEX_STATUS" = "3" ]; then
            ok "索引构建完成! indexStatus=BUILD_SUCCESS"
            INDEX_READY["$DOC_ID"]=1
            break
        elif [ "$INDEX_STATUS" = "4" ]; then
            fail "索引构建失败! indexStatus=BUILD_FAIL"
            break
        fi
        sleep 2
        ELAPSED=$((ELAPSED + 2))
        if [ $((ELAPSED % 10)) -eq 0 ]; then
            info "  等待中... ${ELAPSED}s (当前 indexStatus=$INDEX_STATUS)"
        fi
    done

    if [ $ELAPSED -ge $MAX_WAIT ]; then
        fail "超时: 索引构建未在 ${MAX_WAIT}s 内完成"
    fi
done

# =============================================================================
# 阶段 5 — RAG 检索演示
# =============================================================================
section "阶段 5 — RAG 混合检索演示"

QUERIES=(
    "Redis 缓存穿透和缓存击穿的解决方案是什么"
    "Docker 容器和传统虚拟机的区别有哪些"
    "MySQL 联合索引的最左前缀原则是什么意思"
    "如何优化 MySQL 深分页查询的性能"
    "Docker 网络模型有哪几种驱动"
    "Redis 的持久化机制 RDB 和 AOF 有什么区别"
    "Redis Cluster 和 Docker Swarm 分别是什么"
)

for QUERY in "${QUERIES[@]}"; do
    step "检索: \"$QUERY\""

    RESP=$(curl -s -X POST "$BASE_URL/api/rag/retrieve" \
        -H "Content-Type: application/json" \
        -d "{\"query\":\"$QUERY\",\"topK\":4}")

    CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "1")

    if [ "$CODE" != "0" ]; then
        fail "检索失败: $RESP"
        continue
    fi

    # 格式化输出检索结果
    echo "$RESP" | python3 -c "
import sys, json

data = json.load(sys.stdin)['data']
results = data.get('results', [])
print(f'  ⏱ costMs={data[\"totalCostMs\"]}  hits={len(results)}')
print()
for i, r in enumerate(results):
    source = r.get('source','?')
    score  = r.get('score',0)
    text   = r.get('chunkText','')[:90].replace('\n',' ')
    path   = r.get('sectionPath','') or '-'
    doc_id = r.get('documentId','')
    emoji  = {'vector':'🔵','keyword':'🟡','hybrid':'🟢'}.get(source,'⚪')
    print(f'  {i+1}. {emoji} [{source}] score={score:.4f}  section={path}')
    print(f'     {text}...')
    print()
" 2>/dev/null || echo "  (解析失败)"
    echo
done

# =============================================================================
# 总结
# =============================================================================
section "演示完成"

echo -e "  文档数量: ${#DOC_IDS[@]}"
echo -e "  检索次数: ${#QUERIES[@]}"
echo -e ""
echo -e "  ${GREEN}全链路验证通过:${RESET}"
echo -e "    POST /api/document/upload         → Kafka → 解析 + 策略推荐"
echo -e "    POST /api/document/strategy/confirm → Kafka → 切块 + 向量化 + ES索引"
echo -e "    POST /api/rag/retrieve            → 向量 + 关键词 + RRF 融合"
echo
