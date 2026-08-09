# 问题跟踪器：GitHub Issues

Fluorite 的问题、功能请求和需要长期跟踪的开发任务记录在当前仓库的 GitHub Issues 中。运行命令时，`gh` 会从仓库的 `origin` 自动识别 `DsWePm/Fluorite`。

## 使用约定

- 创建：`gh issue create --title "..." --body "..."`
- 阅读：`gh issue view <编号> --comments`
- 列表：`gh issue list --state open --json number,title,body,labels,comments`
- 评论：`gh issue comment <编号> --body "..."`
- 添加或移除标签：`gh issue edit <编号> --add-label "..."` / `--remove-label "..."`
- 关闭：`gh issue close <编号> --comment "..."`

创建 Issue 前应先搜索是否已有同一故障或决策，避免把同一条证据链拆散。Issue 保存尚未完成、需要协作或值得独立追踪的工作；已经结案的实现过程归入开发日志，而不是长期堆积在主开发文档中。

当工程技能要求“发布到问题跟踪器”时，含义就是创建 GitHub Issue；要求“读取相关工单”时，读取 Issue 正文、标签和评论。
