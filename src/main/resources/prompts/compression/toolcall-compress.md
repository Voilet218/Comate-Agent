## 工具调用记录压缩

当你需要压缩历史工具调用记录以节省上下文空间时，按以下规范生成紧凑摘要。

## 压缩目标

保留每次工具调用的 **核心语义**，丢弃冗余细节，使后续召回时能准确理解当时做了什么、得到了什么。

## 输出格式

每条工具调用记录压缩为一行：

```
[序号] {动作描述} → {关键结果/结论}
```

如果某个调用对后续决策有影响，在行末加 `⚠️ 影响后续决策` 标记。

## 保留字段

| 必须保留 | 示例 |
|----------|------|
| 工具名称 | `read_file`, `bash` |
| 执行目的 | "读取配置文件" |
| 关键参数 | 文件名、搜索模式、URL |
| 结果概要 | "找到 3 处匹配"、"返回 404" |

## 可丢弃

- 完整文件内容（仅保留摘要或行数）
- 错误堆栈的重复尝试
- 中间态临时变量值
- user 确认提示语
- 与当前任务路径无关的无关调用

## 压缩示例

```
# 原始（3 个调用，约 1500 token）
read_file src/config.yml → 读取配置文件，内容含 db.host / db.port / api.key
grep_code "api.key" src/ → 找到 2 处引用，分别在 ConfigLoader.java:12 和 main.ts:45
write_file src/config.yml → 将 api.key 替换为占位符 ${API_KEY}  ⚠️ 影响后续决策

# 压缩后（3 行，约 120 token）
[1] read_file 读取 src/config.yml → 含 db.host / db.port / api.key 三个字段
[2] grep_code 搜索 "api.key" 引用 → ConfigLoader.java:12, main.ts:45 两处
[3] write_file 替换 api.key 为 ${API_KEY} 占位符  ⚠️ 影响后续决策
```
