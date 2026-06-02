# Audiobookshelf Socket.io 阶段 6 评估记录

日期：2026-06-02

## 结论

阶段 6 只记录 Socket.io 的能力边界，不把它接入当前首版同步正确性路径。

当前首版继续坚持：

- catalog 发现仍然走 `GET /api/libraries/<id>/items?limit=0&minified=1&collapseseries=0`
- item 详情仍然走 `POST /api/items/batch/get`
- 播放进度仍然走 `/play`、`/sync`、`/close`

也就是说：

- Socket.io 不是首版同步的前置依赖
- 没有新增任何运行时代码依赖
- 首版同步出错时，也不需要排查 websocket 链路

## 可以关注的事件能力

后续如果要把 Socket.io 纳入优化项，可以重点评估这些能力：

- 远端 library item 新增、更新、删除事件
- 远端 progress 变化事件
- 服务端扫描开始、完成事件
- 会话同步相关事件

## 暂不接入的原因

- 首版已经有全量 minified 清单作为正确性基线
- 当前增量优化只是在“全量发现不变”的前提下减少 detail 拉取，不需要实时推送才能成立
- Socket.io 一旦接入，就会引入连接状态、重连、鉴权续期、事件乱序和幂等等一组新的运行时复杂度

## 后续 TODO

- 等首版 book catalog mirror、播放、进度同步稳定后，再单独评估 Socket.io 是否值得作为“体验优化层”
- 如果要接入，必须保持“断开 websocket 后仍可完整退回 REST 全量同步”
- 不允许把 Socket.io 事件变成唯一真相源；本地 Room 镜像和 REST 全量校正仍然要保留
