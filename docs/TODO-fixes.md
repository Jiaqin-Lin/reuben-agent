# 待修复 TODO

> 已定位根因、待开新 session 修复的问题。修复后删除对应条目。
> 当前对话上下文已接近上限,新 session 按 below 逐条处理即可。

---

## 1. [BUG] MyBatis-Plus 分页插件缺失,所有 selectPage 的 total 返回 0

**优先级**:高(直接影响运营总览、文档列表、会话列表等所有分页页面的总数显示)

**现象**:
- 运营总览:文档总数 0、会话总数 0、索引成功率 0(但"解析成功"显示 1,因为那是前端 filter records 算的)
- `GET /api/document/page?pageNo=1&pageSize=1` 返回 `{"total":"0", "records":[{...1条文档...}]}` —— total 是 0 但 records 有数据
- `GET /api/chat/session/list` 同样 total=0

**根因**:
- `common/src/main/java/com/reubenagent/common/config/MybatisPlusAutoFillConfiguration.java` 只注册了 `MetaObjectHandler`(自动填充 createTime/updateTime/isDeleted),**没有注册 `PaginationInnerInterceptor`(分页拦截器)**
- 没有分页拦截器时,`mapper.selectPage(new Page<>(...), wrapper)` 的行为:
  - 不执行 COUNT,`page.getTotal()` 返回 0
  - 不拼 `LIMIT`,把全部记录塞进 records 返回
- 所以后端 `DocumentManageServiceImpl.pageQuery()`(`:663` `Long total = page.getTotal()`)拿到的是 0
- 前端 `AdminDashboardPage.tsx:101` `total: page.total ?? docs.length` —— `??` 只挡 null/undefined,挡不住 0,于是显示 0

**修复方向**:
在 `common` 模块新增(或并入现有配置类)MyBatis-Plus 拦截器配置:

```java
@Configuration
public class MybatisPlusConfiguration {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件(MySQL 方言;若多数据库需用 MultipetDbType)
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

依赖 `MybatisPlusInterceptor` / `PaginationInnerInterceptor` 来自 `mybatis-plus-jsqlparser` / `mybatis-plus-core`,版本 3.5.7,项目已引入,无需加依赖。

**验证**:
- 重启后端,`GET /api/document/page?pageNo=1&pageSize=1` 的 `total` 应为实际文档数(字符串形式)
- 运营总览文档总数、会话总数应正常显示
- 检查是否需要 `DbType.MYSQL`(项目用 MySQL 8.0,端口 3307)

**注意**:
- `common` 是所有 business 模块的共享依赖,在这里加配置会被 `launcher` 的 `scanBasePackages="com.reubenagent"` 扫描到,全局生效
- 加完后所有 `selectPage` 都会正常分页 + COUNT,留意是否有依赖"不分页返回全部"的代码(理论上不该有)

---

## 2. [BUG] 雪花 ID 前端精度丢失(turnId 等所有 Long ID)

**优先级**:高(直接导致对话详情"轮次不存在",且所有超过 16 位的雪花 ID 都有风险)

**现象**:
- `GET /api/chat/exchange/detail?turnId=2295015740257878000` 返回 `{"code":30002,"message":"轮次不存在 —— 2295015740257878000"}`
- 实际 turnId 是 `2295015740257878017`,末三位 `017` 被截成 `000`

**根因**:
- 雪花 ID 是 19 位 long,超过 JS `Number.MAX_SAFE_INTEGER`(2^53 ≈ 9007199254740992,16 位)
- 后端**部分**字段已序列化为字符串(如 `ChatTurnVo.turnId` 在列表接口返回 `"2295018111079825408"` 带引号),但详情接口或前端某些路径仍按 number 解析 → `JSON.parse` 丢精度
- 前端类型定义里多处 `turnId: number`(`ui/src/types/displayMessage.ts:9`、`ui/src/types/chat.ts:61/145/205/231`、`ui/src/types/knowledge.ts:74`),即使后端返字符串,类型层面也容易在运算/比较时被隐式转 number

**影响范围**(全栈,所有雪花 ID 都有风险):
- turnId、documentId、chunkId、parentBlockId、taskId、planId、structureNodeId 等
- 已确认对话详情链路有问题;文档 chunk 列表的 `documentId`/`chunkId` 在列表接口返回里已是字符串,但前端类型是 number,存在隐患

**修复方向**(全栈统一改):
1. **后端**:所有 VO/DTO 里的 `Long` 类型 ID 字段统一加 `@JsonSerialize(using = ToStringSerializer.class)`,或全局配置 Jackson 把 Long 序列化成字符串。重点检查:
   - `business/chat/src/main/java/com/reubenagent/chat/vo/` 下所有 VO
   - `business/document/src/main/java/com/reubenagent/document/vo/` 下所有 VO
   - 特别是详情接口返回的 turnId(列表已字符串化,详情可能漏了)
   - 推荐用全局 Jackson 配置(`Jackson2ObjectMapperBuilderCustomizer`),一次性把 `Long.class` 序列化为 String,避免逐个加注解遗漏
2. **前端**:把所有 ID 类型从 `number` 改成 `string`,相关比较/拼接/传参改用字符串。涉及:
   - `ui/src/types/chat.ts`、`types/document.ts`、`types/displayMessage.ts`、`types/knowledge.ts`
   - `ui/src/api/chat.ts:73/83/93` 等 `turnId: number` → `string`
   - 所有 `Number(id)` 转换、路由参数解析处
3. **验证**:对话详情 turnId、文档 chunk 列表 ID、检索引用 chunkId 等全链路 ID 末位不被截断

**建议**:改动较大,建议分模块逐步推进(先 chat 链路,再 document 链路),每个模块改完手动验证 ID 末位正确。

---

## 附:代理环境变量(已排除,非问题)

之前怀疑 `http_proxy=http://127.0.0.1:7897` 导致模型调用 502,但用户确认对话能正常进行(已回答 2 轮),说明 Spring AI 调 DeepSeek 不受系统代理影响。此条作废,无需处理。
